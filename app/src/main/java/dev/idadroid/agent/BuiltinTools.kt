package dev.idadroid.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * run_shell 工具 — 在 proot 容器内执行 shell 命令。
 *
 * 这是 AI 的核心工具，覆盖 mcpc、deep-index、文件操作等所有命令行能力。
 * 超时上限 600 秒，防止 AI 恶意设置超大值。
 */
class ShellTool : AbstractAgentTool() {
    override val name = "run_shell"
    override val description = """在 IDA 工作区的 Linux 环境中执行 shell 命令。
可用于：
- 运行 mcpc 命令操作 IDA Pro (如: mcpc call decompile_function '{"name":"main"}')
- 运行 deep-index 工具链 (如: deep-index index /root/pi_workspace/challenge)
- 执行文件操作 (file, strings, readelf, objdump 等)
- 运行 Python 脚本
命令在 proot 容器内执行，工作区路径为 /root/pi_workspace。
重要：mcpc 是操作 IDA 的核心工具，格式为 `mcpc call <tool_name> '<json_args>'`。""".trimIndent()

    override val parameters: JsonObject = ToolSchema.objectSchema(
        properties = buildJsonObject {
            put("command", ToolSchema.string("要执行的 shell 命令"))
            put("timeout", ToolSchema.integer("超时时间（秒），最大 600", 120))
        },
        required = listOf("command")
    )

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        val command = args.getString("command") ?: return ToolOutcome.error("缺少 command 参数")
        val timeoutSec = (args.getInt("timeout") ?: 120).coerceIn(1, MAX_SHELL_TIMEOUT_SEC)

        return try {
            val output = context.proot.executeCommandWithTimeout(command, timeoutSec.toLong() * 1000)
            ToolOutcome.success(output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.error("命令执行失败: ${e.message ?: e::class.simpleName ?: "未知错误"}")
        }
    }

    companion object {
        const val MAX_SHELL_TIMEOUT_SEC = 600
    }
}

/**
 * read_file 工具 — 读取工作区文件内容。
 *
 * 安全策略：路径遍历保护，文件必须在工作区内。
 * 性能：流式读取前 maxBytes+1024 字节，避免大文件 OOM。
 */
class ReadFileTool : AbstractAgentTool() {
    override val name = "read_file"
    override val description = "读取文件内容。支持文本和二进制文件（二进制以 hex 显示）。路径可在工作区 /root/pi_workspace 或 MCP 文件传输目录 /root/.mcp-transfer 内；也接受宿主路径（如 /data/user/0/.../rootfs/root/.mcp-transfer/xxx，将自动映射为容器内路径）。"
    override val parameters: JsonObject = ToolSchema.objectSchema(
        properties = buildJsonObject {
            put("path", ToolSchema.string("文件路径（工作区或 /root/.mcp-transfer 内，相对或绝对；也支持宿主形态路径）"))
            put("max_bytes", ToolSchema.integer("最大读取字节数", 65536))
        },
        required = listOf("path")
    )

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        val path = args.getString("path") ?: return ToolOutcome.error("缺少 path 参数")
        val maxBytes = args.getInt("max_bytes") ?: 65536

        val file = try {
            context.resolveReadableFile(path)
        } catch (e: SecurityException) {
            return ToolOutcome.error(e.message ?: "路径越界")
        }
        if (!file.exists()) return ToolOutcome.error("文件不存在: $path")
        if (!file.isFile) return ToolOutcome.error("不是文件: $path")

        val fileSize = file.length().toInt()
        val actualSize = minOf(fileSize, maxBytes)

        // 流式读取前 maxBytes+1024 字节，避免大文件 OOM
        val readSize = minOf(fileSize, maxBytes + 1024)
        val bytes = ByteArray(readSize)
        file.inputStream().use { it.read(bytes) }
        val isBinary = bytes.take(minOf(1024, readSize)).any { it == 0.toByte() }
        return if (isBinary) {
            val hex = bytes.take(actualSize).joinToString("") { "%02x".format(it) }
            ToolOutcome.success("二进制文件 ($path), 大小=$fileSize bytes, 显示前 $actualSize bytes:\n$hex")
        } else {
            val text = String(bytes, 0, actualSize, Charsets.UTF_8)
            if (fileSize > maxBytes) {
                ToolOutcome.success("$text\n...(文件被截断，总大小 $fileSize bytes)")
            } else {
                ToolOutcome.success(text)
            }
        }
    }
}

