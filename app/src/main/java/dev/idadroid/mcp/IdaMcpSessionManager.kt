package dev.idadroid.mcp

import android.content.Context
import android.system.Os
import android.util.Log
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import dev.idadroid.util.safePid
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class IdaMcpSessionManager private constructor(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context),
    private val settingsStore: dev.idadroid.settings.IdaDroidSettings? = null
) {
    private val appContext = context.applicationContext
    private val runtime = IdaProotRuntime(appContext, paths = paths)
    private val fileTransferManager = FileTransferManager(appContext, paths)
    private val fileTransferServer = FileTransferHttpServer(
        manager = fileTransferManager,
        idaMcpEndpointProvider = { currentIdaMcpEndpoint() }
    )
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var watchdogJob: Job? = null
    @Volatile private var monitoringEnabled: Boolean = settingsStore?.mcpSettings?.value?.autoRestart ?: true
    private val autoRestartCount = AtomicInteger(0)
    @Volatile private var lastHealthCheckAt: Long = 0L
    @Volatile private var lastHealthOk: Boolean = false

    /** Exposed so the UI can trigger host→container file transfers. */
    val transfers: FileTransferManager get() = fileTransferManager

    /**
     * Returns the ida-mcp HTTP endpoint when the server is running, or null when
     * it is stopped. Used by [FileTransferHttpServer]'s `/api/open-in-ida` route
     * so external tools can invoke `open_file` without knowing whether IDA MCP
     * is currently up.
     */
    private fun currentIdaMcpEndpoint(): String? {
        val state = _state.value
        return if (state.status == IdaMcpStatus.Running) state.endpoint else null
    }

    /**
     * One-shot convenience for the UI: search the host for [name] (or use
     * [hostPath] directly if provided), transfer the file into the container,
     * then call ida-mcp's `open_file` tool so IDA Pro opens it immediately.
     *
     * Returns a [Result] whose value is the ida-mcp tool's text response on
     * success, or an exception describing which step failed.
     */
    suspend fun openInIda(name: String? = null, hostPath: String? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(!name.isNullOrBlank() || !hostPath.isNullOrBlank()) {
                "必须提供 name 或 hostPath"
            }
            val endpoint = currentIdaMcpEndpoint()
                ?: error("IDA MCP 未运行，请先启动 IDA MCP HTTP 服务")
            val entry = when {
                !hostPath.isNullOrBlank() -> fileTransferManager.transferHostPath(hostPath)
                else -> {
                    val n = name ?: error("文件名为空")
                    fileTransferManager.findAndTransferByName(n)
                        ?: error("主机端未找到文件：$n")
                }
            }
            IdaMcpClient(endpoint).openFile(entry.prootPath)
        }.let { result ->
            // runCatching 会吞掉 CancellationException，导致协程取消信号丢失。
            // 检测到 CancellationException 时重新抛出，让父协程能正确取消。
            val cause = result.exceptionOrNull()
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            result
        }
    }

    private val _state = MutableStateFlow(
        IdaMcpSessionState(
            status = IdaMcpStatus.Stopped,
            message = "未启动",
            settings = currentLaunchSettings()
        )
    )
    val state: StateFlow<IdaMcpSessionState> = _state.asStateFlow()

    fun updateSettings(settings: IdaMcpLaunchSettings) {
        _state.update { it.copy(settings = settings.sanitized()) }
    }

    /** Build launch settings from the central IdaDroidSettings store (if available). */
    private fun currentLaunchSettings(): IdaMcpLaunchSettings {
        val mcp = settingsStore?.mcpSettings?.value ?: return IdaMcpLaunchSettings()
        return IdaMcpLaunchSettings(
            bindHost = mcp.bindHost,
            port = mcp.port,
            allowOrigin = mcp.allowOrigin,
            stateless = mcp.stateless,
            sessionKeepAliveSecs = mcp.sessionKeepAliveSecs,
            sseKeepAliveSecs = mcp.sseKeepAliveSecs
        )
    }

    /** Refresh state from settings — call after the user changes MCP settings. */
    fun refreshFromSettings() {
        val refreshed = currentLaunchSettings()
        _state.update { it.copy(settings = refreshed.sanitized()) }
        monitoringEnabled = settingsStore?.mcpSettings?.value?.autoRestart ?: true
    }

    suspend fun start(settings: IdaMcpLaunchSettings = _state.value.settings): Result<IdaMcpSessionState> {
        val launchSettings = settings.sanitized()
        updateSettings(launchSettings)
        if (!startStopMutex.tryLock()) {
            // Another start/stop is already in progress; reflect that in state and fail.
            val current = _state.value.let {
                if (it.status == IdaMcpStatus.Starting || it.status == IdaMcpStatus.Running) it
                else it.copy(status = IdaMcpStatus.Starting, message = "IDA MCP 正在启动，请稍候…", settings = launchSettings)
            }
            _state.update { current }
            return Result.failure(IllegalStateException("IDA MCP 正在启动或停止，请稍候…"))
        }
        try {
            val startResult = withContext(Dispatchers.IO) {
                runCatching {
                    require(paths.readyMarker.isFile && paths.rootfsDir.isDirectory) { "rootfs 尚未 ready，请先导入并验证环境" }
                    val idaHome = settingsStore?.envSettings?.value?.idaHome ?: dev.idadroid.settings.IdaDroidSettings.DEFAULT_IDA_HOME
                    val idaHomeGuest = idaHome.trimEnd('/')
                    val idaHomeHost = idaHomeGuest.trimStart('/')
                    val binary = File(paths.rootfsDir, "$idaHomeHost/ida-mcp")
                    require(binary.isFile) { "缺少 $idaHomeGuest/ida-mcp" }
                    runCatching { Os.chmod(binary.absolutePath, 493) }
                    materializeLogDir()

                    if (isTcpOpen(launchSettings.port, launchSettings.bindHost)) {
                        // Port is open. If it is our own live process bound to
                        // the same endpoint we are already running; otherwise a
                        // stale process from a previous session holds the port,
                        // so clean it up and start fresh instead of reporting a
                        // phantom "running".
                        if (activeProcess?.isAlive == true && activeProcessBind == launchSettings.bind) {
                            val running = runningState(launchSettings, "IDA MCP HTTP 已在端口 ${launchSettings.port} 运行")
                            _state.update { running }
                            return@runCatching running
                        }
                        runCatching { runtime.run(buildStopCommand(launchSettings), timeoutMs = 15_000) }
                        if (!waitUntilPortClosed(launchSettings.port, timeoutMs = 10_000, bindHost = launchSettings.bindHost)) {
                            // The port is still held by something we cannot stop
                            // (e.g. a non-MCP process). Starting a replacement
                            // would bind-fail while waitUntilTcpOpen would
                            // falsely report success against the foreign port.
                            val errorState = IdaMcpSessionState(
                                status = IdaMcpStatus.Error,
                                settings = launchSettings,
                                endpoint = launchSettings.endpoint,
                                message = "IDA MCP 端口 ${launchSettings.port} 被其它进程占用且无法清理，请先释放端口",
                                startedAt = System.currentTimeMillis()
                            )
                            _state.update { errorState }
                            throw IllegalStateException(errorState.message)
                        }
                    }

                    _state.update { IdaMcpSessionState(
                        status = IdaMcpStatus.Starting,
                        settings = launchSettings,
                        endpoint = launchSettings.endpoint,
                        message = "正在启动 IDA MCP HTTP…",
                        startedAt = System.currentTimeMillis()
                    ) }

                    activeProcess?.takeIf { it.isAlive }?.destroy()
                    val spec = runtime.workspaceCommandSpec(buildStartCommand(launchSettings))
                    val process = ProcessBuilder(spec.command)
                        .directory(spec.workingDirectory)
                        .also { it.environment().putAll(spec.environment) }
                        .start()
                    activeProcess = process
                    activeProcessBind = launchSettings.bind
                    pumpProcessOutput(process, logFile())

                    val ready = waitUntilMcpReady(process, launchSettings.port, launchSettings.bindHost, timeoutMs = 30_000)
                    if (!ready) {
                        // Tear down the half-started process so it cannot keep
                        // holding the port or linger as a zombie.
                        runCatching { process.destroy() }
                        if (!process.waitFor(2, TimeUnit.SECONDS)) runCatching { process.destroyForcibly() }
                        activeProcess = null
                        activeProcessBind = null
                        val logTail = readLogTail(60).ifBlank { "暂无 ida-mcp-http.log" }
                        val errorState = IdaMcpSessionState(
                            status = IdaMcpStatus.Error,
                            settings = launchSettings,
                            endpoint = launchSettings.endpoint,
                            pid = process.safePid(),
                            message = "IDA MCP 端口 ${launchSettings.port} 在 30 秒内未就绪\n$logTail",
                            startedAt = System.currentTimeMillis()
                        )
                        _state.update { errorState }
                        throw IllegalStateException(errorState.message)
                    }

                    val running = runningState(launchSettings, "IDA MCP HTTP 已启动：${launchSettings.endpoint}")
                    _state.update { running }
                    // Start the file-transfer HTTP bridge so the agent inside the
                    // container can discover and open host-transferred files.
                    runCatching { fileTransferServer.start() }
                        .onFailure { Log.w("IdaMcpSessionManager", "file transfer server failed to start", it) }
                    // Start the health-monitoring watchdog so the service
                    // auto-recovers if Android kills the process or the port
                    // stops responding.
                    startWatchdog(launchSettings)
                    running
                }.onFailure { error ->
                    if (_state.value.status == IdaMcpStatus.Starting) {
                        _state.update { it.copy(
                            status = IdaMcpStatus.Error,
                            message = "启动 IDA MCP 失败：${error.message}"
                        ) }
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
                    stopWatchdog()
                    fileTransferServer.stop()
                    val settings = _state.value.settings
                    activeProcess?.let { process ->
                        if (process.isAlive) {
                            process.destroy()
                            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                        }
                    }
                    activeProcess = null
                    activeProcessBind = null
                    val result = runtime.run(buildStopCommand(settings), timeoutMs = 15_000)
                    if (result.exitCode != 0 && !result.timedOut) {
                        throw IllegalStateException(result.stderr.ifBlank { result.stdout }.ifBlank { "停止命令失败" })
                    }
                    _state.update { IdaMcpSessionState(
                        status = IdaMcpStatus.Stopped,
                        settings = settings,
                        endpoint = settings.endpoint,
                        message = "已停止 IDA MCP HTTP"
                    ) }
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
            isTcpOpen(settings.port, settings.bindHost) -> runningState(settings, "IDA MCP 端口 ${settings.port} 已就绪")
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
        _state.update { probed }
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
        startedAt = _state.value.startedAt ?: System.currentTimeMillis(),
        monitoringEnabled = monitoringEnabled,
        lastHealthCheckAt = if (lastHealthCheckAt > 0) lastHealthCheckAt else null,
        lastHealthOk = lastHealthOk,
        autoRestartCount = autoRestartCount.get()
    )

    private fun buildStartCommand(settings: IdaMcpLaunchSettings): String = buildString {
        val idaHome = (settingsStore?.envSettings?.value?.idaHome ?: dev.idadroid.settings.IdaDroidSettings.DEFAULT_IDA_HOME).trimEnd('/')
        val quotedHome = IdaProotRuntime.shellQuote(idaHome)
        appendLine("set -e")
        appendLine("cd $quotedHome")
        appendLine("export IDADIR=$quotedHome")
        appendLine("export LD_LIBRARY_PATH=$quotedHome:\${LD_LIBRARY_PATH:-}")
        append("exec $quotedHome/ida-mcp serve-http")
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

    private fun buildStopCommand(settings: IdaMcpLaunchSettings): String {
        val idaHomeKill = (settingsStore?.envSettings?.value?.idaHome ?: dev.idadroid.settings.IdaDroidSettings.DEFAULT_IDA_HOME).trimEnd('/')
        val pattern1 = IdaProotRuntime.shellQuote("$idaHomeKill/ida-mcp serve-http")
        val pattern2 = IdaProotRuntime.shellQuote("ida-mcp serve-http --bind ${settings.bind}")
        return """
            set +e
            pkill -f $pattern1 2>/dev/null || true
            pkill -f $pattern2 2>/dev/null || true
        """.trimIndent()
    }

    /** bindHost → 本地探测主机列表（通配/0.0.0.0 → 127.0.0.1；:: → ::1）。 */
    private fun probeHosts(bindHost: String?): List<String> {
        // bindHost 默认从当前会话设置取，避免服务绑定到 IPv6 回环(::1)等
        // 非 127.0.0.1 地址时误判为不可达（导致反复重启/销毁）。
        val configured = (bindHost ?: _state.value.settings.bindHost).trim().trim('[', ']')
        return when {
            configured.isBlank() || configured == "0.0.0.0" || configured == "*" ->
                listOf("127.0.0.1")
            configured == "::" -> listOf("::1")
            configured.contains(':') -> listOf(configured) // IPv6 literal
            else -> listOf(configured)
        }
    }

    private fun isTcpOpen(port: Int, bindHost: String? = null): Boolean =
        probeHosts(bindHost).any { host ->
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 350)
                    true
                }
            }.getOrDefault(false)
        }

    private suspend fun waitUntilTcpOpen(port: Int, timeoutMs: Long, bindHost: String? = null): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isTcpOpen(port, bindHost)) return true
            delay(500)
        }
        return false
    }
    /**
     * MCP 就绪判定：不能只依赖端口开放 —— 若别的进程抢占了该端口，waitUntilTcpOpen
     * 会误报就绪而真正的 MCP 进程 bind 失败退出。要求：
     * 1. 由我们启动的进程仍然存活（bind 失败/提前退出 → 立即判定未就绪）；
     * 2. 端口 TCP 开放；
     * 3. 端口上有 HTTP 响应（MCP 是 HTTP 服务，任意 TCP 监听者不会应答）。
     */
    private suspend fun waitUntilMcpReady(
        process: Process,
        port: Int,
        bindHost: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var tcpOpenSince = -1L
        while (System.currentTimeMillis() < deadline) {
            // 由我们启动的进程提前退出（例如 bind 失败）→ 立即判定未就绪，
            // 防止把其它进程抢占的端口误报为 MCP 就绪。
            if (!process.isAlive) return false
            val open = isTcpOpen(port, bindHost)
            if (open) {
                if (tcpOpenSince < 0) tcpOpenSince = System.currentTimeMillis()
                // MCP 特定 HTTP 应答优先；若 HTTP 层探测端点不被支持（连接被重置/
                // 超时），在进程存活且端口已稳定开放一段时间后也视为就绪 ——
                // 否则会把正常启动的 MCP 误杀（30 秒超时 → destroy → 反复重启）。
                val httpOk = isMcpHttpResponding(port, bindHost)
                val stable = System.currentTimeMillis() - tcpOpenSince >= 8_000
                if (httpOk || stable) return true
            } else {
                tcpOpenSince = -1L
            }
            delay(500)
        }
        return false
    }

    /** MCP HTTP 探测端点候选（按顺序尝试，任一返回 HTTP 状态即视为应答）。 */
    private val mcpProbePaths = listOf("/", "/api/health", "/api/transfers")

    /** 探测端口上的 HTTP 服务是否应答（任意 2xx/3xx/4xx/5xx 均视为有 HTTP 服务）。 */
    private fun isMcpHttpResponding(port: Int, bindHost: String): Boolean =
        probeHosts(bindHost).any { host ->
            mcpProbePaths.any { probePath ->
                runCatching {
                    val urlHost = if (host.contains(':')) "[$host]" else host
                    val conn = (URL("http://$urlHost:$port$probePath").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 1200
                        readTimeout = 1200
                    }
                    try {
                        val code = conn.responseCode
                        code >= 200 && code < 600
                    } finally {
                        conn.disconnect()
                    }
                }.getOrDefault(false)
            }
        }

    private suspend fun waitUntilPortClosed(port: Int, timeoutMs: Long, bindHost: String? = null): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isTcpOpen(port, bindHost)) return true
            delay(300)
        }
        return false
    }

    private fun pumpProcessOutput(process: Process, file: File) {
        file.parentFile?.mkdirs()
        val logLock = Any()
        fun logLine(prefix: String, line: String) = synchronized(logLock) {
            file.appendText("[$prefix] $line\n")
        }
        synchronized(logLock) {
            file.appendText("\n== ${Instant.now()} IDA MCP supervisor started pid=${process.safePid() ?: "unknown"} ==\n")
        }
        Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { logLine("stdout", it) }
                }
            } catch (_: java.io.IOException) {
                // Stream closed by process teardown (stop/destroy); reading is done.
            }
        }.apply { name = "idadroid-mcp-stdout"; isDaemon = true; start() }
        Thread {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { logLine("stderr", it) }
                }
            } catch (_: java.io.IOException) {
                // Stream closed by process teardown (stop/destroy); reading is done.
            }
        }.apply { name = "idadroid-mcp-stderr"; isDaemon = true; start() }
    }

    private fun materializeLogDir() {
        File(paths.rootfsDir, "root/pi_workspace/.idadroid/logs").mkdirs()
        paths.logsDir.mkdirs()
    }

    fun logFile(): File = File(paths.logsDir, "ida-mcp-http.log")

    @Volatile private var activeProcess: Process? = null

    /** Bind endpoint ("host:port") the active process was launched with. */
    @Volatile private var activeProcessBind: String? = null

    // ==================== Health Monitor / Watchdog ====================

    /**
     * Enable or disable the health-monitoring watchdog. When enabled (default),
     * the manager periodically checks whether the MCP process is alive and the
     * port is responding, and auto-restarts the service if it has died.
     */
    fun setMonitoringEnabled(enabled: Boolean) {
        monitoringEnabled = enabled
        if (!enabled) {
            stopWatchdog()
        } else if (_state.value.status == IdaMcpStatus.Running && activeProcess?.isAlive == true) {
            // 服务已在运行但 watchdog 之前被关闭（例如启动时监控被禁用）：
            // 重新启用后立即启动监控循环，否则正在运行的服务将不再被守护。
            startWatchdog(_state.value.settings)
        }
        _state.update { it.copy(monitoringEnabled = enabled) }
    }

    fun isMonitoringEnabled(): Boolean = monitoringEnabled

    /** One-shot health probe — updates [lastHealthOk] and returns the result. */
    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        val process = activeProcess
        val settings = _state.value.settings
        val alive = process?.isAlive == true
        val portOpen = isTcpOpen(settings.port, settings.bindHost)
        val ok = alive && portOpen
        val now = System.currentTimeMillis()
        lastHealthOk = ok
        lastHealthCheckAt = now
        // 同步发布健康结果到 state，避免 UI 看到过期的 lastHealthOk/lastHealthCheckAt
        _state.update { it.copy(lastHealthOk = ok, lastHealthCheckAt = now) }
        if (!ok && _state.value.status == IdaMcpStatus.Running) {
            _state.update { it.copy(
                status = IdaMcpStatus.Error,
                message = "健康检查失败：进程${if (alive) "存活" else "已退出"}，端口${if (portOpen) "开放" else "无响应"}"
            ) }
        }
        ok
    }

    private fun startWatchdog(settings: IdaMcpLaunchSettings) {
        stopWatchdog()
        watchdogJob = watchdogScope.launch {
            Log.i("IdaMcpSessionManager", "Watchdog started for port ${settings.port}")
            while (isActive && monitoringEnabled) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    val process = activeProcess
                    val alive = process?.isAlive == true
                    val portOpen = isTcpOpen(settings.port, settings.bindHost)
                    val now = System.currentTimeMillis()
                    lastHealthCheckAt = now
                    lastHealthOk = alive && portOpen
                    // 同步发布健康结果到 state，避免 UI 一直显示旧健康状态
                    _state.update { it.copy(lastHealthOk = lastHealthOk, lastHealthCheckAt = now) }

                    if (!alive || !portOpen) {
                        // Process died or port stopped responding while we think
                        // it should be running — attempt auto-restart.
                        if (_state.value.status == IdaMcpStatus.Running && monitoringEnabled) {
                            val count = autoRestartCount.incrementAndGet()
                            Log.w("IdaMcpSessionManager", "Watchdog detected failure (alive=$alive, port=$portOpen), auto-restart #$count")
                            _state.update { it.copy(
                                status = IdaMcpStatus.Starting,
                                message = "监控检测到服务中断，正在自动重启…（第 $count 次）"
                            ) }
                            runCatching { start(settings) }
                                .onFailure { Log.e("IdaMcpSessionManager", "Auto-restart failed", it) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("IdaMcpSessionManager", "Watchdog check failed", e)
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        lastHealthOk = false
    }

    companion object {
        private val startStopMutex = Mutex()
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        @Volatile
        private var instance: IdaMcpSessionManager? = null

        /**
         * Process-wide singleton. Home UI and the floating window must share
         * one manager (state, settings and the launched process), otherwise
         * each surface starts its own MCP with its own settings and neither
         * reflects the other's real state.
         */
        fun get(context: Context, settingsStore: dev.idadroid.settings.IdaDroidSettings? = null): IdaMcpSessionManager {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: IdaMcpSessionManager(context.applicationContext, settingsStore = settingsStore).also { instance = it }
            }
        }
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
    val bind: String get() {
        val host = bindHost.ifBlank { "127.0.0.1" }
        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$formattedHost:${port.coerceIn(1, 65535)}"
    }
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
    val startedAt: Long? = null,
    val monitoringEnabled: Boolean = true,
    val lastHealthCheckAt: Long? = null,
    val lastHealthOk: Boolean = false,
    val autoRestartCount: Int = 0
)
