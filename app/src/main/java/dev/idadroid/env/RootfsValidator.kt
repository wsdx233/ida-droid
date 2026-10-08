package dev.idadroid.env

import android.content.Context
import android.os.Build
import android.system.Os
import dev.idadroid.data.CommandStatus
import dev.idadroid.data.FileStatus
import dev.idadroid.data.IdaStatus
import dev.idadroid.data.ValidationReport
import dev.idadroid.data.VncStatus
import dev.idadroid.proot.IdaProotRuntime
import dev.idadroid.proot.ElfAbi
import dev.idadroid.settings.IdaDroidSettings
import dev.idadroid.util.JsonFormats
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class RootfsValidator(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext
    private val settings = IdaDroidSettings(appContext)

    suspend fun validate(
        rootfsDir: File = paths.rootfsDir,
        hostTmpDir: File = paths.hostTmpDir
    ): ValidationReport = withContext(Dispatchers.IO) {
        val idaHome = settings.envSettings.value.idaHome.trimEnd('/').ifBlank { IdaDroidSettings.DEFAULT_IDA_HOME }
        if (!rootfsDir.isDirectory) {
            return@withContext failureReport(idaHome, listOf("rootfs 目录不存在：${rootfsDir.absolutePath}"),
                raw = "rootfs directory missing")
        }

        val abi = inspectAbis(rootfsDir, idaHome)
        if (abi.fatal.isNotEmpty()) {
            return@withContext failureReport(idaHome, abi.fatal, abi.warnings, abi.raw, abi.arch)
                .also { writeActiveValidateLogIfNeeded(rootfsDir, it) }
        }
        val runtime = IdaProotRuntime(appContext, rootfsDir = rootfsDir, hostTmpDir = hostTmpDir)
        val result = try {
            runtime.run(validationProbeScript(idaHome), timeoutMs = 120_000)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return@withContext failureReport(idaHome,
                listOf("无法启动 proot 验证：${error.message ?: error.javaClass.simpleName}"),
                abi.warnings, abi.raw, abi.arch).also { writeActiveValidateLogIfNeeded(rootfsDir, it) }
        }
        val raw = buildString {
            append(abi.raw)
            appendLine("exitCode=${result.exitCode}")
            appendLine("--- stdout ---")
            append(result.stdout)
            appendLine()
            appendLine("--- stderr ---")
            append(result.stderr)
        }

        if (result.exitCode != 0 && result.stdout.lines().none { it.startsWith(PROBE_PREFIX) }) {
            return@withContext failureReport(idaHome,
                listOf("无法进入 proot 或验证脚本失败，exit=${result.exitCode}"),
                abi.warnings + result.stderr.lineSequence().filter { it.isNotBlank() }.take(8).toList(),
                raw, abi.arch).also { writeActiveValidateLogIfNeeded(rootfsDir, it) }
        }

        val fields = parseProbe(result.stdout)
        buildReport(fields, raw, idaHome, abi).also { writeActiveValidateLogIfNeeded(rootfsDir, it) }
    }

    private fun buildReport(fields: Map<String, String>, raw: String, idaHome: String, abi: AbiInspection): ValidationReport {
        val arch = abi.arch
        val homeWritable = fields["homeWritable"].toBooleanCompat()

        val idaBinary = fields["idaBinary"].orEmpty()
        val ida = IdaStatus(
            exists = fields["idaDir"].toBooleanCompat(),
            path = idaHome,
            binary = idaBinary,
            binaryInfo = fields["idaBinaryInfo"].orEmpty()
        )
        val idaMcp = FileStatus(
            path = "$idaHome/ida-mcp",
            exists = fields["idaMcp"].toBooleanCompat(),
            executable = fields["idaMcpExecutable"].toBooleanCompat()
        )
        val usageDoc = FileStatus(
            path = "$idaHome/IDA_MCP_MCPC_USAGE.md",
            exists = fields["usageDoc"].toBooleanCompat()
        )
        val pi = CommandStatus(
            name = "pi",
            exists = fields["piPath"].orEmpty().isNotBlank(),
            path = fields["piPath"].orEmpty(),
            version = fields["piVersion"].orEmpty()
        )
        val node = CommandStatus(
            name = "node",
            exists = fields["nodePath"].orEmpty().isNotBlank(),
            path = fields["nodePath"].orEmpty(),
            version = fields["nodeVersion"].orEmpty()
        )
        val npm = CommandStatus(
            name = "npm",
            exists = fields["npmPath"].orEmpty().isNotBlank(),
            path = fields["npmPath"].orEmpty(),
            version = fields["npmVersion"].orEmpty()
        )
        val mcpc = CommandStatus(
            name = "mcpc",
            exists = fields["mcpcPath"].orEmpty().isNotBlank(),
            path = fields["mcpcPath"].orEmpty(),
            version = fields["mcpcVersion"].orEmpty()
        )

        val vncServer = firstNonBlank(fields["xtigervncPath"], fields["vncserverPath"], fields["x11vncPath"])
        val xServer = firstNonBlank(fields["xvfbPath"], fields["xtigervncPath"])
        val wm = firstNonBlank(fields["openboxPath"], fields["fluxboxPath"], fields["xfce4SessionPath"], fields["startxfce4Path"])
        val vncMode = when {
            fields["xtigervncPath"].orEmpty().isNotBlank() || fields["vncserverPath"].orEmpty().isNotBlank() -> "tigervnc"
            fields["xvfbPath"].orEmpty().isNotBlank() && fields["x11vncPath"].orEmpty().isNotBlank() -> "xvfb-x11vnc"
            else -> "missing"
        }
        val vnc = VncStatus(mode = vncMode, server = vncServer, xServer = xServer, wm = wm)

        val fatal = buildList {
            if (!homeWritable) add("/root 不存在或不可写")
            if (!ida.exists) add("缺少 $idaHome")
            if (!idaMcp.exists) add("缺少 ${idaMcp.path}")
            if (idaMcp.exists && !idaMcp.executable) add("${idaMcp.path} 不可执行")
            if (!pi.exists) add("PATH 中找不到 pi")
            if (!node.exists) add("PATH 中找不到 node")
        }
        val warnings = buildList {
            addAll(abi.warnings)
            if (ida.binary.isBlank()) add("未找到 IDA GUI/terminal binary 候选：ida/ida64/idat/idat64")
            if (!usageDoc.exists) add("缺少 ${usageDoc.path}，agent 无法自动学习 mcpc/ida-mcp 用法")
            if (!npm.exists) add("PATH 中找不到 npm；如果 pi 由 npm/nvm 安装，请确认 /root/.nvm 可被 shell 初始化")
            if (!mcpc.exists) add("PATH 中找不到 mcpc，IDA MCP agent 工作流会受限")
            if (vnc.mode == "missing") add("未检测到 TigerVNC 或 Xvfb+x11vnc，Phase 3 GUI 会受阻")
            if (vnc.wm.isBlank()) add("未检测到 openbox/fluxbox/xfce4-session/startxfce4 等轻量 WM")
            if (node.exists && !node.version.startsWith("v22") && !node.version.startsWith("v23") && !node.version.startsWith("v24")) {
                add("node 版本可能偏低：${node.version.ifBlank { "unknown" }}，建议 >= v22")
            }
        }

        return ValidationReport(
            ok = fatal.isEmpty(),
            arch = arch,
            homeWritable = homeWritable,
            ida = ida,
            idaMcp = idaMcp,
            usageDoc = usageDoc,
            pi = pi,
            node = node,
            npm = npm,
            mcpc = mcpc,
            vnc = vnc,
            fatal = fatal,
            warnings = warnings,
            rawOutput = raw
        )
    }

    private fun validationProbeScript(idaHome: String): String = """
        set +e
        export HOME=/root
        export NVM_DIR=/root/.nvm
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${'$'}PATH
        if [ -s "${'$'}NVM_DIR/nvm.sh" ]; then . "${'$'}NVM_DIR/nvm.sh" >/dev/null 2>&1 || true; fi
        for d in /root/.nvm/versions/node/*/bin /root/.npm-global/bin /root/.local/bin /root/bin; do
          if [ -d "${'$'}d" ]; then PATH="${'$'}d:${'$'}PATH"; fi
        done
        export PATH
        ida_home=${IdaProotRuntime.shellQuote(idaHome)}
        probe() { printf '${PROBE_PREFIX}%s=%s\n' "${'$'}1" "${'$'}2"; }
        cmd_path() { command -v "${'$'}1" 2>/dev/null || true; }
        first_existing() {
          for p in "${'$'}@"; do
            if [ -e "${'$'}p" ]; then printf '%s' "${'$'}p"; return 0; fi
          done
          return 0
        }
        first_line() { "${'$'}@" 2>/dev/null | head -n 1 | tr '\r\n' ' ' || true; }

        probe hostUname "${'$'}(uname -m 2>/dev/null || true)"
        if [ -d /root ] && [ -w /root ]; then probe homeWritable true; else probe homeWritable false; fi
        if [ -d "${'$'}ida_home" ]; then probe idaDir true; else probe idaDir false; fi
        ida_bin="${'$'}(first_existing "${'$'}ida_home/ida" "${'$'}ida_home/ida64" "${'$'}ida_home/idat" "${'$'}ida_home/idat64")"
        probe idaBinary "${'$'}ida_bin"
        if [ -n "${'$'}ida_bin" ] && command -v file >/dev/null 2>&1; then probe idaBinaryInfo "${'$'}(file "${'$'}ida_bin" 2>/dev/null | head -n 1)"; else probe idaBinaryInfo ""; fi
        if [ -e "${'$'}ida_home/ida-mcp" ]; then probe idaMcp true; else probe idaMcp false; fi
        if [ -x "${'$'}ida_home/ida-mcp" ]; then probe idaMcpExecutable true; else probe idaMcpExecutable false; fi
        if [ -f "${'$'}ida_home/IDA_MCP_MCPC_USAGE.md" ]; then probe usageDoc true; else probe usageDoc false; fi

        pi_path="${'$'}(cmd_path pi)"; probe piPath "${'$'}pi_path"
        if [ -n "${'$'}pi_path" ]; then probe piVersion "${'$'}(first_line pi --version)"; else probe piVersion ""; fi
        node_path="${'$'}(cmd_path node)"; probe nodePath "${'$'}node_path"
        if [ -n "${'$'}node_path" ]; then probe nodeVersion "${'$'}(first_line node --version)"; else probe nodeVersion ""; fi
        npm_path="${'$'}(cmd_path npm)"; probe npmPath "${'$'}npm_path"
        if [ -n "${'$'}npm_path" ]; then probe npmVersion "${'$'}(first_line npm --version)"; else probe npmVersion ""; fi
        mcpc_path="${'$'}(cmd_path mcpc)"; probe mcpcPath "${'$'}mcpc_path"
        if [ -n "${'$'}mcpc_path" ]; then probe mcpcVersion "${'$'}(first_line mcpc --version)"; else probe mcpcVersion ""; fi

        probe xtigervncPath "${'$'}(cmd_path Xtigervnc)"
        probe vncserverPath "${'$'}(cmd_path vncserver)"
        probe xvfbPath "${'$'}(cmd_path Xvfb)"
        probe x11vncPath "${'$'}(cmd_path x11vnc)"
        probe openboxPath "${'$'}(cmd_path openbox)"
        probe fluxboxPath "${'$'}(cmd_path fluxbox)"
        probe xfce4SessionPath "${'$'}(cmd_path xfce4-session)"
        probe startxfce4Path "${'$'}(cmd_path startxfce4)"
        mkdir -p /root/pi_workspace >/dev/null 2>&1 || true
    """.trimIndent()

    private fun parseProbe(stdout: String): Map<String, String> = stdout
        .lineSequence()
        .filter { it.startsWith(PROBE_PREFIX) }
        .mapNotNull { line ->
            val body = line.removePrefix(PROBE_PREFIX)
            val index = body.indexOf('=')
            if (index <= 0) null else body.substring(0, index) to body.substring(index + 1).trim()
        }
        .toMap()

    private fun String?.toBooleanCompat(): Boolean = equals("true", ignoreCase = true) || this == "1" || equals("yes", ignoreCase = true)

    private fun firstNonBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    private data class AbiInspection(
        val arch: String,
        val fatal: List<String>,
        val warnings: List<String>,
        val raw: String
    )

    private fun inspectAbis(rootfsDir: File, idaHome: String): AbiInspection {
        val supported = Build.SUPPORTED_ABIS.toList()
        val fatal = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val raw = StringBuilder("deviceAbis=${supported.joinToString()}\n")
        // Match IdaProotRuntime's selected shell, but read symlinks in the guest namespace.
        val shell = listOf("/bin/bash", "/usr/bin/bash", "/bin/sh", "/usr/bin/sh")
            .firstOrNull { File(rootfsDir, it.trimStart('/')).exists() } ?: "/bin/sh"
        var shellArch = "unknown"
        val idaBinary = listOf("ida", "ida64", "idat", "idat64")
            .map { "$idaHome/$it" }
            .firstOrNull { resolveGuestFile(rootfsDir, it)?.exists() == true }
        val binaries = listOfNotNull(shell, idaBinary, "$idaHome/ida-mcp")
        for (guestPath in binaries.distinct()) {
            val file = resolveGuestFile(rootfsDir, guestPath)
            val header = file?.let { ElfAbi.read(it) }
            raw.append("elf[$guestPath]=${header?.description ?: "unknown/non-ELF/unreadable"}\n")
            if (guestPath == shell && header?.abi != null) shellArch = header.abi
            when {
                header?.abi == null || supported.isEmpty() ->
                    warnings += "无法确认 $guestPath 的 ABI 兼容性（${header?.description ?: "非 ELF、缺失或不可读"}）；未将 uname 当作 rootfs 架构"
                !ElfAbi.supported(header, supported) ->
                    fatal += "$guestPath 的 ELF ABI ${header.abi} 与设备支持的 ABI ${supported.joinToString()} 不兼容；proot 本身不提供 CPU 翻译"
            }
        }
        return AbiInspection(shellArch, fatal, warnings, raw.toString())
    }

    /** Absolute symlinks in an imported Linux tree refer to that tree, not Android's /. */
    private fun resolveGuestFile(rootfsDir: File, guestPath: String): File? = runCatching {
        val pending = ArrayDeque(guestPath.split('/').filter { it.isNotEmpty() })
        val resolved = mutableListOf<String>()
        var links = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            if (part == ".") continue
            if (part == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
                continue
            }
            val file = File(rootfsDir, (resolved + part).joinToString("/"))
            if (Files.isSymbolicLink(file.toPath())) {
                if (++links > 40) return@runCatching null
                val destination = Os.readlink(file.absolutePath)
                if (destination.startsWith('/')) resolved.clear()
                destination.split('/').filter { it.isNotEmpty() }.asReversed().forEach { pending.addFirst(it) }
            } else {
                resolved += part
            }
        }
        File(rootfsDir, resolved.joinToString("/"))
    }.getOrNull()

    private fun failureReport(
        idaHome: String,
        fatal: List<String>,
        warnings: List<String> = emptyList(),
        raw: String,
        arch: String = "unknown"
    ): ValidationReport = ValidationReport(
        arch = arch,
        ida = IdaStatus(path = idaHome),
        idaMcp = FileStatus(path = "$idaHome/ida-mcp"),
        usageDoc = FileStatus(path = "$idaHome/IDA_MCP_MCPC_USAGE.md"),
        fatal = fatal,
        warnings = warnings,
        rawOutput = raw
    )

    private fun writeActiveValidateLogIfNeeded(rootfsDir: File, report: ValidationReport) {
        if (rootfsDir.absoluteFile == paths.rootfsDir.absoluteFile) {
            paths.validateLog.apply {
                parentFile?.mkdirs()
                writeText(JsonFormats.pretty.encodeToString(report))
            }
        }
    }

    private companion object {
        const val PROBE_PREFIX = "__IDADROID__"
    }
}