/**
 * write_file 工具 — 写入工作区文件。
 *
 * 安全策略：路径遍历保护，文件必须在工作区内。
 */
class WriteFileTool : AbstractAgentTool() {
    override val name = "write_file"
    override val description = "将内容写入工作区内文件。如果文件已存在会被覆盖。路径必须在 /root/pi_workspace 内。"
    override val parameters: JsonObject = ToolSchema.objectSchema(
        properties = buildJsonObject {
            put("path", ToolSchema.string("文件路径（相对于工作区或绝对路径）"))
            put("content", ToolSchema.string("要写入的内容"))
        },
        required = listOf("path", "content")
    )

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        val path = args.getString("path") ?: return ToolOutcome.error("缺少 path 参数")
        val content = args.getString("content") ?: return ToolOutcome.error("缺少 content 参数")

        val file = try {
            context.resolveWorkspaceFile(path)
        } catch (e: SecurityException) {
            return ToolOutcome.error(e.message ?: "路径越界")
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        return ToolOutcome.success("已写入 $path (${content.length} 字符)")
    }
}

/**
 * list_dir 工具 — 列出工作区目录内容。
 *
 * 安全策略：路径遍历保护，目录必须在工作区内。
 */
class ListDirTool : AbstractAgentTool() {
    override val name = "list_dir"
    override val description = "列出目录的文件和子目录。路径可在工作区 /root/pi_workspace 或 MCP 文件传输目录 /root/.mcp-transfer 内（也接受宿主形态路径）。"
    override val parameters: JsonObject = ToolSchema.objectSchema(
        properties = buildJsonObject {
            put("path", ToolSchema.string("目录路径（工作区或 /root/.mcp-transfer 内）"))
        },
        required = listOf("path")
    )

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        val path = args.getString("path") ?: return ToolOutcome.error("缺少 path 参数")

        val file = try {
            context.resolveReadableFile(path)
        } catch (e: SecurityException) {
            return ToolOutcome.error(e.message ?: "路径越界")
        }
        if (!file.exists()) return ToolOutcome.error("目录不存在: $path")
        if (!file.isDirectory) return ToolOutcome.error("不是目录: $path")

        val entries = file.listFiles()?.sortedBy { it.name } ?: return ToolOutcome.success("空目录")
        val output = buildString {
            append("$path/ (${entries.size} items)\n")
            entries.forEach { entry ->
                val type = if (entry.isDirectory) "d" else "f"
                val size = if (entry.isFile) "${entry.length()}" else "-"
                append("[$type] $size\t${entry.name}\n")
            }
        }
        return ToolOutcome.success(output)
    }
}

/**
 * search_files 工具 — 在工作区内搜索文件内容（grep）或文件名（find）。
 *
 * AI 常需要搜索代码/文本中的关键词，此工具封装 grep 和 find。
 */
class SearchFilesTool : AbstractAgentTool() {
    override val name = "search_files"
    override val description = """在工作区内搜索文件内容或文件名。
模式：
- mode="content": grep -rn 搜索文件内容（类似 grep -rn "pattern" path/）
- mode="filename": find 搜索文件名（类似 find path/ -name "*pattern*"）
默认搜索 content 模式。结果按文件分组，每行格式 文件:行号:内容。""".trimIndent()
    override val parameters: JsonObject = ToolSchema.objectSchema(
        properties = buildJsonObject {
            put("pattern", ToolSchema.string("搜索模式（grep 正则或 find 通配符）"))
            put("path", ToolSchema.string("搜索起始目录（默认 .）"))
            put("mode", ToolSchema.string("搜索模式", enum = listOf("content", "filename")))
            put("max_results", ToolSchema.integer("最大结果行数", 50))
        },
        required = listOf("pattern")
    )

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        val pattern = args.getString("pattern") ?: return ToolOutcome.error("缺少 pattern 参数")
        val path = args.getString("path") ?: "."
        val mode = args.getString("mode") ?: "content"
        val maxResults = args.getInt("max_results") ?: 50

        // 转义 shell 单引号 — 防止 pattern 中的单引号导致命令注入
        val safePattern = pattern.replace("'", "'\"'\"'")
        // 容器内看不到宿主 /data/user/0 路径，先映射为 guest 路径（如 /root/.mcp-transfer/xxx）
        val safePath = context.toGuestPath(path).replace("'", "'\"'\"'")

