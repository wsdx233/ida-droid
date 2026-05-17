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
    private val uploadHostDir: File get() = File(paths.rootfsDir, "root/pi_workspace/.upload")

    suspend fun readDraft(uri: Uri, maxBytes: Long = 50L * 1024L * 1024L): DraftAttachment = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val mime = appContext.contentResolver.getType(uri).orEmpty().ifBlank { guessMimeType(name) ?: "application/octet-stream" }
        val input = appContext.contentResolver.openInputStream(uri) ?: error("无法打开附件：$uri")
        val bytes = input.use { stream ->
            val data = stream.readBytes()
            require(data.size <= maxBytes) { "附件过大：${data.size / 1024 / 1024} MiB，当前限制 ${maxBytes / 1024 / 1024} MiB" }
            data
        }
        DraftAttachment(safeFileName(name), mime, bytes, mime.startsWith("image/") || isImagePath(name))
    }

    suspend fun storeAttachments(attachments: List<DraftAttachment>): List<StoredAttachment> = withContext(Dispatchers.IO) {
        uploadHostDir.mkdirs()
        attachments.map { draft ->
            val file = uniqueFile(uploadHostDir, draft.name)
            file.writeBytes(draft.bytes)
            val prootPath = "/root/pi_workspace/.upload/${file.name}"
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

    suspend fun expandFileReferencesForPrompt(message: String, cwd: String = "/root/pi_workspace"): ExpandedPrompt = withContext(Dispatchers.IO) {
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
            if (mime.startsWith("image/") || isImagePath(file.name)) {
                images += ImagePayload(data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP), mimeType = mime.takeIf { it.startsWith("image/") } ?: "image/png")
                fileText.append("<file name=\"").append(resolved.prootPath).append("\"></file>\n")
            } else {
                fileText.append("<file name=\"").append(resolved.prootPath).append("\">\n")
                fileText.append(file.readText(Charsets.UTF_8)).append("\n</file>\n")
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

    private data class WorkspaceRef(val prootPath: String, val hostFile: File)

    private fun resolveWorkspaceReference(input: String, cwd: String): WorkspaceRef {
        val base = normalizeWorkspacePath(cwd.ifBlank { "/root/pi_workspace" })
        val raw = input.trim()
        val abs = if (raw.startsWith("/")) normalizeWorkspacePath(raw) else normalizeWorkspacePath("$base/$raw")
        if (abs != "/root/pi_workspace" && !abs.startsWith("/root/pi_workspace/")) {
            throw IllegalArgumentException("文件路径必须位于 /root/pi_workspace 内：$input")
        }
        val rel = abs.removePrefix("/root/pi_workspace").trimStart('/')
        return WorkspaceRef(abs, if (rel.isBlank()) File(paths.rootfsDir, "root/pi_workspace") else File(paths.rootfsDir, "root/pi_workspace/$rel"))
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
        return "/${parts.joinToString("/")}".trimEnd('/').ifBlank { "/root/pi_workspace" }
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
