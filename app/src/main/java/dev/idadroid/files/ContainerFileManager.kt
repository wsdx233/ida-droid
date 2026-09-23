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

/**
 * 浏览/管理容器（proot rootfs）内文件。
 *
 * guest 路径解析规则与 PiAgentManager.workspaceHostRoot 保持一致：
 *  - /root/xxx、/xxx 等普通路径 → rootfs 内部（Android app 私有目录）
 *  - /sdcard、/storage、/mnt 前缀 → proot 启动时绑定到宿主同名路径，
 *    所以直接映射到 Android 宿主文件系统（/sdcard → /storage/emulated/0），
 *    使外部共享存储目录在文件浏览器中“默认可见可访问”。
 */
class ContainerFileManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext

    /** 由 proot 绑定到宿主同名路径的 guest 目录前缀（在 rootfs 之外）。 */
    private val externalGuestPrefixes = listOf("/sdcard", "/storage", "/mnt")

    suspend fun listFiles(path: String): List<ContainerFileEntry> = withContext(Dispatchers.IO) {
        val dir = guestFile(path)
        require(dir.isDirectory) { "目录不存在：${normalizeGuestPath(path)}" }
        dir.listFiles().orEmpty().map { file -> file.toEntry(normalizeGuestPath(path)) }
    }

    suspend fun uploadFile(destinationPath: String, uri: Uri): ContainerFileEntry = withContext(Dispatchers.IO) {
        requireReady()
        val dir = guestFile(destinationPath)
        require(dir.isDirectory) { "目标不是目录：${normalizeGuestPath(destinationPath)}" }
        val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
        val target = uniqueFile(dir, name)
        val input = appContext.contentResolver.openInputStream(uri) ?: error("无法打开文件：$uri")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
        target.toEntry(normalizeGuestPath(destinationPath))
    }

    suspend fun importInstalledApk(packageName: String, label: String, apkPath: String, destinationPath: String): ContainerFileEntry = withContext(Dispatchers.IO) {
        requireReady()
        val source = File(apkPath)
        require(source.isFile && source.canRead()) { "无法读取 APK：$apkPath" }
        val dir = guestFile(destinationPath)
        require(dir.isDirectory) { "目标不是目录：${normalizeGuestPath(destinationPath)}" }
        val target = uniqueFile(dir, "${safeFileName(label.ifBlank { packageName }).removeSuffix(".apk")}.apk")
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        target.toEntry(normalizeGuestPath(destinationPath))
    }

    suspend fun createDirectory(path: String): ContainerFileEntry = withContext(Dispatchers.IO) {
        requireReady()
        val dir = guestFile(path, mustExist = false).apply { mkdirs() }
        require(dir.isDirectory) { "无法创建目录：${normalizeGuestPath(path)}" }
        dir.toEntry(parentPath(path))
    }

    suspend fun createEmptyFile(directoryPath: String, name: String): ContainerFileEntry = withContext(Dispatchers.IO) {
        requireReady()
        val dir = guestFile(directoryPath)
        require(dir.isDirectory) { "目标不是目录：${normalizeGuestPath(directoryPath)}" }
        val target = uniqueFile(dir, name)
        target.writeBytes(ByteArray(0))
        target.toEntry(normalizeGuestPath(directoryPath))
    }

    suspend fun deleteFile(path: String) = withContext(Dispatchers.IO) {
        requireReady()
        val file = guestFile(path)
        require(file != paths.rootfsDir.canonicalFile) { "不能删除 rootfs 根目录" }
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    suspend fun fileForSharing(path: String): File = withContext(Dispatchers.IO) {
        val file = guestFile(path)
        require(file.isFile) { "文件不存在：${normalizeGuestPath(path)}" }
        // rootfs 内的文件通过 FileProvider 直接分享；
        // 外部共享存储文件（/sdcard 等）不在 provider 覆盖范围，先复制到
        // 应用缓存目录（cache-path 已声明），保证“打开/编辑”可用。
        if (!isExternalGuestPath(path)) return@withContext file
        val cacheRoot = java.io.File(appContext.cacheDir, "shared").apply { mkdirs() }
        val copy = uniqueFile(cacheRoot, file.name)
        file.inputStream().use { input -> copy.outputStream().use { output -> input.copyTo(output) } }
        copy
    }

    suspend fun saveFileAs(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val file = guestFile(path)
        require(file.isFile) { "文件不存在：${normalizeGuestPath(path)}" }
        val output = appContext.contentResolver.openOutputStream(destination, "wt") ?: error("无法写入目标文件")
        output.use { target -> file.inputStream().use { source -> source.copyTo(target) } }
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
        // 外部绑定根的父级仍停留在绑定根本身（rootfs 里没有对应的上层目录）
        if (externalGuestPrefixes.any { normalized == it }) return normalized
        return normalized.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "/").ifBlank { "/" }
    }

    fun normalizeGuestPath(path: String): String {
        val raw = path.trim().ifBlank { "/root/pi_workspace" }.replace('\\', '/')
        val absolute = if (raw.startsWith('/')) raw else "${dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH}/$raw"
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

    /** guest 路径是否指向外部绑定根（rootfs 之外的真实宿主目录）。 */
    fun isExternalGuestPath(path: String): Boolean {
        val normalized = normalizeGuestPath(path)
        return externalGuestPrefixes.any { prefix -> normalized == prefix || normalized.startsWith("$prefix/") }
    }

    private fun guestFile(path: String, mustExist: Boolean = true): File {
        requireReady()
        val normalized = normalizeGuestPath(path)
        // 外部绑定目录：proot 把宿主同名路径挂进容器，Android 侧直接访问宿主路径
        externalGuestPrefixes.firstOrNull { prefix -> normalized == prefix || normalized.startsWith("$prefix/") }?.let { prefix ->
            val hostPath = if (prefix == "/sdcard") "/storage/emulated/0" + normalized.removePrefix(prefix) else normalized
            val host = File(hostPath)
            return if (mustExist || host.exists()) host.canonicalFile else host.absoluteFile.toPath().normalize().toFile()
        }
        val root = paths.rootfsDir.canonicalFile
        val rel = normalized.trimStart('/')
        val raw = if (rel.isBlank()) root else File(root, rel)
        val file = if (mustExist || raw.exists()) raw.canonicalFile else raw.absoluteFile.toPath().normalize().toFile()
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "路径越界：$path" }
        return file
    }

    /** 把宿主文件转成条目；guestPath 是文件所在目录的 guest 路径（避免 canonical 反向映射丢失 /sdcard 前缀）。 */
    private fun File.toEntry(parentGuestPath: String): ContainerFileEntry {
        val parent = normalizeGuestPath(parentGuestPath)
        val guestPath = if (parent == "/") "/$name" else "$parent/$name"
        return ContainerFileEntry(
            name = name,
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
