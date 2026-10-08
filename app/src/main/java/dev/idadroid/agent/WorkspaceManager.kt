package dev.idadroid.agent

import android.content.Context
import android.webkit.MimeTypeMap
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.files.ContainerFileManager
import dev.idadroid.settings.IdaDroidSettings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Files, PI configuration and sessions all live in the selected container directory. */
class WorkspaceManager(
    context: Context,
    paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val settings = IdaDroidSettings(context.applicationContext)
    private val files = ContainerFileManager(context, paths)

    val currentWorkspacePath: String get() = settings.envSettings.value.workspacePath
    val currentWorkspaceName: String get() = currentWorkspacePath.trimEnd('/').substringAfterLast('/').ifBlank { "/" }
    val hasWorkspace: Boolean get() = currentWorkspacePath.isNotBlank()

    /** Validate before changing the common setting; failed selection leaves the old workspace intact. */
    suspend fun validateWorkspace(path: String, create: Boolean = false): String = withContext(Dispatchers.IO) {
        val normalized = IdaDroidSettings.normalizeWorkspacePath(path)
        val dir = files.rootfsFile(normalized)
        if (create && !dir.exists()) check(dir.mkdirs()) { "无法创建工作区：$normalized" }
        require(dir.isDirectory) { "工作区目录不存在：$normalized" }
        require(dir.canRead() && dir.canWrite() && dir.canExecute()) { "工作区没有读写或访问权限：$normalized" }
        checkNotNull(dir.listFiles()) { "无法读取工作区：$normalized" }
        normalized
    }

    suspend fun setWorkspace(path: String, create: Boolean = false): String {
        val normalized = validateWorkspace(path, create)
        settings.updateWorkspacePath(normalized)
        return normalized
    }

    suspend fun listFiles(relativePath: String = ""): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val relative = normalizeRelativePath(relativePath)
        val dir = workspaceFile(relative)
        require(dir.isDirectory) { "目录不存在：${guestPath(relative)}" }
        val children = dir.listFiles() ?: error("无法读取目录：${guestPath(relative)}")
        children.filter { child ->
            val childRelative = if (relative.isEmpty()) child.name else "$relative/${child.name}"
            !Files.isSymbolicLink(child.toPath()) &&
                runCatching { IdaDroidSettings.normalizeWorkspacePath(guestPath(childRelative)) }.isSuccess
        }.map { child ->
            val childRelative = if (relative.isEmpty()) child.name else "$relative/${child.name}"
            WorkspaceFileEntry(
                containerPath = guestPath(childRelative),
                name = child.name,
                relativePath = childRelative,
                isDirectory = child.isDirectory,
                mimeType = if (child.isDirectory) "inode/directory" else guessMimeType(child.name),
                size = if (child.isFile) child.length() else 0L,
                lastModified = child.lastModified()
            )
        }.sortedWith(compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name })
    }

    suspend fun readAsText(entry: WorkspaceFileEntry): String = withContext(Dispatchers.IO) {
        val file = entryFile(entry)
        require(file.length() <= 512L * 1024L) { "文件过大，仅支持插入 512 KiB 内文本；可改用路径或附件" }
        file.readText(Charsets.UTF_8)
    }

    suspend fun readAsDraftAttachment(entry: WorkspaceFileEntry): DraftAttachment = withContext(Dispatchers.IO) {
        val file = entryFile(entry)
        val maxBytes = 50 * 1024 * 1024
        require(file.length() <= maxBytes) { "附件过大，当前限制 50 MiB" }
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "附件过大，当前限制 50 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val mime = guessMimeType(file.name)
        DraftAttachment(file.name, mime, bytes, mime.startsWith("image/"))
    }

    suspend fun writeFileText(relativePath: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val relative = normalizeRelativePath(relativePath)
        require(relative.isNotEmpty()) { "文件名不能为空" }
        val file = workspaceFile(relative)
        val parent = checkNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法创建父目录：${guestPath(relative)}" }
        // Re-check after creation, so no existing symlink parent can redirect the write.
        workspaceFile(relative).writeText(content, Charsets.UTF_8)
        true
    }

    /** Navigation is relative to the selected workspace; parent at its root stays at root. */
    fun resolvePath(currentPath: String, relativePath: String): String {
        val current = normalizeRelativePath(currentPath)
        if (relativePath.isBlank() || relativePath == ".") return current
        if (relativePath == "..") return current.substringBeforeLast('/', "")
        val next = normalizeRelativePath(relativePath)
        return if (current.isEmpty()) next else "$current/$next"
    }

    private fun entryFile(entry: WorkspaceFileEntry): File {
        val relative = normalizeRelativePath(entry.relativePath)
        require(entry.containerPath == guestPath(relative)) { "工作区已切换，请刷新文件列表" }
        val file = workspaceFile(relative)
        require(file.isFile) { "文件不存在：${entry.containerPath}" }
        return file
    }

    private fun workspaceFile(relativePath: String): File {
        val root = files.rootfsFile(currentWorkspacePath)
        require(root.isDirectory) { "工作区目录不存在：$currentWorkspacePath" }
        val file = files.rootfsFile(guestPath(relativePath))
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "路径越界：$relativePath" }
        return file
    }

    private fun guestPath(relativePath: String): String =
        if (relativePath.isEmpty()) currentWorkspacePath else "${currentWorkspacePath.trimEnd('/')}/$relativePath"

    private fun normalizeRelativePath(path: String): String {
        require(!path.startsWith('/') && '\\' !in path && path.none { it.isISOControl() }) { "无效的工作区相对路径" }
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        require(".." !in parts) { "工作区路径不能包含 .." }
        return parts.joinToString("/")
    }

    private fun guessMimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: when (name.substringAfterLast('.', "").lowercase()) {
                "kt", "java", "py", "js", "ts", "md", "txt", "sh", "c", "cpp", "h", "go", "rs", "yml", "yaml", "toml", "gradle" -> "text/plain"
                else -> "application/octet-stream"
            }
}

@Serializable
data class WorkspaceFileEntry(
    val containerPath: String,
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val size: Long,
    val lastModified: Long
)

data class WorkspaceState(
    val hasWorkspace: Boolean = false,
    val workspaceName: String = "",
    val workspacePath: String = "",
    val files: List<WorkspaceFileEntry> = emptyList(),
    val currentPath: String = "",
    val loading: Boolean = false,
    val error: String? = null
)