        val command = when (mode) {
            "filename" -> "find '$safePath' -name '*${safePattern}*' 2>/dev/null | head -${maxResults}"
            else -> "grep -rn -- '$safePattern' '$safePath' 2>/dev/null | head -${maxResults}"
        }

        return try {
            val output = context.proot.executeCommandWithTimeout(command, 30_000)
            if (output.isBlank()) ToolOutcome.success("未找到匹配结果")
            else ToolOutcome.success(output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.error("搜索失败: ${e.message ?: e::class.simpleName ?: "未知错误"}")
        }
    }
}

/**
 * delete_file 工具 — 删除工作区内的文件或空目录。
 *
 * 安全策略：
 * - 路径遍历保护
 * - 不允许删除工作区根目录
 * - 不允许递归删除（rm -r），仅删除单文件或空目录
 */
class DeleteFileTool : AbstractAgentTool() {
    override val name = "delete_file"
    override val description = "删除工作区内的文件或空目录。不能删除目录树（不递归）。路径必须在 /root/pi_workspace 内。"
    override val parameters: JsonObject = ToolSchema.objectSchema(
        properties = buildJsonObject {
            put("path", ToolSchema.string("要删除的文件或空目录路径"))
        },
        required = listOf("path")
    )

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        val path = args.getString("path") ?: return ToolOutcome.error("缺少 path 参数")

        val file = try {
            context.resolveWorkspaceFile(path)
        } catch (e: SecurityException) {
            return ToolOutcome.error(e.message ?: "路径越界")
        }
        if (!file.exists()) return ToolOutcome.error("文件不存在: $path")

        // 不允许删除工作区根目录
        if (file.canonicalPath == context.workspaceDir.canonicalPath) {
            return ToolOutcome.error("不允许删除工作区根目录")
        }

        return try {
            if (file.isDirectory) {
                if (file.listFiles()?.isNotEmpty() == true) {
                    return ToolOutcome.error("目录非空，无法删除（不支持递归删除）")
                }
                file.delete()
            } else {
                file.delete()
            }
            ToolOutcome.success("已删除: $path")
        } catch (e: Exception) {
            ToolOutcome.error("删除失败: ${e.message ?: e::class.simpleName ?: "未知错误"}")
        }
    }
}

/**
 * file_info 工具 — 获取文件或目录的元数据。
 *
 * 提供 stat 信息：大小、权限、修改时间、类型等。
 */
class FileInfoTool : AbstractAgentTool() {
    override val name = "file_info"
    override val description = "获取文件或目录的元数据（大小、类型、权限、修改时间等）。路径可在工作区或 /root/.mcp-transfer 内（也接受宿主形态路径）。"
    override val parameters: JsonObject = ToolSchema.objectSchema(
        properties = buildJsonObject {
            put("path", ToolSchema.string("文件或目录路径"))
        },
        required = listOf("path")
    )

    override suspend fun execute(argsJson: String, context: ToolContext): ToolOutcome {
        val args = parseArgs(argsJson) ?: return ToolOutcome.error("参数解析失败: 无效 JSON")
        val path = args.getString("path") ?: return ToolOutcome.error("缺少 path 参数")

        val file = try {
            context.resolveReadableFile(path)
        } catch (e: SecurityException) {
            return ToolOutcome.error(e.message ?: "路径越界")
        }
        if (!file.exists()) return ToolOutcome.error("文件不存在: $path")

        val output = buildString {
            append("路径(容器内): ${context.toGuestPath(file.canonicalPath)}\n")
            append("类型: ${if (file.isDirectory) "目录" else "文件"}\n")
            append("大小: ${file.length()} bytes\n")
            append("可读: ${file.canRead()}\n")
            append("可写: ${file.canWrite()}\n")
            append("可执行: ${file.canExecute()}\n")
            append("修改时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(file.lastModified()))}\n")
            if (file.isFile) {
                val ext = file.extension.lowercase()
                append("扩展名: $ext\n")
                // 用 run_shell 获取 file 命令的输出
                val fileType = runCatching {
                    // 转义单引号，防止 transfer 文件名里的特殊字符注入 shell（CWE-78）
                    val fileGuestPath = context.toGuestPath(file.canonicalPath).replace("'", "'\"'\"'")
                    context.proot.executeCommandWithTimeout("file '$fileGuestPath'", 5_000)
                }.getOrDefault("未知")
                append("文件类型: $fileType")
            } else if (file.isDirectory) {
                val count = file.listFiles()?.size ?: 0
                append("条目数: $count")
            }
        }
        return ToolOutcome.success(output)
    }
}
