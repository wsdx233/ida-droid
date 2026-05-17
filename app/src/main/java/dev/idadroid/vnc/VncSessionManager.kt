package dev.idadroid.vnc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import dev.idadroid.settings.IdaDroidSettings
import dev.idadroid.settings.VncSettings
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

/**
 * Phase 3 GUI/VNC session coordinator.
 *
 * The bundled viewer is still a future AVNC-derived module. For this phase we launch AVNC (or any
 * app handling vnc://) through RFC-7869 URI while keeping all IDA/VNC lifecycle inside IDAdroid.
 */
class VncSessionManager(
    context: Context,
    private val settingsStore: IdaDroidSettings,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext
    private val runtime = IdaProotRuntime(appContext, paths = paths)

    private val _state = MutableStateFlow(GuiSessionState(status = GuiStatus.Stopped, message = "未启动"))
    val state: StateFlow<GuiSessionState> = _state.asStateFlow()

    suspend fun startGui(openViewer: Boolean = true): Result<GuiSessionState> {
        if (!startStopMutex.tryLock()) {
            val busy = _state.value.copy(
                status = GuiStatus.Starting,
                message = "IDA GUI/VNC 正在启动，请稍候…",
                startedAt = _state.value.startedAt ?: System.currentTimeMillis()
            )
            _state.value = busy
            return Result.success(busy)
        }
        try {
            val settings = settingsStore.vncSettings.value
            val startResult = withContext(Dispatchers.IO) {
            runCatching {
                require(paths.readyMarker.isFile && paths.rootfsDir.isDirectory) { "rootfs 尚未 ready，请先导入并验证环境" }
                materializeScripts(settings)

                if (isTcpOpen(settings.port)) {
                    val running = runningState(settings, "VNC 已在端口 ${settings.port} 运行")
                    _state.value = running
                    return@runCatching running
                }

                _state.value = GuiSessionState(
                    status = GuiStatus.Starting,
                    port = settings.port,
                    display = settings.display,
                    pid = null,
                    message = "正在启动 IDA GUI/VNC...",
                    startedAt = System.currentTimeMillis()
                )

                activeProcess?.takeIf { it.isAlive }?.destroy()
                val command = buildStartCommand(settings)
                val spec = runtime.workspaceCommandSpec(command)
                val process = ProcessBuilder(spec.command)
                    .directory(spec.workingDirectory)
                    .also { it.environment().putAll(spec.environment) }
                    .start()
                activeProcess = process
                pumpProcessOutput(process, supervisorLogFile())

                val ready = waitUntilTcpOpen(settings.port, timeoutMs = 45_000)
                if (!ready) {
                    val logTail = readLogTail(40).ifBlank { "暂无 ida-vnc.log" }
                    val errorState = GuiSessionState(
                        status = GuiStatus.Error,
                        port = settings.port,
                        display = settings.display,
                        pid = process.safePid(),
                        message = "VNC 端口 ${settings.port} 在 45 秒内未就绪\n$logTail",
                        startedAt = System.currentTimeMillis()
                    )
                    _state.value = errorState
                    throw IllegalStateException(errorState.message)
                }

                val running = runningState(settings, "IDA GUI/VNC 已启动：127.0.0.1:${settings.port}")
                _state.value = running
                running
            }
        }

            val state = startResult.getOrElse { return Result.failure(it) }
            if (openViewer) {
                connectViewer().onFailure { error ->
                    val updated = state.copy(message = "${state.message}\nVNC 已启动，但打开 viewer 失败：${error.message}")
                    _state.value = updated
                    return Result.success(updated)
                }
            }
            return Result.success(_state.value)
        } finally {
            startStopMutex.unlock()
        }
    }

    suspend fun stopGui(): Result<Unit> {
        startStopMutex.lock()
        try {
            return withContext(Dispatchers.IO) {
                runCatching {
            val settings = settingsStore.vncSettings.value
            materializeScripts(settings)
            activeProcess?.let { process ->
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                }
            }
            activeProcess = null
            val stopCommand = buildStopCommand(settings)
            val result = runtime.run(stopCommand, timeoutMs = 20_000)
            if (result.exitCode != 0 && !result.timedOut) {
                throw IllegalStateException("停止 GUI 失败：${result.stderr.ifBlank { result.stdout }}")
            }
                    _state.value = GuiSessionState(
                        status = GuiStatus.Stopped,
                        port = settings.port,
                        display = settings.display,
                        message = "已停止 IDA GUI/VNC"
                    )
                }
            }
        } finally {
            startStopMutex.unlock()
        }
    }

    suspend fun restartGui(openViewer: Boolean = true): Result<GuiSessionState> {
        stopGui()
        return startGui(openViewer = openViewer)
    }

    suspend fun connectViewer(): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            val settings = settingsStore.vncSettings.value
            val uri = buildVncUri(settings)
            val preferred = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setPackage(AVNC_PACKAGE)
            val generic = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                appContext.startActivity(preferred)
            } catch (_: ActivityNotFoundException) {
                try {
                    appContext.startActivity(generic)
                } catch (_: ActivityNotFoundException) {
                    throw ActivityNotFoundException("未安装 AVNC 或其它可处理 vnc:// 的 VNC 客户端")
                }
            }
        }
    }

    suspend fun probe(): GuiSessionState = withContext(Dispatchers.IO) {
        val settings = settingsStore.vncSettings.value
        val probed = when {
            isTcpOpen(settings.port) -> runningState(settings, "VNC 端口 ${settings.port} 已就绪")
            activeProcess?.isAlive == true -> GuiSessionState(
                status = GuiStatus.Starting,
                port = settings.port,
                display = settings.display,
                pid = activeProcess.safePid(),
                message = "进程仍在运行，等待 VNC 端口 ${settings.port}",
                startedAt = _state.value.startedAt
            )
            else -> GuiSessionState(status = GuiStatus.Stopped, port = settings.port, display = settings.display, message = "未启动")
        }
        _state.value = probed
        probed
    }

    fun readLogTail(maxLines: Int = 120): String {
        val files = listOf(
            File(paths.rootfsDir, "root/pi_workspace/.idadroid/logs/ida-vnc.log"),
            supervisorLogFile()
        )
        return files.filter { it.isFile }.joinToString("\n\n") { file ->
            val lines = runCatching { file.readLines().takeLast(maxLines) }.getOrDefault(emptyList())
            "== ${file.name} ==\n${lines.joinToString("\n")}".trimEnd()
        }
    }

    private fun runningState(settings: VncSettings, message: String): GuiSessionState = GuiSessionState(
        status = GuiStatus.Running,
        port = settings.port,
        display = settings.display,
        pid = activeProcess.safePid(),
        message = message,
        startedAt = _state.value.startedAt ?: System.currentTimeMillis()
    )

    private fun buildStartCommand(settings: VncSettings): String = listOf(
        "IDADROID_DISPLAY=${IdaProotRuntime.shellQuote(settings.display.toString())}",
        "IDADROID_VNC_PORT=${IdaProotRuntime.shellQuote(settings.port.toString())}",
        "IDADROID_VNC_PASSWORD=${IdaProotRuntime.shellQuote(settings.password)}",
        "IDADROID_GEOMETRY=${IdaProotRuntime.shellQuote(settings.geometry)}",
        "IDADROID_DEPTH=${IdaProotRuntime.shellQuote(settings.depth.toString())}",
        "/root/pi_workspace/.idadroid/scripts/start-ida-vnc.sh"
    ).joinToString(" ")

    private fun buildStopCommand(settings: VncSettings): String = listOf(
        "IDADROID_DISPLAY=${IdaProotRuntime.shellQuote(settings.display.toString())}",
        "IDADROID_VNC_PORT=${IdaProotRuntime.shellQuote(settings.port.toString())}",
        "/root/pi_workspace/.idadroid/scripts/stop-ida-vnc.sh"
    ).joinToString(" ")

    private fun buildVncUri(settings: VncSettings): Uri {
        val builder = Uri.Builder()
            .scheme("vnc")
            .encodedAuthority("127.0.0.1:${settings.port}")
            .appendQueryParameter("ConnectionName", "IDA")
            .appendQueryParameter("SecurityType", "0")
            .appendQueryParameter("SaveConnection", "false")
        if (settings.password.isNotBlank()) builder.appendQueryParameter("VncPassword", settings.password)
        return builder.build()
    }

    private fun materializeScripts(settings: VncSettings) {
        val scriptsDir = File(paths.rootfsDir, "root/pi_workspace/.idadroid/scripts").apply { mkdirs() }
        File(paths.rootfsDir, "root/pi_workspace/.idadroid/logs").mkdirs()
        val start = File(scriptsDir, "start-ida-vnc.sh")
        val stop = File(scriptsDir, "stop-ida-vnc.sh")
        start.writeText(startScript(settings))
        stop.writeText(stopScript())
        runCatching { Os.chmod(start.absolutePath, 493) }
        runCatching { Os.chmod(stop.absolutePath, 493) }
    }

    private fun startScript(settings: VncSettings): String = """
        #!/usr/bin/env bash
        set -euo pipefail

        export HOME=/root
        export USER=root
        export LOGNAME=root
        export DISPLAY=":${'$'}{IDADROID_DISPLAY:-${settings.display}}"
        display_num="${'$'}{DISPLAY#:}"
        export VNC_PORT="${'$'}{IDADROID_VNC_PORT:-${settings.port}}"
        if [ "${'$'}{IDADROID_VNC_PASSWORD+x}" = x ]; then
          export VNC_PASSWORD="${'$'}IDADROID_VNC_PASSWORD"
        else
          export VNC_PASSWORD=${IdaProotRuntime.shellQuote(settings.password)}
        fi
        export GEOMETRY="${'$'}{IDADROID_GEOMETRY:-${settings.geometry}}"
        export DEPTH="${'$'}{IDADROID_DEPTH:-${settings.depth}}"
        export XDG_RUNTIME_DIR=/tmp/runtime-root
        export QT_QPA_PLATFORM=xcb
        export QT_X11_NO_MITSHM=1
        export LIBGL_ALWAYS_SOFTWARE=1
        export NO_AT_BRIDGE=1

        mkdir -p "${'$'}XDG_RUNTIME_DIR" /tmp/.X11-unix /root/pi_workspace/.idadroid/logs
        chmod 700 "${'$'}XDG_RUNTIME_DIR" >/dev/null 2>&1 || true

        log=/root/pi_workspace/.idadroid/logs/ida-vnc.log
        : > "${'$'}log"
        echo "[$(date -Is 2>/dev/null || date)] IDAdroid VNC start" >>"${'$'}log"
        echo "display=${'$'}DISPLAY port=${'$'}VNC_PORT geometry=${'$'}GEOMETRY depth=${'$'}DEPTH" >>"${'$'}log"

        cleanup() {
          for pid in ${'$'}(jobs -pr 2>/dev/null || true); do kill "${'$'}pid" 2>/dev/null || true; done
          if command -v vncserver >/dev/null 2>&1; then vncserver -kill "${'$'}DISPLAY" >>"${'$'}log" 2>&1 || true; fi
          pkill -f "x11vnc.*-rfbport ${'$'}VNC_PORT" 2>/dev/null || true
          pkill -f "Xtigervnc.*:${'$'}display_num" 2>/dev/null || true
          if [ -f /tmp/idadroid-ida.pid ]; then kill "${'$'}(cat /tmp/idadroid-ida.pid)" 2>/dev/null || true; fi
        }
        trap cleanup EXIT INT TERM

        cleanup_stale() {
          if command -v vncserver >/dev/null 2>&1; then vncserver -kill "${'$'}DISPLAY" >>"${'$'}log" 2>&1 || true; fi
          pkill -f "x11vnc.*-rfbport ${'$'}VNC_PORT" 2>/dev/null || true
          pkill -f "Xtigervnc.*:${'$'}display_num" 2>/dev/null || true
          rm -f "/tmp/.X${'$'}{display_num}-lock" "/tmp/.X11-unix/X${'$'}display_num" 2>/dev/null || true
        }

        create_passwd_file() {
          [ -n "${'$'}VNC_PASSWORD" ] || return 0
          command -v vncpasswd >/dev/null 2>&1 || return 0
          passdir=/tmp/tigervnc-idadroid
          mkdir -p "${'$'}passdir"
          chmod 700 "${'$'}passdir" >/dev/null 2>&1 || true
          printf '%s\n' "${'$'}VNC_PASSWORD" | vncpasswd -f > "${'$'}passdir/passwd"
          chmod 600 "${'$'}passdir/passwd" >/dev/null 2>&1 || true
          printf '%s' "${'$'}passdir/passwd"
        }

        start_wm() {
          if command -v openbox >/dev/null 2>&1; then openbox >>"${'$'}log" 2>&1 & return 0; fi
          if command -v fluxbox >/dev/null 2>&1; then fluxbox >>"${'$'}log" 2>&1 & return 0; fi
          if command -v xfce4-session >/dev/null 2>&1; then xfce4-session >>"${'$'}log" 2>&1 & return 0; fi
          if command -v startxfce4 >/dev/null 2>&1; then startxfce4 >>"${'$'}log" 2>&1 & return 0; fi
          echo "No window manager found" >>"${'$'}log"
          return 1
        }

        start_ida() {
          cd /root/ida-pro-9.3 2>/dev/null || { echo "No /root/ida-pro-9.3" >>"${'$'}log"; return 1; }
          for bin in ./ida ./ida64 ./idat ./idat64; do
            if [ -x "${'$'}bin" ]; then
              "${'$'}bin" >>"${'$'}log" 2>&1 &
              echo "${'$'}!" > /tmp/idadroid-ida.pid
              echo "IDA PID: ${'$'}!" | tee -a "${'$'}log"
              return 0
            fi
          done
          echo "No executable IDA binary found" >>"${'$'}log"
          return 1
        }

        start_vnc() {
          passwd_file="${'$'}(create_passwd_file || true)"
          if [ -n "${'$'}passwd_file" ]; then
            security_args=(-SecurityTypes VncAuth -PasswordFile "${'$'}passwd_file")
          else
            security_args=(-SecurityTypes None)
          fi

          cleanup_stale

          if command -v Xtigervnc >/dev/null 2>&1; then
            Xtigervnc "${'$'}DISPLAY" -localhost -rfbport "${'$'}VNC_PORT" -geometry "${'$'}GEOMETRY" -depth "${'$'}DEPTH" "${'$'}{security_args[@]}" >>"${'$'}log" 2>&1 &
            echo "${'$'}!" > /tmp/idadroid-vnc.pid
          elif command -v vncserver >/dev/null 2>&1; then
            vncserver "${'$'}DISPLAY" -localhost yes -rfbport "${'$'}VNC_PORT" -geometry "${'$'}GEOMETRY" -depth "${'$'}DEPTH" "${'$'}{security_args[@]}" >>"${'$'}log" 2>&1
          elif command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1; then
            Xvfb "${'$'}DISPLAY" -screen 0 "${'$'}{GEOMETRY}x${'$'}DEPTH" +extension GLX +render -noreset >>"${'$'}log" 2>&1 &
            echo "${'$'}!" > /tmp/idadroid-xvfb.pid
            sleep 1
            if [ -n "${'$'}VNC_PASSWORD" ]; then
              x11vnc -display "${'$'}DISPLAY" -localhost -passwd "${'$'}VNC_PASSWORD" -rfbport "${'$'}VNC_PORT" -forever -shared >>"${'$'}log" 2>&1 &
            else
              x11vnc -display "${'$'}DISPLAY" -localhost -nopw -rfbport "${'$'}VNC_PORT" -forever -shared >>"${'$'}log" 2>&1 &
            fi
            echo "${'$'}!" > /tmp/idadroid-vnc.pid
          elif [ -x /root/start-vnc-ida.sh ]; then
            echo "No direct VNC server command found; delegating to /root/start-vnc-ida.sh" >>"${'$'}log"
            IDADROID_VNC_PORT="${'$'}VNC_PORT" IDADROID_VNC_PASSWORD="${'$'}VNC_PASSWORD" IDADROID_DISPLAY="${'$'}display_num" /root/start-vnc-ida.sh >>"${'$'}log" 2>&1 &
            echo "${'$'}!" > /tmp/idadroid-rootfs-vnc-script.pid
          else
            echo "No supported VNC/X server found" >>"${'$'}log"
            exit 127
          fi
        }

        start_vnc
        sleep 2
        start_wm || true
        start_ida || true

        echo "VNC started on display ${'$'}DISPLAY (port ${'$'}VNC_PORT)" | tee -a "${'$'}log"
        if [ -n "${'$'}VNC_PASSWORD" ]; then echo "Password: ${'$'}VNC_PASSWORD" | tee -a "${'$'}log"; fi

        while true; do
          if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":${'$'}VNC_PORT "; then sleep 2; continue; fi
          sleep 2
        done
    """.trimIndent() + "\n"

    private fun stopScript(): String = """
        #!/usr/bin/env bash
        set +e
        export DISPLAY=":${'$'}{IDADROID_DISPLAY:-1}"
        display_num="${'$'}{DISPLAY#:}"
        VNC_PORT="${'$'}{IDADROID_VNC_PORT:-5901}"
        log=/root/pi_workspace/.idadroid/logs/ida-vnc.log
        mkdir -p /root/pi_workspace/.idadroid/logs
        echo "[$(date -Is 2>/dev/null || date)] IDAdroid VNC stop display=${'$'}DISPLAY port=${'$'}VNC_PORT" >>"${'$'}log"

        if command -v vncserver >/dev/null 2>&1; then vncserver -kill "${'$'}DISPLAY" >>"${'$'}log" 2>&1 || true; fi
        for f in /tmp/idadroid-ida.pid /tmp/idadroid-vnc.pid /tmp/idadroid-xvfb.pid /tmp/idadroid-rootfs-vnc-script.pid; do
          [ -f "${'$'}f" ] && kill "${'$'}(cat "${'$'}f")" 2>/dev/null || true
          rm -f "${'$'}f" 2>/dev/null || true
        done
        pkill -f "x11vnc.*-rfbport ${'$'}VNC_PORT" 2>/dev/null || true
        pkill -f "Xtigervnc.*:${'$'}display_num" 2>/dev/null || true
        pkill -f "/root/ida-pro-9.3/ida" 2>/dev/null || true
        rm -f "/tmp/.X${'$'}{display_num}-lock" "/tmp/.X11-unix/X${'$'}display_num" 2>/dev/null || true
        echo "Stopped" >>"${'$'}log"
    """.trimIndent() + "\n"

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
        file.appendText("\n== ${Instant.now()} supervisor started pid=${process.safePid() ?: "unknown"} ==\n")
        Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { file.appendText("[stdout] $it\n") }
            }
        }.apply { name = "idadroid-vnc-stdout"; isDaemon = true; start() }
        Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { file.appendText("[stderr] $it\n") }
            }
        }.apply { name = "idadroid-vnc-stderr"; isDaemon = true; start() }
    }

    private fun supervisorLogFile(): File = File(paths.logsDir, "ida-vnc-supervisor.log")

    private fun Process?.safePid(): Int? = null

    companion object {
        private const val AVNC_PACKAGE = "com.gaurav.avnc"
        private val startStopMutex = Mutex()

        @Volatile
        private var activeProcess: Process? = null
    }
}

enum class GuiStatus { Stopped, Starting, Running, Error }

data class GuiSessionState(
    val status: GuiStatus,
    val port: Int? = null,
    val display: Int? = null,
    val pid: Int? = null,
    val message: String = "",
    val startedAt: Long? = null
)
