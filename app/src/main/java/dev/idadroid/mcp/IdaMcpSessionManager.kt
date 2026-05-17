package dev.idadroid.mcp

import android.content.Context
import android.system.Os
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class IdaMcpSessionManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext
    private val runtime = IdaProotRuntime(appContext, paths = paths)

    private val _state = MutableStateFlow(
        IdaMcpSessionState(
            status = IdaMcpStatus.Stopped,
            message = "未启动",
            settings = IdaMcpLaunchSettings()
        )
    )
    val state: StateFlow<IdaMcpSessionState> = _state.asStateFlow()

    fun updateSettings(settings: IdaMcpLaunchSettings) {
        _state.value = _state.value.copy(settings = settings.sanitized())
    }

    suspend fun start(settings: IdaMcpLaunchSettings = _state.value.settings): Result<IdaMcpSessionState> {
        val launchSettings = settings.sanitized()
        updateSettings(launchSettings)
        if (!startStopMutex.tryLock()) {
            val current = _state.value.copy(
                status = IdaMcpStatus.Starting,
                message = "IDA MCP 正在启动，请稍候…",
                settings = launchSettings
            )
            _state.value = current
            return Result.success(current)
        }
        try {
            val startResult = withContext(Dispatchers.IO) {
                runCatching {
                    require(paths.readyMarker.isFile && paths.rootfsDir.isDirectory) { "rootfs 尚未 ready，请先导入并验证环境" }
                    val binary = File(paths.rootfsDir, "root/ida-pro-9.3/ida-mcp")
                    require(binary.isFile) { "缺少 /root/ida-pro-9.3/ida-mcp" }
                    runCatching { Os.chmod(binary.absolutePath, 493) }
                    materializeLogDir()

                    if (isTcpOpen(launchSettings.port)) {
                        val running = runningState(launchSettings, "IDA MCP HTTP 已在端口 ${launchSettings.port} 运行")
                        _state.value = running
                        return@runCatching running
                    }

                    _state.value = IdaMcpSessionState(
                        status = IdaMcpStatus.Starting,
                        settings = launchSettings,
                        endpoint = launchSettings.endpoint,
                        message = "正在启动 IDA MCP HTTP…",
                        startedAt = System.currentTimeMillis()
                    )

                    activeProcess?.takeIf { it.isAlive }?.destroy()
                    val spec = runtime.workspaceCommandSpec(buildStartCommand(launchSettings))
                    val process = ProcessBuilder(spec.command)
                        .directory(spec.workingDirectory)
                        .also { it.environment().putAll(spec.environment) }
                        .start()
                    activeProcess = process
                    pumpProcessOutput(process, logFile())

                    val ready = waitUntilTcpOpen(launchSettings.port, timeoutMs = 30_000)
                    if (!ready) {
                        val logTail = readLogTail(60).ifBlank { "暂无 ida-mcp-http.log" }
                        val errorState = IdaMcpSessionState(
                            status = IdaMcpStatus.Error,
                            settings = launchSettings,
                            endpoint = launchSettings.endpoint,
                            pid = process.safePid(),
                            message = "IDA MCP 端口 ${launchSettings.port} 在 30 秒内未就绪\n$logTail",
                            startedAt = System.currentTimeMillis()
                        )
                        _state.value = errorState
                        throw IllegalStateException(errorState.message)
                    }

                    val running = runningState(launchSettings, "IDA MCP HTTP 已启动：${launchSettings.endpoint}")
                    _state.value = running
                    running
                }.onFailure { error ->
                    if (_state.value.status == IdaMcpStatus.Starting) {
                        _state.value = _state.value.copy(
                            status = IdaMcpStatus.Error,
                            message = "启动 IDA MCP 失败：${error.message}"
                        )
                    }
                }
            }
            return startResult
        } finally {
            startStopMutex.unlock()
        }
    }

    suspend fun stop(): Result<Unit> {
        startStopMutex.lock()
        try {
            return withContext(Dispatchers.IO) {
                runCatching {
                    val settings = _state.value.settings
                    activeProcess?.let { process ->
                        if (process.isAlive) {
                            process.destroy()
                            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                        }
                    }
                    activeProcess = null
                    val result = runtime.run(buildStopCommand(settings), timeoutMs = 15_000)
                    if (result.exitCode != 0 && !result.timedOut) {
                        throw IllegalStateException(result.stderr.ifBlank { result.stdout }.ifBlank { "停止命令失败" })
                    }
                    _state.value = IdaMcpSessionState(
                        status = IdaMcpStatus.Stopped,
                        settings = settings,
                        endpoint = settings.endpoint,
                        message = "已停止 IDA MCP HTTP"
                    )
                }
            }
        } finally {
            startStopMutex.unlock()
        }
    }

    suspend fun restart(settings: IdaMcpLaunchSettings = _state.value.settings): Result<IdaMcpSessionState> {
        stop()
        return start(settings)
    }

    suspend fun probe(): IdaMcpSessionState = withContext(Dispatchers.IO) {
        val settings = _state.value.settings
        val probed = when {
            isTcpOpen(settings.port) -> runningState(settings, "IDA MCP 端口 ${settings.port} 已就绪")
            activeProcess?.isAlive == true -> IdaMcpSessionState(
                status = IdaMcpStatus.Starting,
                settings = settings,
                endpoint = settings.endpoint,
                pid = activeProcess.safePid(),
                message = "进程仍在运行，等待端口 ${settings.port}",
                startedAt = _state.value.startedAt
            )
            else -> IdaMcpSessionState(
                status = IdaMcpStatus.Stopped,
                settings = settings,
                endpoint = settings.endpoint,
                message = "未启动"
            )
        }
        _state.value = probed
        probed
    }

    fun readLogTail(maxLines: Int = 140): String {
        val file = logFile()
        return if (file.isFile) {
            val lines = runCatching { file.readLines().takeLast(maxLines) }.getOrDefault(emptyList())
            lines.joinToString("\n")
        } else {
            ""
        }
    }

    private fun runningState(settings: IdaMcpLaunchSettings, message: String): IdaMcpSessionState = IdaMcpSessionState(
        status = IdaMcpStatus.Running,
        settings = settings,
        endpoint = settings.endpoint,
        pid = activeProcess.safePid(),
        message = message,
        startedAt = _state.value.startedAt ?: System.currentTimeMillis()
    )

    private fun buildStartCommand(settings: IdaMcpLaunchSettings): String = buildString {
        appendLine("set -e")
        appendLine("cd /root/ida-pro-9.3")
        appendLine("export IDADIR=/root/ida-pro-9.3")
        appendLine("export LD_LIBRARY_PATH=/root/ida-pro-9.3:\${LD_LIBRARY_PATH:-}")
        append("exec /root/ida-pro-9.3/ida-mcp serve-http")
        append(" --bind ").append(IdaProotRuntime.shellQuote(settings.bind))
        append(" --allow-origin ").append(IdaProotRuntime.shellQuote(settings.allowOrigin))
        if (settings.allowHost.isNotBlank()) {
            append(" --allow-host ").append(IdaProotRuntime.shellQuote(settings.allowHost))
        }
        if (settings.stateless) append(" --stateless")
        append(" --session-keep-alive-secs ").append(IdaProotRuntime.shellQuote(settings.sessionKeepAliveSecs.toString()))
        append(" --sse-keep-alive-secs ").append(IdaProotRuntime.shellQuote(settings.sseKeepAliveSecs.toString()))
        appendLine()
    }

    private fun buildStopCommand(settings: IdaMcpLaunchSettings): String = """
        set +e
        pkill -f '/root/ida-pro-9.3/ida-mcp serve-http' 2>/dev/null || true
        pkill -f 'ida-mcp serve-http --bind ${settings.bind}' 2>/dev/null || true
    """.trimIndent()

    private fun isTcpOpen(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 350)
            true
        }
    }.getOrDefault(false)

    private suspend fun waitUntilTcpOpen(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isTcpOpen(port)) return true
            delay(500)
        }
        return false
    }

    private fun pumpProcessOutput(process: Process, file: File) {
        file.parentFile?.mkdirs()
        file.appendText("\n== ${Instant.now()} IDA MCP supervisor started pid=${process.safePid() ?: "unknown"} ==\n")
        Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { file.appendText("[stdout] $it\n") }
            }
        }.apply { name = "idadroid-mcp-stdout"; isDaemon = true; start() }
        Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { file.appendText("[stderr] $it\n") }
            }
        }.apply { name = "idadroid-mcp-stderr"; isDaemon = true; start() }
    }

    private fun materializeLogDir() {
        File(paths.rootfsDir, "root/pi_workspace/.idadroid/logs").mkdirs()
        paths.logsDir.mkdirs()
    }

    private fun logFile(): File = File(paths.logsDir, "ida-mcp-http.log")

    private fun Process?.safePid(): Int? = null

    companion object {
        private val startStopMutex = Mutex()

        @Volatile
        private var activeProcess: Process? = null
    }
}

