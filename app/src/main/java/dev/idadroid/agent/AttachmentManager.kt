package dev.idadroid.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import dev.idadroid.env.EnvironmentPaths
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DraftAttachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val isImage: Boolean
) {
    val size: Long get() = bytes.size.toLong()
    override fun equals(other: Any?): Boolean = other is DraftAttachment && name == other.name && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * (31 * name.hashCode() + mimeType.hashCode()) + bytes.contentHashCode()
}

data class StoredAttachment(
    val display: ChatAttachment,
    val prootPath: String,
    val hostFile: File,
    val isImage: Boolean,
    val mimeType: String
)

data class ExpandedPrompt(
    val message: String,
    val images: List<ImagePayload>,
    val referencedPaths: Set<String>
)

class AttachmentManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext
    private val settings = dev.idadroid.settings.IdaDroidSettings(appContext)
    private val workspaceProotPath: String get() = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH }
    private val uploadHostDir: File get() {
        val ws = workspaceProotPath
        // /root/xxx → rootfs 内；/sdcard、/storage 前缀由 proot 绑定，直接使用宿主同路径。
        if (ws.startsWith("/root/")) return File(File(paths.rootfsDir, ws.removePrefix("/").ifBlank { "root/pi_workspace" }), ".upload")
        if (ws.startsWith("/sdcard") || ws.startsWith("/storage")) return File(ws, ".upload")
        return File(paths.rootfsDir, "${ws.removePrefix("/").ifBlank { "root/pi_workspace" }}/.upload")
    }
    private val uploadProotPath: String get() = "$workspaceProotPath/.upload"

    suspend fun readDraft(uri: Uri, maxBytes: Long = 50L * 1024L * 1024L): DraftAttachment = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val mime = appContext.contentResolver.getType(uri).orEmpty().ifBlank { guessMimeType(name) ?: "application/octet-stream" }
        // 先通过 cursor 查询文件大小，避免对超大文件 readBytes 导致 OOM
        val fileSize = queryFileSize(uri)
        if (fileSize != null && fileSize > maxBytes) {
            error("附件过大：${fileSize / 1024 / 1024} MiB，当前限制 ${maxBytes / 1024 / 1024} MiB")
        }
        val input = appContext.contentResolver.openInputStream(uri) ?: error("无法打开附件：$uri")
        val bytes = input.use { stream ->
            // 限制读取长度，防止 cursor 报告的 size 不准确或缺失时 OOM
            val maxIntBytes = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (total <= maxIntBytes) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            require(total <= maxIntBytes) { "附件过大：${total / 1024 / 1024} MiB，当前限制 ${maxBytes / 1024 / 1024} MiB" }
            out.toByteArray()
        }
        DraftAttachment(safeFileName(name), mime, bytes, mime.startsWith("image/") || isImagePath(name))
    }

    suspend fun storeAttachments(attachments: List<DraftAttachment>): List<StoredAttachment> = withContext(Dispatchers.IO) {
        uploadHostDir.mkdirs()
        attachments.map { draft ->
            val file = uniqueFile(uploadHostDir, draft.name)
            file.writeBytes(draft.bytes)
            val prootPath = "$uploadProotPath/${file.name}"
            StoredAttachment(
                display = if (draft.isImage) {
                    ChatAttachment.Image(draft.name, draft.mimeType.ifBlank { "image/png" }, Base64.encodeToString(draft.bytes, Base64.NO_WRAP), path = prootPath, size = draft.size)
                } else {
                    ChatAttachment.File(draft.name, prootPath, draft.size, draft.mimeType)
                },
                prootPath = prootPath,
                hostFile = file,
                isImage = draft.isImage,
                mimeType = draft.mimeType
            )
        }
    }

    suspend fun expandFileReferencesForPrompt(message: String, cwd: String = workspaceProotPath): ExpandedPrompt = withContext(Dispatchers.IO) {
        val refs = parseFileRefs(message)
        if (refs.isEmpty()) return@withContext ExpandedPrompt(message, emptyList(), emptySet())
        val attempted = linkedSetOf<String>()
        val attached = linkedSetOf<String>()
        val images = mutableListOf<ImagePayload>()
        val fileText = StringBuilder()
        refs.forEach { rawRef ->
            val resolved = runCatching { resolveWorkspaceReference(rawRef, cwd) }.getOrNull() ?: return@forEach
            if (!attempted.add(resolved.prootPath)) return@forEach
            val file = resolved.hostFile.takeIf { it.isFile && it.canRead() } ?: return@forEach
            attached += resolved.prootPath
            val mime = guessMimeType(file.name) ?: "text/plain"
            val maxInlineBytes = 256 * 1024L  // 256 KiB 内联上限
            if (mime.startsWith("image/") || isImagePath(file.name)) {
                if (file.length() <= maxInlineBytes) {
                    images += ImagePayload(data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP), mimeType = mime.takeIf { it.startsWith("image/") } ?: "image/png")
                }
                // 修复：原代码字符串转义错误，把 if 表达式的两个分支合并进了一个
                // 巨大的字符串字面量里（并在字符串外留下裸反斜杠，导致编译失败）。
                // 这里拆成清晰的 if/else，分别拼装标签尾部。
                val tagTail = if (file.length() > maxInlineBytes) {
                    "\" size=${file.length()} (too large to inline)>"
                } else {
                    "\">"
                }
                fileText.append("<file name=\"").append(resolved.prootPath).append(tagTail).append("</file>\n")
            } else {
                fileText.append("<file name=\"").append(resolved.prootPath).append("\">\n")
                if (file.length() <= maxInlineBytes) {
                    fileText.append(file.readText(Charsets.UTF_8))
                } else {
                    fileText.append("(文件过大，${file.length()} bytes，已省略内容，请用 read_file 工具查看)")
                }
                fileText.append("\n</file>\n")
            }
        }
        ExpandedPrompt(if (fileText.isNotEmpty()) fileText.toString() + message else message, images, attached)
    }

    fun fileRef(path: String): String = if (path.any { it.isWhitespace() }) "@\"${path.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else "@$path"

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)
        }
    }.getOrNull()

    private fun queryFileSize(uri: Uri): Long? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            if (cursor.isNull(0)) return@use null
            cursor.getLong(0)
        }
    }.getOrNull()

    private data class WorkspaceRef(val prootPath: String, val hostFile: File)

    private fun resolveWorkspaceReference(input: String, cwd: String): WorkspaceRef {
        val ws = workspaceProotPath
        val base = normalizeWorkspacePath(cwd.ifBlank { ws })
        val raw = input.trim()
        val abs = if (raw.startsWith("/")) normalizeWorkspacePath(raw) else normalizeWorkspacePath("$base/$raw")
        if (abs != ws && !abs.startsWith("$ws/")) {
            throw IllegalArgumentException("文件路径必须位于 $ws 内：$input")
        }
        val rel = abs.removePrefix(ws).trimStart('/')
        val hostRoot = if (ws.startsWith("/root/")) File(paths.rootfsDir, ws.removePrefix("/")) else File(ws)
        return WorkspaceRef(abs, if (rel.isBlank()) hostRoot else File(hostRoot, rel))
    }

    private fun normalizeWorkspacePath(value: String): String {
        val parts = mutableListOf<String>()
        value.replace('\\', '/').split('/').forEach { part ->
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return "/${parts.joinToString("/")}".trimEnd('/').ifBlank { workspaceProotPath }
    }

    private fun parseFileRefs(text: String): List<String> {
        val refs = mutableListOf<String>()
        val re = Regex("(^|\\s)@(?:\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"|'([^']*)'|([^\\s]+))")
        re.findAll(text).forEach { match ->
            val doubleQuoted = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.replace(Regex("\\\\([\\\"\\\\])"), "$1")
            val singleQuoted = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
            val isQuoted = doubleQuoted != null || singleQuoted != null
            val raw = (doubleQuoted ?: singleQuoted ?: match.groupValues.getOrNull(4).orEmpty().replace(Regex("[),.;:!?，。；：！？]+$"), "")).trim()
            if (raw.isBlank() || raw.startsWith("@")) return@forEach
            if (!isQuoted && shouldSkipUnquotedAtRef(raw)) return@forEach
            refs += raw
        }
        return refs
    }

    private fun shouldSkipUnquotedAtRef(rawPath: String): Boolean {
        if (rawPath.contains("@")) return true
        val firstSegment = rawPath.substringBefore('/').removePrefix("+")
        val resourceType = firstSegment.substringAfter(':', firstSegment)
        return resourceType in androidResourceRefTypes
    }

    private fun safeFileName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
        .trim()
        .take(120)
        .ifBlank { "attachment" }

    private fun uniqueFile(dir: File, name: String): File {
        val safe = safeFileName(name)
        val dot = safe.lastIndexOf('.').takeIf { it > 0 && it < safe.lastIndex }
        val base = dot?.let { safe.substring(0, it) } ?: safe
        val ext = dot?.let { safe.substring(it) }.orEmpty()
        var candidate = File(dir, safe)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-$i$ext")
            i++
        }
        return candidate
    }

    private fun isImagePath(path: String): Boolean = Regex("\\.(png|jpe?g|gif|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(path)

    private fun guessMimeType(path: String): String? = when {
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
        path.endsWith(".gif", true) -> "image/gif"
        path.endsWith(".webp", true) -> "image/webp"
        path.endsWith(".json", true) -> "application/json"
        path.endsWith(".md", true) -> "text/markdown"
        path.endsWith(".txt", true) -> "text/plain"
        path.endsWith(".log", true) -> "text/plain"
        else -> null
    }

    private companion object {
        val androidResourceRefTypes = setOf(
            "anim", "animator", "array", "attr", "bool", "color", "dimen", "drawable", "font", "fraction", "id", "integer",
            "interpolator", "layout", "menu", "mipmap", "plurals", "raw", "string", "style", "styleable", "transition", "xml"
        )
    }
}
