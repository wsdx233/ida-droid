package dev.idadroid.proot

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.env.RootfsConfigurator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible

class IdaProotRuntime(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context),
    private val rootfsDir: File = paths.rootfsDir,
    private val hostTmpDir: File = paths.hostTmpDir
) {
    private val appContext = context.applicationContext
    private val settings = dev.idadroid.settings.IdaDroidSettings(appContext)
    private val proroot = ProrootSupport(appContext, paths, rootfsDir, hostTmpDir)
    private val rootfsConfigurator = RootfsConfigurator(appContext)
    /** 用户设置的工作区路径（容器内可见路径） */
    private val workspacePath: String get() = settings.envSettings.value.workspacePath.ifBlank { DEFAULT_WORKSPACE }


    data class LaunchSpec(
        val command: List<String>,
        val workingDirectory: File,
        val environment: Map<String, String>
    )

    data class TerminalLaunchSpec(
        val executable: String,
        val workingDirectory: String,
        /**
         * Full argv, including argv[0]. terminal-emulator's JNI passes this array
         * directly to execvp(cmd, argv), so dropping argv[0] would make proot miss
         * its first flag.
         */
        val args: Array<String>,
        val environment: Array<String>
    )

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false
    )

    fun buildInteractiveLaunch(term: String = "xterm-256color", cwd: String = workspacePath): TerminalLaunchSpec =
        interactiveShellSpec(term = term, cwd = cwd)

    fun interactiveShellSpec(term: String = "xterm-256color", cwd: String = workspacePath): TerminalLaunchSpec {
        val spec = interactiveLaunchSpec(term = term, cwd = cwd)
        return TerminalLaunchSpec(
            executable = spec.command.first(),
            workingDirectory = spec.workingDirectory.absolutePath,
            args = spec.command.toTypedArray(),
            environment = spec.environment.entries.map { (key, value) -> "$key=$value" }.toTypedArray()
        )
    }

    fun commandSpec(script: String, cwd: String = "/root", term: String = "dumb"): LaunchSpec {
        val guestShell = resolveGuestShell()
        val command = buildProotPrefix(term = term, cwd = cwd).apply {
            addAll(listOf(guestShell, "-l", "-c", wrapScriptWithRuntimeBootstrap(script)))
        }
        return LaunchSpec(command, workingDirectory = paths.envDir.apply { mkdirs() }, environment = hostEnvironment())
    }

    fun workspaceCommandSpec(script: String): LaunchSpec = commandSpec(script, cwd = workspacePath)

    suspend fun run(script: String, timeoutMs: Long = 120_000): CommandResult = runInterruptible(Dispatchers.IO) {
        runProcess(commandSpec(script), timeoutMs)
    }

    private fun runProcess(spec: LaunchSpec, timeoutMs: Long): CommandResult {
        val processBuilder = ProcessBuilder(spec.command).directory(spec.workingDirectory)
        processBuilder.environment().putAll(spec.environment)
        val process = processBuilder.start()
        val executor = Executors.newFixedThreadPool(2)
        val stdoutFuture = executor.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
        val stderrFuture = executor.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }
        var finished = false
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) terminate(process)
            val exitCode = if (finished) process.exitValue() else -1
            val stdout = runCatching { stdoutFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            val stderr = runCatching { stderrFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            return CommandResult(exitCode, stdout, stderr, timedOut = !finished)
        } finally {
            if (process.isAlive) terminate(process)
            executor.shutdownNow()
        }
    }

    private fun terminate(process: Process) {
        process.destroy()
        if (!runCatching { process.waitFor(1_500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) process.destroyForcibly()
    }

    /** 便捷方法：执行单条命令，返回 stdout+stderr 合并文本 */
    suspend fun executeCommandWithTimeout(command: String, timeoutMs: Long = 60_000): String = runInterruptible(Dispatchers.IO) {
        val result = runProcess(workspaceCommandSpec(command), timeoutMs)
        if (result.timedOut) {
            "错误: 命令超时 (${timeoutMs / 1000}s)\nstderr: ${result.stderr}"
        } else if (result.exitCode != 0) {
            "退出码: ${result.exitCode}\nstdout: ${result.stdout}\nstderr: ${result.stderr}"
        } else result.stdout.ifBlank { result.stderr.ifBlank { "(无输出)" } }
    }

    fun describe(spec: TerminalLaunchSpec): String = buildString {
        appendLine("runtime=${proroot.diagnostics()}")
        appendLine("cwd=${spec.workingDirectory}")
        appendLine("env=${spec.environment.joinToString(" ") { shellQuote(it) }}")
        append("cmd=")
        append(spec.args.joinToString(" ") { shellQuote(it) })
    }

    fun describe(spec: LaunchSpec): String = buildString {
        appendLine("runtime=${proroot.diagnostics()}")
        appendLine("cwd=${spec.workingDirectory.absolutePath}")
        appendLine("env=${spec.environment.entries.joinToString(" ") { (key, value) -> shellQuote("$key=$value") }}")
        append("cmd=")
        append(spec.command.joinToString(" ") { shellQuote(it) })
    }
    private fun buildProotPrefix(term: String, cwd: String): MutableList<String> {
        rootfsConfigurator.ensureResolverConfig(rootfsDir)
        return when (proroot.select().kind) {
            ProrootSupport.Kind.PROROOT -> proroot.prefix(ProrootSupport.Kind.PROROOT, resolveGuestWorkingDirectory(cwd)).apply {
                addAll(listOf("/usr/bin/env", "-i"))
            }
            ProrootSupport.Kind.PROOT -> {
                require(paths.prootBinary.isFile) { "proot binary is not installed: ${paths.prootBinary.absolutePath}" }
                mutableListOf<String>().apply {
                    addAll(listOf(paths.prootBinary.absolutePath, "-L", "--link2symlink", "--kill-on-exit", "--root-id", "-r", rootfsDir.absolutePath))
                    listOf("/dev", "/proc", "/sys", "/storage", "/sdcard", "/mnt").forEach { hostPath ->
                        if (File(hostPath).exists()) addAll(listOf("-b", hostPath))
                    }
                    listOf(
                        "/dev/urandom" to "/dev/random",
                        "/proc/self/fd" to "/dev/fd",
                        hostTmpDir.absolutePath to "/tmp",
                        hostTmpDir.absolutePath to "/var/tmp",
                        appContext.filesDir.absolutePath to "/host/files",
                        appContext.cacheDir.absolutePath to "/host/cache"
                    ).forEach { (host, guest) -> if (File(host).exists()) addAll(listOf("-b", "$host:$guest")) }
                    val fakeFips = File(rootfsDir, "proc/sys/crypto/fips_enabled")
                    if (fakeFips.exists()) addAll(listOf("-b", "${fakeFips.absolutePath}:/proc/sys/crypto/fips_enabled"))
                    val devShmSource = File(rootfsDir, "tmp")
                    if (devShmSource.exists()) addAll(listOf("-b", "${devShmSource.absolutePath}:/dev/shm"))
                    addAll(listOf("-w", resolveGuestWorkingDirectory(cwd), "/usr/bin/env", "-i"))
                }
            }
        }.also { command -> guestEnvironment(term).forEach { (key, value) -> command += "$key=$value" } }
    }

    private fun interactiveLaunchSpec(term: String, cwd: String): LaunchSpec {
        val guestShell = resolveGuestShell()
        val command = buildProotPrefix(term = term, cwd = cwd).apply {
            addAll(listOf(guestShell, "-l"))
        }
        return LaunchSpec(command, workingDirectory = paths.envDir.apply { mkdirs() }, environment = hostEnvironment())
    }


    private fun guestEnvironment(term: String): Map<String, String> = linkedMapOf(
        "HOME" to "/root",
        "USER" to "root",
        "LOGNAME" to "root",
        "SHELL" to resolveGuestShell(),
        "WORKSPACE" to workspacePath,
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
        "NVM_DIR" to "/root/.nvm",
        "PATH" to buildGuestPath(),
        "TERM" to term,
        "TMPDIR" to "/tmp",
        "XDG_RUNTIME_DIR" to "/tmp/runtime-root",
        "XDG_DATA_HOME" to "/root/.local/share",
        "XDG_CACHE_HOME" to "/root/.cache",
        "XDG_CONFIG_HOME" to "/root/.config",
        "PI_CODING_AGENT_DIR" to "$workspacePath/.idadroid/pi-agent",
        "PI_SKIP_VERSION_CHECK" to "1",
        "PI_TELEMETRY" to "0"
    )

    private fun buildGuestPath(): String {
        val path = linkedSetOf<String>()
        path += collectVersionedBinDirs(File(rootfsDir, "root/.nvm/versions/node"), "/root/.nvm/versions/node")
        path += collectVersionedBinDirs(File(rootfsDir, "opt/nvm/versions/node"), "/opt/nvm/versions/node")
        path += listOf(
            "/root/.npm-global/bin",
            "/root/.local/bin",
            "/root/bin",
            "/usr/local/sbin",
            "/usr/local/bin",
            "/usr/sbin",
            "/usr/bin",
            "/sbin",
            "/bin"
        )
        return path.joinToString(":")
    }

    private fun collectVersionedBinDirs(hostVersionsDir: File, guestVersionsDir: String): List<String> =
        hostVersionsDir.listFiles()
            ?.filter { File(it, "bin").isDirectory }
            ?.sortedWith(compareByDescending<File> { versionWeight(it.name) }.thenByDescending { it.name })
            ?.map { "$guestVersionsDir/${it.name}/bin" }
            .orEmpty()

    private fun versionWeight(name: String): Long {
        val parts = name.trimStart('v', 'V')
            .split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val major = parts.getOrNull(0)?.coerceIn(0, 4095) ?: 0
        val minor = parts.getOrNull(1)?.coerceIn(0, 4095) ?: 0
        val patch = parts.getOrNull(2)?.coerceIn(0, 4095) ?: 0
        return (major.toLong() shl 24) or (minor.toLong() shl 12) or patch.toLong()
    }

    private fun wrapScriptWithRuntimeBootstrap(script: String): String = """
        export HOME=/root
        export WORKSPACE="$workspacePath"
        export PI_CODING_AGENT_DIR="$workspacePath/.idadroid/pi-agent"
        export NVM_DIR="${'$'}{NVM_DIR:-/root/.nvm}"
        if [ -s "${'$'}NVM_DIR/nvm.sh" ]; then . "${'$'}NVM_DIR/nvm.sh" >/dev/null 2>&1 || true; fi
        for d in /root/.nvm/versions/node/*/bin /opt/nvm/versions/node/*/bin /root/.npm-global/bin /root/.local/bin /root/bin; do
          if [ -d "${'$'}d" ]; then PATH="${'$'}d:${'$'}PATH"; fi
        done
        export PATH
        mkdir -p /tmp/runtime-root >/dev/null 2>&1 || true
        chmod 700 /tmp/runtime-root >/dev/null 2>&1 || true
        $script
    """.trimIndent()

    private fun resolveGuestShell(): String = when {
        File(rootfsDir, "bin/bash").exists() -> "/bin/bash"
        File(rootfsDir, "usr/bin/bash").exists() -> "/usr/bin/bash"
        File(rootfsDir, "bin/sh").exists() -> "/bin/sh"
        File(rootfsDir, "usr/bin/sh").exists() -> "/usr/bin/sh"
        else -> "/bin/sh"
    }
    private fun hostEnvironment(): Map<String, String> = linkedMapOf(
        "TMPDIR" to hostTmpDir.absolutePath,
        "PROOT_TMP_DIR" to hostTmpDir.absolutePath
    ).also { environment ->
        if (proroot.select().kind == ProrootSupport.Kind.PROROOT) proroot.applyEnvironment(environment)
    }
    private fun resolveGuestWorkingDirectory(requested: String): String {
        val normalized = requested.trim().ifBlank { "/root" }.let { if (it.startsWith('/')) it else "/$it" }
        if (guestPathToHost(normalized).isDirectory) return normalized
        if (guestPathToHost("/root").isDirectory) return "/root"
        return "/"
    }

    private fun guestPathToHost(guestPath: String): File {
        val relative = guestPath.trimStart('/')
        return if (relative.isEmpty()) rootfsDir else File(rootfsDir, relative)
    }


    companion object {
        const val DEFAULT_WORKSPACE = "/root/pi_workspace"

        fun shellQuote(value: String): String {
            if (value.isEmpty()) return "''"
            val safe = value.all { char -> char.isLetterOrDigit() || char in "@%_+=:,./-" }
            if (safe) return value
            return "'" + value.replace("'", "'\"'\"'") + "'"
        }
    }
}
