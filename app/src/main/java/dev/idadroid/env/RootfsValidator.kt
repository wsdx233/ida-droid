package dev.idadroid.env

import android.content.Context
import dev.idadroid.data.CommandStatus
import dev.idadroid.data.FileStatus
import dev.idadroid.data.IdaStatus
import dev.idadroid.data.ValidationReport
import dev.idadroid.data.VncStatus
import dev.idadroid.proot.IdaProotRuntime
import dev.idadroid.util.JsonFormats
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

class RootfsValidator(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext

    suspend fun validate(
        rootfsDir: File = paths.rootfsDir,
        hostTmpDir: File = paths.hostTmpDir
    ): ValidationReport = withContext(Dispatchers.IO) {
        if (!rootfsDir.isDirectory) {
            return@withContext ValidationReport(
                fatal = listOf("rootfs 目录不存在：${rootfsDir.absolutePath}"),
                rawOutput = "rootfs directory missing"
            )
        }

        val runtime = IdaProotRuntime(appContext, rootfsDir = rootfsDir, hostTmpDir = hostTmpDir)
        val script = validationProbeScript()
        val result = runtime.run(script, timeoutMs = 120_000)
        val raw = buildString {
            appendLine("exitCode=${result.exitCode}")
            appendLine("--- stdout ---")
            append(result.stdout)
            appendLine()
            appendLine("--- stderr ---")
            append(result.stderr)
        }

        if (result.exitCode != 0 && result.stdout.lines().none { it.startsWith(PROBE_PREFIX) }) {
            return@withContext ValidationReport(
                fatal = listOf("无法进入 proot 或验证脚本失败，exit=${result.exitCode}"),
                warnings = result.stderr.lineSequence().filter { it.isNotBlank() }.take(8).toList(),
                rawOutput = raw
            ).also { writeActiveValidateLogIfNeeded(rootfsDir, it) }
        }

        val fields = parseProbe(result.stdout)
        buildReport(fields, raw).also { writeActiveValidateLogIfNeeded(rootfsDir, it) }
    }

    private fun buildReport(fields: Map<String, String>, raw: String): ValidationReport {
        val arch = fields["arch"].orEmpty()
        val homeWritable = fields["homeWritable"].toBooleanCompat()

        val idaBinary = fields["idaBinary"].orEmpty()
        val ida = IdaStatus(
            exists = fields["idaDir"].toBooleanCompat(),
            binary = idaBinary,
            binaryInfo = fields["idaBinaryInfo"].orEmpty()
        )
        val idaMcp = FileStatus(
            path = "/root/ida-pro-9.3/ida-mcp",
            exists = fields["idaMcp"].toBooleanCompat(),
            executable = fields["idaMcpExecutable"].toBooleanCompat()
        )
        val usageDoc = FileStatus(
            path = "/root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md",
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
            if (!ida.exists) add("缺少 /root/ida-pro-9.3")
            if (!idaMcp.exists) add("缺少 /root/ida-pro-9.3/ida-mcp")
            if (idaMcp.exists && !idaMcp.executable) add("/root/ida-pro-9.3/ida-mcp 不可执行")
            if (!pi.exists) add("PATH 中找不到 pi")
            if (!node.exists) add("PATH 中找不到 node")
        }
        val warnings = buildList {
            if (ida.binary.isBlank()) add("未找到 IDA GUI/terminal binary 候选：ida/ida64/idat/idat64")
            if (!usageDoc.exists) add("缺少 IDA_MCP_MCPC_USAGE.md，agent 无法自动学习 mcpc/ida-mcp 用法")
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

    private fun validationProbeScript(): String = """
        set +e
        export HOME=/root
        export NVM_DIR=/root/.nvm
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${'$'}PATH
        if [ -s "${'$'}NVM_DIR/nvm.sh" ]; then . "${'$'}NVM_DIR/nvm.sh" >/dev/null 2>&1 || true; fi
        for d in /root/.nvm/versions/node/*/bin /root/.npm-global/bin /root/.local/bin /root/bin; do
          if [ -d "${'$'}d" ]; then PATH="${'$'}d:${'$'}PATH"; fi
        done
        export PATH
        probe() { printf '${PROBE_PREFIX}%s=%s\n' "${'$'}1" "${'$'}2"; }
        cmd_path() { command -v "${'$'}1" 2>/dev/null || true; }
        first_existing() {
          for p in "${'$'}@"; do
            if [ -e "${'$'}p" ]; then printf '%s' "${'$'}p"; return 0; fi
          done
          return 0
        }
        first_line() { "${'$'}@" 2>/dev/null | head -n 1 | tr '\r\n' ' ' || true; }

        probe arch "${'$'}(uname -m 2>/dev/null || true)"
        if [ -d /root ] && [ -w /root ]; then probe homeWritable true; else probe homeWritable false; fi
        if [ -d /root/ida-pro-9.3 ]; then probe idaDir true; else probe idaDir false; fi
        ida_bin="${'$'}(first_existing /root/ida-pro-9.3/ida /root/ida-pro-9.3/ida64 /root/ida-pro-9.3/idat /root/ida-pro-9.3/idat64)"
        probe idaBinary "${'$'}ida_bin"
        if [ -n "${'$'}ida_bin" ] && command -v file >/dev/null 2>&1; then probe idaBinaryInfo "${'$'}(file "${'$'}ida_bin" 2>/dev/null | head -n 1)"; else probe idaBinaryInfo ""; fi
        if [ -e /root/ida-pro-9.3/ida-mcp ]; then probe idaMcp true; else probe idaMcp false; fi
        if [ -x /root/ida-pro-9.3/ida-mcp ]; then probe idaMcpExecutable true; else probe idaMcpExecutable false; fi
        if [ -f /root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md ]; then probe usageDoc true; else probe usageDoc false; fi

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
