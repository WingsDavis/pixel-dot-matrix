package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.core.protection.DomainProfileStore
import com.example.core.protection.DomainRules
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class LocalDnsSinkholeVpnService : VpnService() {
    private val domainProfiles by lazy { DomainProfileStore(this) }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "LocalDnsSinkholeVpn"
        const val ACTION_START = "com.example.service.START_VPN"
        const val ACTION_STOP = "com.example.service.STOP_VPN"
        private const val CHANNEL_ID = "VpnServiceChannel"
        private const val NOTIFICATION_ID = 1002
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val LOCAL_DNS_ADDRESS = "10.0.0.1"
        private const val UPSTREAM_DNS_ADDRESS = "8.8.8.8"
        private const val DNS_PORT = 53

        // High frequency distraction domains to sinkhole
        val SINKHOLE_DOMAINS = listOf(
            "facebook.com", "instagram.com", "tiktok.com", "twitter.com", "x.com",
            "reddit.com", "youtube.com", "news.google.com"
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnInterface != null) return // Already running

        Log.d(TAG, "Starting Local DNS Sinkhole VPN...")
        try {
            // Build the local VPN interface
            val builder = Builder()
                .setSession("Pixel Dot Matrix DNS Protection")
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(LOCAL_DNS_ADDRESS)
                .addRoute(LOCAL_DNS_ADDRESS, 32)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()

            startVpnForeground()

            vpnJob = serviceScope.launch {
                val input = FileInputStream(vpnInterface?.fileDescriptor)
                val output = FileOutputStream(vpnInterface?.fileDescriptor)
                val buffer = ByteBuffer.allocate(32768)

                while (isActive && vpnInterface != null) {
                    try {
                        val length = input.read(buffer.array())
                        if (length > 0) {
                            val packet = buffer.array().copyOf(length)
                            val response = handleDnsPacket(packet)
                            if (response != null) {
                                output.write(response)
                            }
                        }
                        buffer.clear()
                    } catch (e: Exception) {
                        Log.e(TAG, "Packet loop stopped", e)
                        break
                    }
                }
            }
            Log.d(TAG, "VPN Interface established successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "Stopping Local DNS Sinkhole VPN...")
        vpnJob?.cancel()
        vpnJob = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // ignore
        }
        vpnInterface = null
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    private fun startVpnForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun handleDnsPacket(packet: ByteArray): ByteArray? {
        val parsed = Ipv4UdpDnsPacket.parse(packet) ?: return null
        if (parsed.destinationPort != DNS_PORT) return null

        val question = DnsMessage.parseQuestion(parsed.payload) ?: return null
        val shouldBlock = !domainProfiles.isTemporarilyUnblocked() &&
            DomainRules.matches(question.domain, domainProfiles.activeDomains())

        val dnsResponse = if (shouldBlock) {
            Log.d(TAG, "Sinkholed DNS query for ${question.domain}")
            DnsMessage.buildNxDomainResponse(parsed.payload, question.questionEndOffset)
        } else {
            forwardDnsQuery(parsed.payload)
        } ?: return null

        return parsed.buildUdpResponse(dnsResponse)
    }

    private suspend fun forwardDnsQuery(query: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = 3000
                val upstream = InetAddress.getByName(UPSTREAM_DNS_ADDRESS)
                socket.send(DatagramPacket(query, query.size, upstream, DNS_PORT))

                val responseBuffer = ByteArray(1500)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(responsePacket)
                responseBuffer.copyOf(responsePacket.length)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to forward DNS query", e)
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS Sinkhole VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DNS Sinkhole VPN Active")
            .setContentText("Blocking distraction domains at the network level.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }

    private data class Ipv4UdpDnsPacket(
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val payload: ByteArray
    ) {
        fun buildUdpResponse(dnsPayload: ByteArray): ByteArray {
            val ipHeaderLength = 20
            val udpHeaderLength = 8
            val totalLength = ipHeaderLength + udpHeaderLength + dnsPayload.size
            val response = ByteArray(totalLength)

            response[0] = 0x45
            response[1] = 0
            response.writeShort(2, totalLength)
            response.writeShort(4, 0)
            response.writeShort(6, 0)
            response[8] = 64
            response[9] = 17
            destinationAddress.copyInto(response, 12)
            sourceAddress.copyInto(response, 16)
            response.writeShort(10, ipv4Checksum(response, 0, ipHeaderLength))

            response.writeShort(20, destinationPort)
            response.writeShort(22, sourcePort)
            response.writeShort(24, udpHeaderLength + dnsPayload.size)
            response.writeShort(26, 0)
            dnsPayload.copyInto(response, 28)
            response.writeShort(26, udpChecksum(response, 20, udpHeaderLength + dnsPayload.size, response.copyOfRange(12, 16), response.copyOfRange(16, 20)))

            return response
        }

        companion object {
            fun parse(packet: ByteArray): Ipv4UdpDnsPacket? {
                if (packet.size < 28) return null
                val version = (packet[0].toInt() ushr 4) and 0x0f
                if (version != 4) return null

                val ihl = (packet[0].toInt() and 0x0f) * 4
                if (packet.size < ihl + 8 || packet[9].toInt() and 0xff != 17) return null

                val sourceAddress = packet.copyOfRange(12, 16)
                val destinationAddress = packet.copyOfRange(16, 20)
                val sourcePort = packet.readUnsignedShort(ihl)
                val destinationPort = packet.readUnsignedShort(ihl + 2)
                val udpLength = packet.readUnsignedShort(ihl + 4)
                if (udpLength < 8 || packet.size < ihl + udpLength) return null

                return Ipv4UdpDnsPacket(
                    sourceAddress = sourceAddress,
                    destinationAddress = destinationAddress,
                    sourcePort = sourcePort,
                    destinationPort = destinationPort,
                    payload = packet.copyOfRange(ihl + 8, ihl + udpLength)
                )
            }
        }
    }

    private data class DnsQuestion(val domain: String, val questionEndOffset: Int)

    private object DnsMessage {
        fun parseQuestion(message: ByteArray): DnsQuestion? {
            if (message.size < 12 || message.readUnsignedShort(4) < 1) return null

            var offset = 12
            val labels = mutableListOf<String>()
            while (offset < message.size) {
                val length = message[offset].toInt() and 0xff
                offset += 1
                if (length == 0) break
                if ((length and 0xc0) != 0 || offset + length > message.size) return null
                labels += message.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
                offset += length
            }

            if (labels.isEmpty() || offset + 4 > message.size) return null
            return DnsQuestion(labels.joinToString(".").lowercase(), offset + 4)
        }

        fun buildNxDomainResponse(query: ByteArray, questionEndOffset: Int): ByteArray {
            val response = query.copyOf(questionEndOffset)
            response[2] = 0x81.toByte()
            response[3] = 0x83.toByte()
            response.writeShort(6, 0)
            response.writeShort(8, 0)
            response.writeShort(10, 0)
            return response
        }
    }

}

private fun ByteArray.readUnsignedShort(offset: Int): Int {
    return ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
}

private fun ByteArray.writeShort(offset: Int, value: Int) {
    this[offset] = ((value ushr 8) and 0xff).toByte()
    this[offset + 1] = (value and 0xff).toByte()
}

private fun ipv4Checksum(data: ByteArray, offset: Int, length: Int): Int {
    var sum = 0
    var i = offset
    while (i < offset + length) {
        if (i == offset + 10) {
            i += 2
            continue
        }
        sum += data.readUnsignedShort(i)
        i += 2
    }
    return finalizeChecksum(sum)
}

private fun udpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int, sourceIp: ByteArray, destinationIp: ByteArray): Int {
    var sum = 0
    sum += sourceIp.readUnsignedShort(0)
    sum += sourceIp.readUnsignedShort(2)
    sum += destinationIp.readUnsignedShort(0)
    sum += destinationIp.readUnsignedShort(2)
    sum += 17
    sum += udpLength

    var i = udpOffset
    while (i < udpOffset + udpLength) {
        if (i == udpOffset + 6) {
            i += 2
            continue
        }
        val high = packet[i].toInt() and 0xff
        val low = if (i + 1 < udpOffset + udpLength) packet[i + 1].toInt() and 0xff else 0
        sum += (high shl 8) or low
        i += 2
    }
    val checksum = finalizeChecksum(sum)
    return if (checksum == 0) 0xffff else checksum
}

private fun finalizeChecksum(initial: Int): Int {
    var sum = initial
    while ((sum ushr 16) != 0) {
        sum = (sum and 0xffff) + (sum ushr 16)
    }
    return sum.inv() and 0xffff
}
