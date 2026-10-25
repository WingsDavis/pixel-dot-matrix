package com.example.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.AtomicFile
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.json.JSONObject

internal class WearLogoStore(
    context: Context,
    private val writeBytes: (FileOutputStream, ByteArray) -> Unit = { output, bytes -> output.write(bytes) }
) {
    private val record = AtomicFile(File(context.filesDir, RECORD_FILENAME))
    private val legacy = File(context.filesDir, WearSyncService.LOGO_FILENAME)

    fun apply(pngBytes: ByteArray?, revision: String, timestamp: Long, reset: Boolean): Boolean = synchronized(lock) {
        requireMetadata(revision, timestamp)
        require(reset == (pngBytes == null)) { "Logo reset must have no image; uploads require a PNG" }
        val png = pngBytes?.also(::requirePngBytes)?.copyOf()
        png?.let { decodePng(it, sampleLegacy = false).recycle() }

        val previous = try {
            readRecord()
        } catch (error: Exception) {
            if (!reset || error is FutureSchemaException) throw error
            // Recovery resets trust the source phone's timestamp floor when local ordering is lost.
            // shortcut: repeated record corruption loses historical order; revisit if the phone's timestamp floor can also be lost.
            null
        }
        if (!reset) previous?.png?.let { decodePng(it, sampleLegacy = false).recycle() }
        if (previous?.revision == revision) {
            check(previous.timestamp == timestamp && previous.reset == reset && previous.png.contentEquals(png)) {
                "Logo revision identity does not match its saved payload"
            }
            cleanupLegacy()
            return@synchronized false
        }
        // A timestamp tie cannot safely order an upload against a reset.
        check(previous == null || timestamp > previous.timestamp) { "A newer logo revision is already saved" }
        val bytes = JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("revision", revision)
            .put("timestamp", timestamp)
            .put("reset", reset)
            .put("png", png?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: JSONObject.NULL)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RECORD_BYTES) { "Logo record exceeds 512 KiB" }

        val output = record.startWrite()
        try {
            writeBytes(output, bytes)
            output.fd.sync()
            record.finishWrite(output)
        } catch (error: Exception) {
            record.failWrite(output)
            throw error
        }
        // AtomicFile can log a failed rename without throwing; do not acknowledge an uncommitted revision.
        if (!readBounded(record.openRead(), MAX_RECORD_BYTES).contentEquals(bytes)) {
            throw IOException("Logo revision could not be committed")
        }
        cleanupLegacy()
        true
    }

    fun bitmapOrNull(): Bitmap? = synchronized(lock) {
        try {
            val saved = readRecord()
            when {
                saved != null -> saved.png?.let { decodePng(it, sampleLegacy = false) }
                legacy.isFile -> decodePng(readBounded(legacy.inputStream(), MAX_IMAGE_BYTES), sampleLegacy = true)
                else -> null
            }
        } catch (_: Exception) {
            // A corrupt authoritative record must not revive a legacy image after a reset.
            null
        }
    }

    fun revision(): String? = synchronized(lock) {
        try {
            readRecord()?.revision
        } catch (_: Exception) {
            null
        }
    }

    private fun readRecord(): Record? {
        if (!record.baseFile.exists() && !File(record.baseFile.path + ".bak").exists()) return null
        val json = JSONObject(readBounded(record.openRead(), MAX_RECORD_BYTES).toString(Charsets.UTF_8))
        val schema = if (json.has("schema_version")) json.getLong("schema_version") else SCHEMA_VERSION
        if (schema > SCHEMA_VERSION) throw FutureSchemaException(schema)
        require(schema == SCHEMA_VERSION) { "Invalid logo record schema" }
        val revision = json.getString("revision")
        val timestamp = json.getLong("timestamp")
        requireMetadata(revision, timestamp)
        val reset = json.getBoolean("reset")
        val png = if (json.isNull("png")) null else {
            val encoded = json.getString("png")
            require(encoded.length <= MAX_BASE64_BYTES) { "Logo image exceeds 256 KiB" }
            Base64.decode(encoded, Base64.NO_WRAP).also(::requirePngBytes)
        }
        require(reset == (png == null)) { "Invalid logo reset record" }
        return Record(revision, timestamp, reset, png)
    }

    private fun cleanupLegacy() {
        if (legacy.exists() && !legacy.delete()) throw IOException("Could not remove legacy logo")
    }

    private data class Record(val revision: String, val timestamp: Long, val reset: Boolean, val png: ByteArray?)
    private class FutureSchemaException(schema: Long) : IOException("Unsupported logo record schema: $schema")

    companion object {
        internal const val RECORD_FILENAME = "watchface_logo.json"
        internal const val MAX_IMAGE_BYTES = 256 * 1024
        internal const val MAX_RECORD_BYTES = 512 * 1024
        private const val SCHEMA_VERSION = 1L
        private const val MAX_SIDE = 128
        private const val MAX_BASE64_BYTES = ((MAX_IMAGE_BYTES + 2) / 3) * 4
        private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private val lock = Any()

        private fun requireMetadata(revision: String, timestamp: Long) {
            require(revision.isNotBlank() && revision.length <= 256 && revision.none(Char::isISOControl)) {
                "Invalid logo revision"
            }
            require(timestamp > 0L) { "Invalid logo timestamp" }
        }

        private fun requirePngBytes(bytes: ByteArray) {
            require(bytes.size in signature.size..MAX_IMAGE_BYTES) { "Logo image must be at most 256 KiB" }
            require(signature.indices.all { bytes[it] == signature[it] }) { "Logo image must be PNG" }
        }

        private fun decodePng(bytes: ByteArray, sampleLegacy: Boolean): Bitmap {
            requirePngBytes(bytes)
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                // The header callback runs before pixel allocation, including for legacy files.
                require(info.mimeType == "image/png" && width > 0 && height > 0) { "Invalid PNG bounds" }
                require(sampleLegacy || (width <= MAX_SIDE && height <= MAX_SIDE)) { "Logo image exceeds 128 x 128" }
                var sample = 1
                while (width.toLong() > MAX_SIDE.toLong() * sample || height.toLong() > MAX_SIDE.toLong() * sample) {
                    sample *= 2
                }
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
            if (bitmap.width !in 1..MAX_SIDE || bitmap.height !in 1..MAX_SIDE) {
                bitmap.recycle()
                throw IOException("Decoded logo exceeds 128 x 128")
            }
            return bitmap
        }

        private fun readBounded(input: FileInputStream, maximum: Int): ByteArray = input.use { stream ->
            if (stream.channel.size() > maximum) throw IOException("Logo file exceeds $maximum bytes")
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > maximum) throw IOException("Logo file exceeds $maximum bytes")
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    }
}
