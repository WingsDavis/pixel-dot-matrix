package com.example.wear

import android.content.Context
import android.util.AtomicFile
import com.example.core.sync.SyncAppliedAck
import com.example.core.sync.TimerCommand
import com.example.core.sync.WearCommandProtocol
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class WearCommandOutbox internal constructor(
    context: Context,
    private val transport: WearCommandTransport,
    private val scheduleRetry: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val persist: (AtomicFile, ByteArray) -> Unit = ::writeAtomically,
    private val cancelRetry: () -> Unit = {}
) {
    constructor(context: Context, nodeClient: NodeClient, messageClient: MessageClient) : this(
        context.applicationContext,
        GoogleWearCommandTransport(Wearable.getDataClient(context), nodeClient, messageClient),
        { WearCommandRetryService.schedule(context.applicationContext) },
        cancelRetry = { WearCommandRetryService.cancel(context.applicationContext) }
    )

    private val journal = synchronized(journals) {
        val file = File(context.filesDir, "wear_command_outbox.json").canonicalFile
        journals.getOrPut(file.path) { Journal(AtomicFile(file)) }
    }

    val records: StateFlow<List<WearQueuedCommand>> = journal.records.asStateFlow()

    init {
        synchronized(journal) {
            journal.failure?.let { throw it }
            if (journal.exists()) {
                val entries = read()
                if (entries.any { it.pending }) scheduleRetry()
                journal.records.value = entries.map { it.record }.sortedBy { it.createdAt }
            } else {
                val legacy = context.getSharedPreferences("wear_command_outbox", Context.MODE_PRIVATE)
                    .getStringSet("items", emptySet()).orEmpty().toSet()
                var legacyBytes = 0L
                legacy.forEach {
                    legacyBytes += it.toByteArray(Charsets.UTF_8).size
                    if (legacyBytes > MAX_JOURNAL_BYTES) {
                        throw IOException("Legacy outbox exceeds 512 KiB; original preferences retained")
                    }
                }
                val entries = legacy.map(::migrate)
                check(entries.map { it.record.id }.distinct().size == entries.size) {
                    "Conflicting legacy command IDs; original preferences retained"
                }
                if (entries.any { it.pending }) scheduleRetry()
                if (entries.isNotEmpty()) save(entries)
                else journal.records.value = emptyList()
                // Keep the original set intact for recovery; its iteration order is not a command order.
            }
        }
    }

    @JvmOverloads
    fun sendOrQueue(
        path: String,
        payload: String,
        baseRevision: Long,
        commandId: String = UUID.randomUUID().toString()
    ): Boolean {
        require(path in WearCommandProtocol.paths) { "Unsupported command path" }
        require(path != "/incident/mark" || payload == "DISTRACTION") { "Invalid incident command" }
        require(WearCommandProtocol.validId(commandId)) { "Invalid command ID" }
        val command = TimerCommand(id = commandId, action = payload, baseRevision = baseRevision)
        require(payload.isNotBlank() && TimerCommand.decode(command.encode()) == command) {
            "Invalid command payload"
        }
        val entry = Entry(
            WearQueuedCommand(command.id, path, payload, WearCommandStatus.QUEUED, null, now().coerceAtLeast(0)),
            command.encode().toString(Charsets.UTF_8)
        )
        synchronized(journal) {
            val entries = read()
            val existing = entries.firstOrNull { it.record.id == commandId }
            if (existing != null) {
                require(existing.record.path == path && existing.record.action == payload) {
                    "Command ID already belongs to a different command"
                }
                // Duplicate enqueue never rebases an existing command; only an explicit retry may do that.
                return existing.record.status == WearCommandStatus.DELIVERED ||
                    existing.record.status == WearCommandStatus.APPLIED
            }
            requireCapacity(entries)
            scheduleRetry()
            save(entries + entry)
        }
        return deliver(command.id)
    }

    @JvmOverloads
    fun flush(shouldContinue: () -> Boolean = { true }) {
        if (!shouldContinue()) return
        val entries = synchronized(journal) { read() }
        entries.filter { it.pending }.forEach {
            if (!shouldContinue()) return
            deliver(it.record.id, shouldContinue)
        }
        entries.filter { it.record.status == WearCommandStatus.APPLIED }.forEach {
            if (!shouldContinue()) return
            runCatching { transport.remove(it.record.id) }
        }
    }

    fun acknowledge(bytes: ByteArray) {
        val acknowledgement = SyncAppliedAck.decode(bytes) ?: return
        acknowledge(acknowledgement)
    }

    fun acknowledge(id: String) {
        if (WearCommandProtocol.validId(id)) acknowledge(SyncAppliedAck(id, true))
    }

    private fun acknowledge(ack: SyncAppliedAck) {
        synchronized(journal) {
            val entries = read()
            val entry = entries.firstOrNull { it.record.id == ack.id } ?: return
            if (entry.record.status == WearCommandStatus.APPLIED) {
                if (!ack.applied) return
            } else {
                val status = if (ack.applied) WearCommandStatus.APPLIED else WearCommandStatus.FAILED
                val error = if (ack.applied) null else ack.message ?: "Phone could not apply this command"
                if (entry.record.status == status && entry.record.error == error) return
                val updated = entry.copy(
                    record = entry.record.copy(status = status, error = error),
                    updatedAt = maxOf(now().coerceAtLeast(0), entries.maxOf { it.updatedAt } + 1)
                )
                val changed = entries.map { if (it == entry) updated else it }
                val retained = changed.filter { it.record.status == WearCommandStatus.APPLIED }
                    .sortedBy { it.updatedAt }.takeLast(MAX_APPLIED_RECEIPTS).map { it.record.id }.toSet()
                save(changed.filter { it.record.status != WearCommandStatus.APPLIED || it.record.id in retained })
            }
        }
        if (ack.applied) runCatching { transport.remove(ack.id) }
    }

    fun retry(id: String, latestBaseRevision: Long): Boolean {
        synchronized(journal) {
            val entries = read()
            val entry = entries.firstOrNull { it.record.id == id } ?: return false
            if (entry.record.status != WearCommandStatus.FAILED) return false
            val command = TimerCommand.decode(entry.encoded.toByteArray(Charsets.UTF_8)) ?: return false
            if (entry.record.path !in WearCommandProtocol.paths || command.id != id) return false
            if (entry.record.path == "/incident/mark" && command.action != "DISTRACTION") return false
            requireCapacity(entries)
            scheduleRetry()
            save(entries.map {
                if (it.record.id != id) it else entry.copy(
                    record = entry.record.copy(status = WearCommandStatus.QUEUED, error = null),
                    encoded = command.copy(baseRevision = latestBaseRevision).encode().toString(Charsets.UTF_8),
                    attempts = 0,
                    nextAttemptAt = 0
                )
            })
        }
        return deliver(id)
    }

    private fun deliver(id: String, shouldContinue: () -> Boolean = { true }): Boolean {
        val attempt = synchronized(journal) {
            if (!shouldContinue()) return false
            val entries = read()
            val entry = entries.firstOrNull { it.record.id == id } ?: return false
            val timestamp = now().coerceAtLeast(0)
            if (!entry.pending || id in journal.inFlight || timestamp < entry.nextAttemptAt) return false
            if (entry.attempts >= MAX_ATTEMPTS) {
                save(entries.map {
                    if (it != entry) it else entry.copy(record = entry.record.copy(
                        status = WearCommandStatus.FAILED,
                        error = entry.record.error ?: "No applied acknowledgement after $MAX_ATTEMPTS attempts"
                    ))
                })
                return false
            }
            val updated = entry.copy(
                attempts = entry.attempts + 1,
                updatedAt = maxOf(timestamp, entry.updatedAt + 1),
                nextAttemptAt = timestamp + backoff(entry.attempts)
            )
            // Persist the attempt before publishing so recreation changes the DataItem even without an ACK.
            save(entries.map { if (it == entry) updated else it })
            journal.inFlight.add(id)
            updated
        }
        try {
            if (!shouldContinue()) return false
            val published = runCatching {
                transport.publish(
                    id, attempt.record.path, attempt.encoded.toByteArray(Charsets.UTF_8),
                    attempt.record.createdAt, attempt.attempts, attempt.updatedAt
                )
            }
            if (!shouldContinue()) return false
            val stillPending = synchronized(journal) { read().firstOrNull { it.record.id == id } == attempt }
            val sent = if (!stillPending) Result.success(false) else {
                if (!shouldContinue()) return false
                runCatching {
                    transport.sendLegacy(
                        attempt.record.path, attempt.encoded.toByteArray(Charsets.UTF_8), attempt.record.createdAt
                    )
                }
            }
            if (!shouldContinue()) return false
            val delivered = sent.getOrDefault(false)
            val error = if (published.isSuccess || delivered) null else {
                val cause = published.exceptionOrNull() ?: sent.exceptionOrNull()
                cause?.message?.take(512) ?: "Command transport unavailable"
            }
            synchronized(journal) {
                val entries = read()
                val current = entries.firstOrNull { it.record.id == id }
                if (current == attempt) {
                    val status = when {
                        error != null && attempt.attempts >= MAX_ATTEMPTS -> WearCommandStatus.FAILED
                        delivered -> WearCommandStatus.DELIVERED
                        else -> attempt.record.status
                    }
                    save(entries.map {
                        if (it != attempt) it else attempt.copy(record = attempt.record.copy(status = status, error = error))
                    })
                }
            }
            // Preserve the existing Boolean: a connected peer accepted a message, not necessarily the command.
            return delivered
        } finally {
            synchronized(journal) { journal.inFlight.remove(id) }
        }
    }

    private fun read(): List<Entry> {
        journal.failure?.let { throw it }
        if (!journal.exists()) return emptyList()
        val root = JSONObject(readBounded(journal.file).toString(Charsets.UTF_8))
        check(root.getInt("version") == 1) { "Unsupported outbox journal version" }
        val entries = root.getJSONArray("entries")
        return List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            val encoded = item.getString("payload")
            val command = TimerCommand.decode(encoded.toByteArray(Charsets.UTF_8))
            val id = item.getString("id")
            val path = item.getString("path")
            val status = WearCommandStatus.valueOf(item.getString("status"))
            check(WearCommandProtocol.validId(id)) { "Invalid stored command ID" }
            if (status == WearCommandStatus.QUEUED || status == WearCommandStatus.DELIVERED) {
                check(path in WearCommandProtocol.paths && command?.id == id) { "Invalid pending command" }
            }
            Entry(
                WearQueuedCommand(id, path, command?.action ?: encoded, status,
                    if (item.isNull("error")) null else item.getString("error"), item.getLong("created_at")),
                encoded, item.getInt("attempts"), item.getLong("next_attempt_at"), item.getLong("updated_at")
            ).also {
                check(it.record.createdAt >= 0 && it.attempts in 0..MAX_ATTEMPTS &&
                    it.nextAttemptAt >= 0 && it.updatedAt >= 0) {
                    "Invalid stored retry metadata"
                }
            }
        }.also { check(it.map { entry -> entry.record.id }.distinct().size == it.size) { "Duplicate command IDs" } }
    }

    private fun save(entries: List<Entry>) {
        val values = JSONArray()
        entries.forEach { entry ->
            values.put(JSONObject().apply {
                put("id", entry.record.id)
                put("path", entry.record.path)
                put("payload", entry.encoded)
                put("status", entry.record.status.name)
                put("error", entry.record.error ?: JSONObject.NULL)
                put("created_at", entry.record.createdAt)
                put("attempts", entry.attempts)
                put("next_attempt_at", entry.nextAttemptAt)
                put("updated_at", entry.updatedAt)
            })
        }
        val bytes = JSONObject().put("version", 1).put("entries", values).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_JOURNAL_BYTES) {
            throw IOException("Outbox journal is full (512 KiB); existing commands were kept")
        }
        val previous = if (journal.exists()) readBounded(journal.file) else null
        try {
            persist(journal.file, bytes)
            if (!readBounded(journal.file).contentEquals(bytes)) throw IOException("Outbox journal did not commit")
        } catch (error: Exception) {
            // A writer may replace the base file before reporting failure; restore the known committed bytes.
            try {
                val unchanged = if (previous == null) !journal.exists() else
                    runCatching { readBounded(journal.file).contentEquals(previous) }.getOrDefault(false)
                if (!unchanged && previous == null) {
                    journal.file.delete()
                    if (journal.exists()) throw IOException("Could not restore the empty outbox")
                } else if (!unchanged && previous != null) {
                    writeAtomically(journal.file, previous)
                    if (!readBounded(journal.file).contentEquals(previous)) throw IOException("Could not restore outbox")
                }
            } catch (recovery: Exception) {
                journal.failure = IOException("Outbox recovery failed; storage is unavailable", recovery)
                error.addSuppressed(recovery)
            }
            throw error
        }
        journal.records.value = entries.map { it.record }.sortedBy { it.createdAt }
        if (entries.none { it.pending }) cancelRetry()
    }

    private fun migrate(item: String): Entry {
        val separator = item.indexOf('\n')
        val path = if (separator < 0) "" else item.substring(0, separator)
        val encoded = if (separator < 0) item else item.substring(separator + 1)
        val command = encoded.takeIf { it.startsWith("v2|") }
            ?.let { TimerCommand.decode(it.toByteArray(Charsets.UTF_8)) }
        val valid = command != null && path in WearCommandProtocol.paths &&
            (path != "/incident/mark" || command.action == "DISTRACTION")
        return Entry(
            WearQueuedCommand(
                command?.id ?: UUID.nameUUIDFromBytes(item.toByteArray(Charsets.UTF_8)).toString(),
                path, command?.action ?: encoded,
                if (valid) WearCommandStatus.QUEUED else WearCommandStatus.FAILED,
                if (valid) null else "Invalid legacy command; original preferences retained", 0
            ), encoded
        )
    }

    private data class Entry(
        val record: WearQueuedCommand,
        val encoded: String,
        val attempts: Int = 0,
        val nextAttemptAt: Long = 0,
        val updatedAt: Long = 0
    ) {
        val pending get() = record.status == WearCommandStatus.QUEUED || record.status == WearCommandStatus.DELIVERED
    }

    private class Journal(val file: AtomicFile) {
        val records = MutableStateFlow<List<WearQueuedCommand>>(emptyList())
        val inFlight = mutableSetOf<String>()
        var failure: IOException? = null
        fun exists() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    }

    companion object {
        internal const val MAX_ATTEMPTS = 8
        internal const val MAX_APPLIED_RECEIPTS = 20
        internal const val MAX_PENDING_COMMANDS = 128
        internal const val MAX_JOURNAL_BYTES = 512 * 1024
        private val journals = mutableMapOf<String, Journal>()

        private fun backoff(previousAttempts: Int): Long =
            (30_000L * (1L shl previousAttempts.coerceAtMost(5))).coerceAtMost(900_000L)

        private fun requireCapacity(entries: List<Entry>) {
            if (entries.count { it.pending } >= MAX_PENDING_COMMANDS) {
                throw IOException("Command queue is full (128 pending); existing commands were kept")
            }
        }

        private fun readBounded(file: AtomicFile): ByteArray = file.openRead().use { stream ->
            if (stream.channel.size() > MAX_JOURNAL_BYTES) throw IOException("Outbox journal exceeds 512 KiB")
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > MAX_JOURNAL_BYTES) throw IOException("Outbox journal exceeds 512 KiB")
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }

        private fun writeAtomically(file: AtomicFile, bytes: ByteArray) {
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }
}