enum class IdaMcpStatus { Stopped, Starting, Running, Error }

data class IdaMcpLaunchSettings(
    val bindHost: String = "127.0.0.1",
    val port: Int = 8765,
    val allowOrigin: String = "http://localhost,http://127.0.0.1",
    val allowHost: String = "",
    val stateless: Boolean = false,
    val sessionKeepAliveSecs: Int = 1800,
    val sseKeepAliveSecs: Int = 15
) {
    val bind: String get() = "${bindHost.ifBlank { "127.0.0.1" }}:${port.coerceIn(1, 65535)}"
    val endpoint: String get() = "http://$bind"

    fun sanitized(): IdaMcpLaunchSettings = copy(
        bindHost = sanitizeHost(bindHost),
        port = port.coerceIn(1, 65535),
        allowOrigin = allowOrigin.replace('\n', ',').replace('\r', ',').trim().ifBlank { "http://localhost,http://127.0.0.1" },
        allowHost = allowHost.replace('\n', ',').replace('\r', ',').trim(),
        sessionKeepAliveSecs = sessionKeepAliveSecs.coerceIn(0, 86_400),
        sseKeepAliveSecs = sseKeepAliveSecs.coerceIn(0, 300)
    )

    private fun sanitizeHost(value: String): String {
        val trimmed = value.trim().ifBlank { "127.0.0.1" }
        return if (Regex("^[A-Za-z0-9_.:-]+$").matches(trimmed)) trimmed else "127.0.0.1"
    }
}

data class IdaMcpSessionState(
    val status: IdaMcpStatus,
    val settings: IdaMcpLaunchSettings = IdaMcpLaunchSettings(),
    val endpoint: String = settings.endpoint,
    val pid: Int? = null,
    val message: String = "",
    val startedAt: Long? = null
)
