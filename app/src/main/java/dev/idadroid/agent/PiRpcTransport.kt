package dev.idadroid.agent

import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class PiRpcCommandException(message: String) : IOException(message)

/** One process, one session generation. Never mixes diagnostic stderr with JSONL stdout. */
class PiRpcTransport(
    private val process: Process,
    private val onEvent: (JsonObject) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onStderr: (String) -> Unit = {}
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val closed = AtomicBoolean(false)
    private val writeMutex = Mutex()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private data class Pending(val command: String, val result: CompletableDeferred<JsonObject>)

    init {
        val stdout = scope.launch {
            try {
                // BufferedReader splits CR/LF, never Unicode U+2028/U+2029 inside JSON strings.
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val record = try { Json.parseToJsonElement(line) as? JsonObject }
                            catch (_: Exception) { null }
                            ?: throw IOException("PI emitted malformed JSONL (record hidden for privacy)")
                        when (record["type"]?.jsonPrimitive?.contentOrNull) {
                            "response" -> {
                                val id = record["id"]?.jsonPrimitive?.contentOrNull
                                val request = id?.let(pending::get)
                                if (request == null) {
                                    if (id == null) throw IOException("PI returned an uncorrelated protocol response")
                                    continue // A cancelled caller may have removed its request.
                                }
                                if (record["command"]?.jsonPrimitive?.contentOrNull != request.command) {
                                    val error = IOException("PI response command does not match request")
                                    request.result.completeExceptionally(error)
                                    throw error
                                }
                                if (record["success"]?.jsonPrimitive?.booleanOrNull == true) {
                                    request.result.complete(record["data"] as? JsonObject ?: JsonObject(emptyMap()))
                                } else {
                                    request.result.completeExceptionally(PiRpcCommandException(
                                        record["error"]?.jsonPrimitive?.contentOrNull ?: "PI command failed"
                                    ))
                                }
                                pending.remove(id, request)
                            }
                            null -> throw IOException("PI protocol record has no type")
                            else -> onEvent(record)
                        }
                    }
                }
                fail(IOException("PI process closed stdout"))
            } catch (error: Exception) { fail(error) }
        }
        scope.launch {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val chars = CharArray(2048)
                    while (true) {
                        val count = reader.read(chars)
                        if (count < 0) break
                        onStderr(String(chars, 0, count))
                    }
                }
            } catch (error: Exception) { if (!closed.get()) fail(error) }
        }
        scope.launch {
            try {
                val code = process.waitFor()
                // Drain final records before reporting exit; descendants may keep a pipe open.
                withTimeoutOrNull(2_000) { stdout.join() }
                fail(IOException("PI exited (code $code)"))
            }
            catch (error: Exception) { fail(error) }
        }
    }

    suspend fun request(type: String, fields: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 30_000): JsonObject {
        check(!closed.get()) { "PI transport is closed" }
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = Pending(type, result)
        if (closed.get()) pending.remove(id)?.result?.completeExceptionally(IOException("PI transport is closed"))
        val record = JsonObject(fields + mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type)))
        // The blocking pipe write belongs to the transport, not the caller's timeout scope.
        // Killing the process on timeout unblocks both its readers and its writer.
        val write = scope.launch { try { writeRecord(record) } catch (e: Exception) { fail(e) } }
        try {
            return withTimeout(timeoutMs) { result.await() }
        } catch (error: CancellationException) {
            fail(IOException(if (error is TimeoutCancellationException) "PI $type response timed out" else "PI request cancelled"))
            throw error
        } finally {
            pending.remove(id)
            write.cancel()
        }
    }

    suspend fun cancelExtensionDialog(id: String) {
        val record = buildJsonObject {
            put("type", "extension_ui_response"); put("id", id); put("cancelled", true)
        }
        val done = CompletableDeferred<Unit>()
        scope.launch {
            try { writeRecord(record); done.complete(Unit) }
            catch (e: Exception) { done.completeExceptionally(e); fail(e) }
        }
        try { withTimeout(10_000) { done.await() } }
        catch (e: Exception) { fail(IOException("PI extension response could not be delivered")); throw e }
    }

    private suspend fun writeRecord(record: JsonObject) = writeMutex.withLock {
        check(!closed.get()) { "PI transport is closed" }
        writer.write(record.toString()); writer.newLine(); writer.flush()
    }

    private fun fail(error: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.result.completeExceptionally(error) }
        pending.clear()
        terminate()
        onFailure(error)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { it.result.completeExceptionally(IOException("PI transport closed")) }
        pending.clear()
        terminate()
    }

    private fun terminate() {
        // Do not close the buffered writer synchronously: its monitor can be held by a blocked write.
        process.destroy()
        scope.launch {
            try {
                if (!process.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } finally {
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                scope.cancel()
            }
        }
    }
}
