package dev.idadroid.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.idadroid.env.EnvironmentPaths
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContainerFileEntry(
    val name: String,
    val path: String,
    val type: String,
    val size: Long,
    val modifiedAt: String
)

class ContainerFileManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext

    suspend fun listFiles(path: String): List<ContainerFileEntry> = withContext(Dispatchers.IO) {
        val dir = guestFile(path)
        require(dir.isDirectory) { "目录不存在：${normalizeGuestPath(path)}" }
        dir.listFiles().orEmpty().map { file -> file.toEntry() }
    }

    suspend fun uploadFile(destinationPath: String, uri: Uri): ContainerFileEntry = withContext(Dispatchers.IO) {
        requireReady()
        val dir = guestFile(destinationPath)
        require(dir.isDirectory) { "目标不是目录：${normalizeGuestPath(destinationPath)}" }
        val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
        val target = uniqueFile(dir, name)
        val input = appContext.contentResolver.openInputStream(uri) ?: error("无法打开文件：$uri")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        target.toEntry()
    }

    suspend fun importInstalledApk(packageName: String, label: String, apkPath: String, destinationPath: String): ContainerFileEntry = withContext(Dispatchers.IO) {
        requireReady()
        val source = File(apkPath)
        require(source.isFile && source.canRead()) { "无法读取 APK：$apkPath" }
        val dir = guestFile(destinationPath)
        require(dir.isDirectory) { "目标不是目录：${normalizeGuestPath(destinationPath)}" }
        val target = uniqueFile(dir, "${safeFileName(label.ifBlank { packageName }).removeSuffix(".apk")}.apk")
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        target.toEntry()
    }

    suspend fun createDirectory(path: String): ContainerFileEntry = withContext(Dispatchers.IO) {
        requireReady()
        val dir = guestFile(path, mustExist = false).apply { mkdirs() }
        require(dir.isDirectory) { "无法创建目录：${normalizeGuestPath(path)}" }
        dir.toEntry()
    }

    suspend fun createEmptyFile(directoryPath: String, name: String): ContainerFileEntry = withContext(Dispatchers.IO) {
        requireReady()
        val dir = guestFile(directoryPath)
        require(dir.isDirectory) { "目标不是目录：${normalizeGuestPath(directoryPath)}" }
        val target = uniqueFile(dir, name)
        target.writeBytes(ByteArray(0))
        target.toEntry()
    }

    suspend fun deleteFile(path: String) = withContext(Dispatchers.IO) {
        val file = guestFile(path)
        require(file != paths.rootfsDir.canonicalFile) { "不能删除 rootfs 根目录" }
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    suspend fun readFileText(path: String, maxBytes: Long = 512L * 1024L): String = withContext(Dispatchers.IO) {
        val file = guestFile(path)
        require(file.isFile) { "文件不存在：${normalizeGuestPath(path)}" }
        require(file.length() <= maxBytes) { "文件过大：${file.length() / 1024} KiB，仅支持预览 ${maxBytes / 1024} KiB 内文本" }
        file.readText(Charsets.UTF_8)
    }

    fun parentPath(path: String): String {
        val normalized = normalizeGuestPath(path)
        if (normalized == "/") return "/"
        return normalized.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "/").ifBlank { "/" }
    }

    fun normalizeGuestPath(path: String): String {
        val raw = path.trim().ifBlank { "/root/pi_workspace" }.replace('\\', '/')
        val absolute = if (raw.startsWith('/')) raw else "/root/pi_workspace/$raw"
        val parts = mutableListOf<String>()
        absolute.split('/').forEach { part ->
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return "/${parts.joinToString("/")}".trimEnd('/').ifBlank { "/" }
    }

    private fun guestFile(path: String, mustExist: Boolean = true): File {
        requireReady()
        val root = paths.rootfsDir.canonicalFile
        val normalized = normalizeGuestPath(path)
        val rel = normalized.trimStart('/')
        val raw = if (rel.isBlank()) root else File(root, rel)
        val file = if (mustExist || raw.exists()) raw.canonicalFile else raw.absoluteFile.toPath().normalize().toFile()
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "路径越界：$path" }
        return file
    }

    private fun File.toEntry(): ContainerFileEntry {
        val root = paths.rootfsDir.canonicalFile
        val canonical = canonicalFile
        val rel = canonical.relativeTo(root).path.replace('\\', '/')
        val guestPath = if (rel.isBlank() || rel == ".") "/" else "/$rel"
        return ContainerFileEntry(
            name = if (guestPath == "/") "/" else name,
            path = guestPath,
            type = if (isDirectory) "directory" else "file",
            size = if (isFile) length() else 0L,
            modifiedAt = Instant.ofEpochMilli(lastModified()).toString()
        )
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)
        }
    }.getOrNull()

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

    private fun safeFileName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
        .trim()
        .take(120)
        .ifBlank { "upload" }

    private fun requireReady() {
        require(paths.readyMarker.isFile && paths.rootfsDir.isDirectory) { "rootfs 尚未 ready，请先导入并验证环境" }
    }
}
