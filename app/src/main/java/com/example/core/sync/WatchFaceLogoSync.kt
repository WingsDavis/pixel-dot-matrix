package com.example.core.sync

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Base64
import com.example.core.media.LogoTransform
import com.example.core.media.WatchFaceLogoDocument
import com.example.core.media.WatchFaceLogoRenderer
import com.example.core.media.WatchFaceLogoStore
import com.google.android.gms.wearable.DataMap
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONException

enum class LogoSyncPhase { IDLE, LOCAL, QUEUED, APPLIED, FAILED }

data class LogoUiState(
    val document: WatchFaceLogoDocument? = null,
    val phase: LogoSyncPhase = LogoSyncPhase.IDLE,
    val busy: Boolean = false,
    val message: String = "No watch confirmation",
    val error: String? = null,
    val canRetry: Boolean = false
)

internal data class LogoOperation(
    val id: String,
    val timestamp: Long,
    val png: ByteArray?,
    val reset: Boolean,
    val digest: String,
    val status: String = "QUEUED",
    val attempt: Int = 0,
    val error: String? = null,
    val cleanupPending: Boolean = false
)

internal class WatchFaceLogoSync(
    context: Context,
    private val scope: CoroutineScope,
    private val publish: suspend (LogoOperation) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val store: WatchFaceLogoStore = WatchFaceLogoStore(context),
    private val operationFile: AtomicFile = AtomicFile(File(context.filesDir, "watch_face_logo_sync.json")),
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime
) {
    private val queueLock = Any()
    private var tail: Deferred<Unit>? = null
    private val sendLock = Mutex()
    private var operation: LogoOperation? = null
    private var loadError: String? = null
    private var lastAttempt = Long.MIN_VALUE
    private val _state = MutableStateFlow(LogoUiState())
    val state = _state.asStateFlow()

    init {
        mutate {
            try { operation = readOperation() }
            catch (error: Exception) { loadError = message(error); throw error }
            if (operation?.cleanupPending == true) {
                updateState(null)
                finishCleanup()
            }
            try { updateState(store.load()) }
            catch (error: Exception) { loadError = message(error); throw error }
        }
    }

    fun select(uri: Uri): Job = mutate {
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            operation?.takeIf { it.cleanupPending }?.let { current ->
                val next = current.copy(cleanupPending = false)
                writeOperation(next)
                operation = next
            }
            val document = store.importImage(uri)
            loadError = null
            updateState(document)
        } finally {
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun edit(update: (LogoTransform) -> LogoTransform): Job = mutate {
        val current = _state.value.document ?: error("Choose an image before changing framing")
        updateState(store.update(update(current.transform)))
    }

    fun apply(): Job {
        val prepared = mutate {
            val document = _state.value.document ?: error("Choose a logo first")
            val bytes = WatchFaceLogoRenderer.png(document.rendered)
            require(bytes.size <= WatchFaceLogoProtocol.MAX_PNG_BYTES) { "Rendered logo is too large" }
            val next = newOperation(bytes, false)
            writeOperation(next)
            operation = next
            updateState(document)
            next.id
        }
        return publishPrepared(prepared)
    }

    fun reset(): Deferred<Unit> {
        val prepared = mutate {
            val next = newOperation(null, true).copy(cleanupPending = true)
            writeOperation(next)
            operation = next
            loadError = null
            updateState(null)
            finishCleanup()
            next.id
        }
        return publishPrepared(prepared)
    }

    suspend fun resetAndQueue() = reset().await()

    fun retry(): Job {
        val prepared = mutate {
            finishCleanup()
            val current = operation ?: return@mutate null
            val next = current.copy(status = "QUEUED", attempt = 0, error = null)
            writeOperation(next)
            operation = next
            updateState(_state.value.document)
            next.id
        }
        return publishPrepared(prepared)
    }

    suspend fun retryPending() {
        val prepared = mutate {
            finishCleanup()
            val current = operation ?: return@mutate null
            if (current.status == "APPLIED") return@mutate null
            if (lastAttempt != Long.MIN_VALUE && elapsed() - lastAttempt < 15_000L) return@mutate null
            if (current.attempt >= 5) {
                val message = "Watch has not confirmed the logo. Retry when connected."
                if (current.status != "FAILED" || current.error != message) {
                    val failed = current.copy(status = "FAILED", error = message)
                    writeOperation(failed)
                    operation = failed
                }
                updateState(_state.value.document, message)
                return@mutate null
            }
            current.id
        }
        publishPrepared(prepared).await()
    }

    fun acknowledge(data: DataMap): Job = mutate {
        if (data.getInt("schema_version", 0) != WatchFaceLogoProtocol.SCHEMA_VERSION) return@mutate
        val current = operation ?: return@mutate
        if (current.cleanupPending || data.getString("revision") != current.id) return@mutate
        if (data.getBoolean("reset_logo", false) != current.reset || data.getString("sha256").orEmpty() != current.digest) return@mutate
        val success = data.getBoolean("success", false)
        if (!success && current.status == "APPLIED") return@mutate
        val next = current.copy(status = if (success) "APPLIED" else "FAILED",
            error = if (success) null else data.getString("status_message")?.take(240) ?: "Watch could not store the logo")
        writeOperation(next)
        operation = next
        updateState(_state.value.document)
    }

    private fun finishCleanup() {
        val current = operation?.takeIf { it.cleanupPending } ?: return
        store.clear()
        val next = current.copy(cleanupPending = false)
        writeOperation(next)
        operation = next
        loadError = null
        updateState(null)
    }

    private fun <T> mutate(block: () -> T): Deferred<T> = synchronized(queueLock) {
        val previous = tail
        val finished = CompletableDeferred<Unit>()
        val result = CompletableDeferred<T>()
        // Reserve before dispatch, including across cancellation of an action waiting for its predecessor.
        tail = finished
        val worker = scope.launch(dispatcher) {
            try {
                previous?.join()
                ensureActive()
                result.complete(block())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(error)
                result.completeExceptionally(error)
            }
        }
        worker.invokeOnCompletion { cause ->
            if (cause != null) result.completeExceptionally(cause)
            if (previous == null) finished.complete(Unit)
            else previous.invokeOnCompletion { finished.complete(Unit) }
        }
        result.invokeOnCompletion { if (result.isCancelled) worker.cancel() }
        result
    }

    private fun publishPrepared(prepared: Deferred<String?>): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        val worker = scope.launch(dispatcher) {
            try {
                prepared.await()?.let { send(it) }
                result.complete(Unit)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                result.completeExceptionally(error)
            }
        }
        worker.invokeOnCompletion { cause -> if (cause != null) result.completeExceptionally(cause) }
        result.invokeOnCompletion { if (result.isCancelled) worker.cancel() }
        return result
    }

    private suspend fun send(id: String) = sendLock.withLock send@ {
        val sending = mutate {
            if (operation?.id != id || operation?.status == "APPLIED") return@mutate null
            finishCleanup()
            val current = checkNotNull(operation)
            val next = current.copy(attempt = current.attempt + 1, status = "QUEUED", error = null)
            writeOperation(next)
            operation = next
            lastAttempt = elapsed()
            updateState(_state.value.document)
            next
        }.await() ?: return@send
        try {
            check(withTimeoutOrNull(10_000L) { publish(sending); true } == true) { "Logo transfer timed out; retry when connected" }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutate {
                val current = operation
                if (current?.id == id && current.status != "APPLIED") {
                    val failed = current.copy(status = "FAILED", error = message(error))
                    writeOperation(failed)
                    operation = failed
                    updateState(_state.value.document, failed.error)
                }
            }.await()
        }
    }

    private fun newOperation(bytes: ByteArray?, reset: Boolean): LogoOperation {
        val floor = operation?.timestamp ?: 0L
        check(floor < Long.MAX_VALUE) { "Logo ordering state is invalid" }
        return LogoOperation(UUID.randomUUID().toString(), maxOf(now(), floor + 1), bytes, reset,
            bytes?.let(WatchFaceLogoProtocol::sha256).orEmpty())
    }

    private fun updateState(document: WatchFaceLogoDocument?, failure: String? = null) {
        val current = operation
        val digest = document?.rendered?.let(WatchFaceLogoRenderer::png)?.let(WatchFaceLogoProtocol::sha256)
        val local = document != null && (current == null || current.reset || digest != current.digest)
        val problem = loadError ?: failure.takeUnless { local }
        val phase = when {
            problem != null -> LogoSyncPhase.FAILED
            local -> LogoSyncPhase.LOCAL
            current == null -> LogoSyncPhase.IDLE
            current.status == "APPLIED" -> LogoSyncPhase.APPLIED
            current.status == "FAILED" -> LogoSyncPhase.FAILED
            else -> LogoSyncPhase.QUEUED
        }
        _state.value = LogoUiState(document, phase, message = when (phase) {
            LogoSyncPhase.IDLE -> "No watch confirmation"
            LogoSyncPhase.LOCAL -> "Local logo changes not applied"
            LogoSyncPhase.QUEUED -> if (current?.reset == true) "Default logo waiting for watch" else "Logo waiting for watch"
            LogoSyncPhase.APPLIED -> if (current?.reset == true) "Custom artwork removed on watch" else "Logo stored on watch"
            LogoSyncPhase.FAILED -> problem ?: current?.error ?: "Logo change needs retry"
        }, error = problem ?: current?.error.takeUnless { local },
            canRetry = !local && current != null && current.status != "APPLIED" && loadError == null)
    }

    private fun fail(error: Throwable) {
        if (error is CancellationException) throw error
        _state.value = _state.value.copy(phase = LogoSyncPhase.FAILED,
            message = message(error), error = message(error))
    }

    private fun message(error: Throwable): String =
        if (generateSequence(error) { it.cause }.take(8).any { it is JSONException })
            "Saved logo data is damaged. Reset the logo or choose another image."
        else error.message?.lineSequence()?.firstOrNull()?.take(240) ?: "Logo operation failed"

    private fun readOperation(): LogoOperation? {
        if (!operationFile.baseFile.exists() && !File(operationFile.baseFile.path + ".bak").exists()) return null
        val data = JSONObject(readOperationBytes().toString(Charsets.UTF_8))
        require(data.getInt("schema") == 1)
        val id = data.getString("id")
        require(WatchFaceLogoProtocol.validRevision(id))
        val reset = data.getBoolean("reset")
        val bytes = if (reset) null else Base64.decode(data.getString("png"), Base64.DEFAULT)
        require(bytes == null || bytes.size <= WatchFaceLogoProtocol.MAX_PNG_BYTES)
        val digest = bytes?.let(WatchFaceLogoProtocol::sha256).orEmpty()
        require(data.getString("sha256") == digest)
        val timestamp = data.getLong("timestamp")
        require(timestamp > 0)
        val status = data.getString("status")
        require(status in setOf("QUEUED", "APPLIED", "FAILED"))
        val cleanupPending = data.optBoolean("cleanup_pending", false)
        require(!cleanupPending || reset) { "Only a logo reset can have pending local cleanup" }
        return LogoOperation(id, timestamp, bytes, reset, digest, status, data.optInt("attempt", 0).coerceIn(0, 5),
            data.optString("error").takeIf { it.isNotEmpty() }, cleanupPending)
    }

    private fun readOperationBytes(): ByteArray = operationFile.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 512 * 1024) { "Logo sync state is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
    }

    private fun writeOperation(value: LogoOperation) {
        val json = JSONObject().apply {
            put("schema", 1); put("id", value.id); put("timestamp", value.timestamp); put("reset", value.reset)
            put("png", value.png?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty())
            put("sha256", value.digest); put("status", value.status); put("attempt", value.attempt); put("error", value.error.orEmpty())
            put("cleanup_pending", value.cleanupPending)
        }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= 512 * 1024) { "Logo sync state is too large" }
        val stream = operationFile.startWrite()
        try { stream.write(json); stream.fd.sync(); operationFile.finishWrite(stream) }
        catch (error: Exception) { operationFile.failWrite(stream); throw error }
        if (!readOperationBytes().contentEquals(json)) throw IOException("Logo sync state could not be committed")
    }
}
