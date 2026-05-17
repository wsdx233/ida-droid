package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PiRpcRuntime(
    context: Context,
    private val session: AgentSessionRecord,
    private val events: MutableSharedFlow<PiRuntimeEvent>,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context),
    private val proot: IdaProotRuntime = IdaProotRuntime(context, paths = paths)
) {
    private val configManager = PiConfigManager(paths)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val seq = AtomicLong(0)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
    private val writeLock = Any()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var stopped = false
    @Volatile private var recentStderr = ""

    fun isActive(): Boolean = process?.isAlive == true && !stopped

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isActive()) return@withContext
        stopped = false
        recentStderr = ""
        emit(PiRuntimeEvent.Status("starting"))

        val command = buildStartScript(session)
        val spec = proot.workspaceCommandSpec(command)
        val started = ProcessBuilder(spec.command)
            .directory(spec.workingDirectory)
            .also { it.environment().putAll(spec.environment) }
            .start()
        process = started
        writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))

        pumpStdout(started)
        pumpStderr(started)
        waitForExit(started)

        emit(PiRuntimeEvent.Status("running"))
        runCatching { getState(timeoutMs = 30_000) }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        stopped = true
        pending.forEach { (_, deferred) -> deferred.completeExceptionally(IllegalStateException("agent stopped")) }
        pending.clear()
        runCatching { writer?.close() }
        writer = null
        process?.let { proc ->
            if (proc.isAlive) {
                proc.destroy()
                if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
            }
        }
        process = null
        emit(PiRuntimeEvent.Status("stopped"))
    }

    suspend fun prompt(message: String, images: List<ImagePayload> = emptyList(), streamingBehavior: String? = null): JsonElement {
        val payload = buildJsonObject {
            put("type", "prompt")
            put("message", message)
            streamingBehavior?.takeIf { it.isNotBlank() }?.let { put("streamingBehavior", it) }
            if (images.isNotEmpty()) {
                put("images", kotlinx.serialization.json.JsonArray(images.map { image ->
                    buildJsonObject {
                        put("type", image.type)
                        put("data", image.data)
                        put("mimeType", image.mimeType)
                    }
                }))
            }
        }
        return send(payload, timeoutMs = 120_000)
    }

    suspend fun steer(message: String): JsonElement = send(buildJsonObject { put("type", "steer"); put("message", message) }, timeoutMs = 120_000)
    suspend fun followUp(message: String): JsonElement = send(buildJsonObject { put("type", "follow_up"); put("message", message) }, timeoutMs = 120_000)
    suspend fun abort(): JsonElement = send(buildJsonObject { put("type", "abort") }, timeoutMs = 5_000)
    suspend fun getState(timeoutMs: Long = 120_000): JsonElement = send(buildJsonObject { put("type", "get_state") }, timeoutMs = timeoutMs)
    suspend fun getMessages(): JsonElement = send(buildJsonObject { put("type", "get_messages") }, timeoutMs = 120_000)
    suspend fun getStats(): JsonElement = send(buildJsonObject { put("type", "get_session_stats") }, timeoutMs = 30_000)
    suspend fun availableModels(): JsonElement = send(buildJsonObject { put("type", "get_available_models") }, timeoutMs = 30_000)
    suspend fun setModel(provider: String, modelId: String): JsonElement = send(buildJsonObject { put("type", "set_model"); put("provider", provider); put("modelId", modelId) }, timeoutMs = 30_000)
    suspend fun setThinkingLevel(level: String): JsonElement = send(buildJsonObject { put("type", "set_thinking_level"); put("level", level) }, timeoutMs = 30_000)
    suspend fun setAutoCompaction(enabled: Boolean): JsonElement = send(buildJsonObject { put("type", "set_auto_compaction"); put("enabled", enabled) }, timeoutMs = 30_000)
    suspend fun compact(customInstructions: String? = null): JsonElement = send(buildJsonObject { put("type", "compact"); customInstructions?.takeIf { it.isNotBlank() }?.let { put("customInstructions", it) } }, timeoutMs = 600_000)

    suspend fun send(command: JsonObject, timeoutMs: Long = 120_000): JsonElement {
        if (!isActive()) start()
        val id = "req_${seq.incrementAndGet()}"
        val payload = JsonObject(linkedMapOf("id" to JsonPrimitive(id)) + command)
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        synchronized(writeLock) {
            val out = writer ?: throw IllegalStateException("agent runtime is not running")
            out.write(payload.toString())
            out.write("\n")
            out.flush()
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (deferred.isCompleted) return deferred.await()
            delay(50)
        }
        pending.remove(id)
        deferred.completeExceptionally(IllegalStateException("RPC command timed out: ${command["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"}"))
        return deferred.await()
    }

    fun stderrTail(): String = recentStderr

    private fun buildStartScript(session: AgentSessionRecord): String = buildString {
        appendLine("cd /root/pi_workspace")
        appendLine("export PI_CODING_AGENT_DIR=/root/pi_workspace/.idadroid/pi-agent")
        appendLine("export PI_SKIP_VERSION_CHECK=1")
        appendLine("export PI_TELEMETRY=0")
        appendLine("export TERM=dumb")
        appendLine(configManager.runtimeEnvExports())
        appendLine("export NODE_OPTIONS=\"\${NODE_OPTIONS:-} --require /root/pi_workspace/.idadroid/pi-agent/rpc-stdio-guard.cjs\"")
        append("exec pi --mode rpc ")
        val sessionFile = session.sessionFile?.trim().orEmpty()
        if (sessionFile.isNotBlank()) {
            append("--session ").append(IdaProotRuntime.shellQuote(sessionFile)).append(' ')
        } else {
            append("--session-dir /root/pi_workspace/.pi-sessions ")
        }
        session.provider?.takeIf { it.isNotBlank() }?.let { append("--provider ").append(IdaProotRuntime.shellQuote(it)).append(' ') }
        session.model?.takeIf { it.isNotBlank() }?.let { append("--model ").append(IdaProotRuntime.shellQuote(it)).append(' ') }
        session.thinkingLevel?.takeIf { it.isNotBlank() }?.let { append("--thinking ").append(IdaProotRuntime.shellQuote(it)).append(' ') }
        configManager.extraArgs().forEach { append(IdaProotRuntime.shellQuote(it)).append(' ') }
    }

    private fun pumpStdout(proc: Process) {
        scope.launch {
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (stopped) return@forEach
                        if (line.isBlank()) return@forEach
                        handleStdoutLine(line.trimEnd('\r'))
                    }
                }
            } catch (e: IOException) {
                if (!stopped) emit(PiRuntimeEvent.RawStdout("stdout closed: ${e.message}"))
            }
        }
    }

    private fun pumpStderr(proc: Process) {
        scope.launch {
            try {
                proc.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (stopped) return@forEach
                        val text = line + "\n"
                        recentStderr = tailText(recentStderr + text, 12_000)
                        emit(PiRuntimeEvent.Stderr(text))
                    }
                }
            } catch (e: IOException) {
                if (!stopped) emit(PiRuntimeEvent.Stderr("stderr closed: ${e.message}\n"))
            }
        }
    }

    private fun waitForExit(proc: Process) {
        scope.launch {
            val code = runCatching { proc.waitFor() }.getOrNull()
            if (stopped) return@launch
            stopped = true
            pending.forEach { (_, deferred) -> deferred.completeExceptionally(IllegalStateException("agent process exited")) }
            pending.clear()
            val stderr = recentStderr.trim().takeIf { it.isNotBlank() }
            emit(PiRuntimeEvent.Exited(code, stderr?.let { "agent process exited\nstderr:\n$it" } ?: "agent process exited"))
            emit(PiRuntimeEvent.Status("error", stderr ?: "agent process exited"))
        }
    }

    private suspend fun handleStdoutLine(line: String) {
        val message = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        if (message == null) {
            emit(PiRuntimeEvent.RawStdout(line))
            return
        }
        val type = message["type"]?.jsonPrimitive?.contentOrNull
        val id = message["id"]?.jsonPrimitive?.contentOrNull
        if (type == "response" && id != null && pending.containsKey(id)) {
            val deferred = pending.remove(id) ?: return
            val success = message["success"]?.jsonPrimitive?.booleanOrNull ?: true
            if (success) deferred.complete(message["data"] ?: message["result"] ?: JsonNull)
            else deferred.completeExceptionally(IllegalStateException(message["error"]?.jsonPrimitive?.contentOrNull ?: "RPC command failed"))
            return
        }
        emit(PiRuntimeEvent.RpcEvent(message))
    }

    private suspend fun emit(event: PiRuntimeEvent) {
        events.emit(event)
    }

    private fun tailText(text: String, maxChars: Int): String = if (text.length <= maxChars) text else text.takeLast(maxChars)
}
