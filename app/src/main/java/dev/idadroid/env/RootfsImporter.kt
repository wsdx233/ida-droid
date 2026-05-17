package dev.idadroid.env

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import dev.idadroid.data.EnvironmentMetadata
import dev.idadroid.data.ValidationReport
import dev.idadroid.proot.ProotBinaryInstaller
import dev.idadroid.util.JsonFormats
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream

class RootfsImporter(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context),
    private val prootInstaller: ProotBinaryInstaller = ProotBinaryInstaller(context, paths),
    private val configurator: RootfsConfigurator = RootfsConfigurator(context),
    private val validator: RootfsValidator = RootfsValidator(context, paths),
    private val workspaceMaterializer: PiWorkspaceMaterializer = PiWorkspaceMaterializer()
) {
    private val appContext = context.applicationContext
    private val importMutex = Mutex()

    fun importFromUri(uri: Uri): Flow<ImportProgress> = flow {
        importMutex.withLock {
            val logs = mutableListOf<String>()
            var stagingEnv: File? = null
            var stagingLog: File? = null
            var extractionComplete = false
            var currentSourceName = ""
            var currentFormatLabel = "unknown"

            suspend fun report(
                stage: ImportStage,
                progress: Float?,
                message: String,
                currentFile: String = "",
                error: String? = null
            ) {
                val line = buildString {
                    append(Instant.now().toString())
                    append(" [")
                    append(stage.name)
                    append("] ")
                    append(message)
                    if (currentFile.isNotBlank()) append(" :: ").append(currentFile)
                    if (error != null) append(" :: ").append(error)
                }
                logs += line
                if (logs.size > MAX_LOG_LINES) logs.removeAt(0)
                stagingLog?.apply {
                    parentFile?.mkdirs()
                    appendText(line + "\n")
                }
                emit(
                    ImportProgress(
                        stage = stage,
                        progress = progress,
                        message = message,
                        currentFile = currentFile,
                        logs = logs.toList(),
                        error = error
                    )
                )
            }

            try {
                val sourceName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "rootfs.tar"
                currentSourceName = sourceName
                val archiveSize = querySize(uri)
                val format = RootfsArchiveFormat.detect(sourceName)
                currentFormatLabel = format.label
                report(ImportStage.Preflight, 0.01f, "准备导入 $sourceName (${format.label})")
                checkUsableSpace(archiveSize)?.let { warning ->
                    report(ImportStage.Preflight, 0.02f, warning)
                }

                stagingEnv = paths.stagingEnvDir()
                stagingEnv.deleteRecursively()
                stagingEnv.mkdirs()
                stagingLog = paths.stagingImportLog(stagingEnv)
                val stagingRootfs = paths.stagingRootfsDir(stagingEnv)
                val stagingTmp = paths.stagingTmpDir(stagingEnv)
                stagingRootfs.mkdirs()
                stagingTmp.mkdirs()

                report(ImportStage.Extracting, 0.05f, "开始流式解包 rootfs")
                extractArchive(uri, sourceName, archiveSize, stagingRootfs) { progress, entryName, entries ->
                    val bounded = progress?.let { 0.05f + it.coerceIn(0f, 1f) * 0.55f }
                    val message = if (entries > 0) "正在解包 ($entries entries)" else "正在解包"
                    report(ImportStage.Extracting, bounded, message, entryName)
                }
                extractionComplete = true

                report(ImportStage.Configuring, 0.64f, "写入 resolv.conf/hosts/tmp/fips 配置")
                configurator.configure(stagingRootfs, stagingTmp)

                report(ImportStage.InstallingProot, 0.70f, "安装 proot 运行时")
                val installResult = prootInstaller.ensureInstalled()
                report(
                    ImportStage.InstallingProot,
                    0.74f,
                    if (installResult.copied) "proot 已从 asset 复制" else "proot 已存在",
                    installResult.binary.absolutePath
                )

                report(ImportStage.Validating, 0.76f, "在 proot 内运行 rootfs 验证")
                val validation = validator.validate(stagingRootfs, stagingTmp)
                paths.stagingValidateLog(stagingEnv).apply {
                    parentFile?.mkdirs()
                    writeText(JsonFormats.pretty.encodeToString(validation))
                }
                val validationMessage = if (validation.ok) {
                    "验证通过"
                } else {
                    "验证发现 ${validation.fatal.size} 个 fatal 问题"
                }
                report(ImportStage.Validating, 0.86f, validationMessage)

                report(ImportStage.MaterializingWorkspace, 0.88f, "创建 /root/pi_workspace 与 IDAdroid 脚本")
                workspaceMaterializer.materialize(stagingRootfs)

                val metadata = EnvironmentMetadata(
                    envId = paths.envId,
                    sourceName = sourceName,
                    importedAt = Instant.now().toString(),
                    rootfsStripComponents = 0,
                    rootfsFormat = format.label,
                    validation = validation
                )
                writeStagingMetadata(stagingEnv, metadata)

                if (!validation.ok) {
                    File(stagingEnv, ".rootfs-extracted").writeText("completedAt=${Instant.now()}\n")
                    File(stagingEnv, ".setup-complete").delete()
                    val preservedAt = preserveFailedStaging(stagingEnv)
                    stagingLog = null
                    stagingEnv = null
                    report(
                        ImportStage.Error,
                        null,
                        "rootfs 验证失败，容器已保留，可打开调试终端",
                        currentFile = preservedAt,
                        error = validation.fatal.joinToString("；").ifBlank { "validation failed" }
                    )
                    return@withLock
                }
                File(stagingEnv, ".rootfs-extracted").writeText("completedAt=${Instant.now()}\n")
                File(stagingEnv, ".setup-complete").writeText("completedAt=${Instant.now()}\n")

                report(ImportStage.Activating, 0.94f, "原子切换为 active 环境")
                atomicActivate(stagingEnv, paths.envDir)
                stagingEnv = null

                report(ImportStage.Done, 1.0f, "rootfs 导入完成，环境已 ready")
            } catch (t: Throwable) {
                stagingLog = null
                val reason = t.message ?: t::class.java.simpleName
                val preservedAt = stagingEnv
                    ?.takeIf { extractionComplete && paths.stagingRootfsDir(it).isDirectory }
                    ?.let { failedEnv ->
                        writeStagingMetadata(
                            failedEnv,
                            EnvironmentMetadata(
                                envId = paths.envId,
                                sourceName = currentSourceName,
                                importedAt = Instant.now().toString(),
                                rootfsFormat = currentFormatLabel,
                                validation = ValidationReport(
                                    ok = false,
                                    fatal = listOf(reason),
                                    rawOutput = t.stackTraceToString()
                                )
                            )
                        )
                        File(failedEnv, ".setup-complete").delete()
                        preserveFailedStaging(failedEnv)
                    }
                if (preservedAt != null) {
                    stagingEnv = null
                } else {
                    stagingEnv?.deleteRecursively()
                }
                report(
                    ImportStage.Error,
                    null,
                    if (preservedAt != null) "导入失败，容器已保留，可打开调试终端" else "导入失败，已保留旧环境",
                    currentFile = preservedAt.orEmpty(),
                    error = reason
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun extractArchive(
        uri: Uri,
        sourceName: String,
        archiveSize: Long?,
        rootfsDir: File,
        onEntry: suspend (progress: Float?, entryName: String, entryCount: Int) -> Unit
    ) {
        val rawInput = appContext.contentResolver.openInputStream(uri)
            ?: error("无法打开 rootfs archive: $uri")
        rawInput.use { input ->
            val countingInput = CountingInputStream(BufferedInputStream(input))
            val archiveInput = wrapArchiveInput(sourceName, countingInput)
            TarArchiveInputStream(archiveInput).use { tarInput ->
                val rootCanonical = rootfsDir.canonicalFile.toPath()
                var entryCount = 0
                var lastReportBytes = 0L
                var entry: TarArchiveEntry?
                while (tarInput.nextEntry.also { entry = it } != null) {
                    val currentEntry = entry ?: continue
                    val normalizedName = normalizeEntryName(currentEntry.name) ?: continue
                    entryCount += 1
                    val outputFile = File(rootfsDir, normalizedName)
                    ensureInsideRoot(rootCanonical, outputFile, currentEntry.name)

                    when {
                        currentEntry.isDirectory -> {
                            outputFile.mkdirs()
                            setFilePermissions(outputFile, currentEntry.mode)
                        }
                        currentEntry.isSymbolicLink -> handleSymlink(outputFile, currentEntry.linkName.orEmpty())
                        currentEntry.isLink -> handleHardLink(rootfsDir, outputFile, currentEntry.linkName.orEmpty())
                        currentEntry.isFile -> {
                            handleRegularFile(tarInput, outputFile)
                            setFilePermissions(outputFile, currentEntry.mode)
                        }
                        else -> {
                            // Device nodes/FIFOs are intentionally not materialized in app-private storage.
                        }
                    }

                    if (countingInput.bytesRead - lastReportBytes >= REPORT_BYTES || entryCount % 128 == 0) {
                        lastReportBytes = countingInput.bytesRead
                        onEntry(progressFor(countingInput.bytesRead, archiveSize), normalizedName, entryCount)
                    }
                }
                onEntry(1f, "", entryCount)
            }
        }
    }

    private fun wrapArchiveInput(sourceName: String, input: InputStream): InputStream {
        val lower = sourceName.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> GzipCompressorInputStream(input)
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> XZInputStream(input)
            lower.endsWith(".tar") -> input
            else -> error("不支持的 rootfs archive 格式：$sourceName")
        }
    }

    private fun normalizeEntryName(rawName: String): String? {
        val trimmed = rawName.trim().replace('\\', '/')
        if (trimmed.isBlank() || trimmed.indexOf('\u0000') >= 0) return null
        val noLeadingSlash = trimmed.trimStart('/')
        val withoutDotPrefix = generateSequence(noLeadingSlash) { value ->
            if (value.startsWith("./")) value.removePrefix("./") else null
        }.last()
        if (withoutDotPrefix.isBlank()) return null
        val parts = withoutDotPrefix.split('/').filter { it.isNotBlank() && it != "." }
        if (parts.any { it == ".." }) {
            throw SecurityException("拒绝 tar path traversal entry: $rawName")
        }
        return parts.joinToString("/").takeIf { it.isNotBlank() }
    }

    private fun ensureInsideRoot(rootCanonical: java.nio.file.Path, outputFile: File, rawName: String) {
        val outputPath = outputFile.canonicalFile.toPath()
        if (!outputPath.startsWith(rootCanonical)) {
            throw SecurityException("拒绝写出 rootfs 目录的 entry: $rawName")
        }
    }

    private fun handleSymlink(linkFile: File, targetPath: String) {
        linkFile.parentFile?.mkdirs()
        deletePathIfExists(linkFile)
        try {
            Os.symlink(targetPath, linkFile.absolutePath)
        } catch (e: ErrnoException) {
            throw IllegalStateException("创建 symlink 失败: ${linkFile.path} -> $targetPath", e)
        }
    }

    private fun handleHardLink(rootfsDir: File, linkFile: File, targetPath: String) {
        linkFile.parentFile?.mkdirs()
        deletePathIfExists(linkFile)
        val normalizedTarget = targetPath.removePrefix("/")
        val targetFile = File(rootfsDir, normalizedTarget)
        val linkParent = linkFile.parentFile ?: rootfsDir
        val symlinkTarget = runCatching {
            if (targetPath.startsWith("/")) "/$normalizedTarget" else targetFile.relativeTo(linkParent).path
        }.getOrDefault(targetPath)
        Os.symlink(symlinkTarget, linkFile.absolutePath)
    }

    private fun handleRegularFile(tarInput: TarArchiveInputStream, outputFile: File) {
        if (outputFile.exists() && outputFile.isDirectory) outputFile.deleteRecursively()
        outputFile.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = tarInput.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
        }
    }

    private fun setFilePermissions(file: File, mode: Int) {
        var permissions = mode and 0b111_111_111
        if (permissions <= 0) return
        permissions = if (file.isDirectory) permissions or 448 else permissions or 384 // +0700 or +0600
        runCatching { Os.chmod(file.absolutePath, permissions) }
    }

    private fun deletePathIfExists(file: File) {
        runCatching { Files.deleteIfExists(file.toPath()) }
            .onFailure {
                if (file.exists()) {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            }
    }

    private fun progressFor(bytesRead: Long, totalBytes: Long?): Float? {
        val total = totalBytes?.takeIf { it > 0L } ?: return null
        return (bytesRead.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun writeStagingMetadata(stagingEnv: File, metadata: EnvironmentMetadata) {
        paths.stagingMetadataFile(stagingEnv).apply {
            parentFile?.mkdirs()
            writeText(JsonFormats.pretty.encodeToString(metadata))
        }
    }

    /**
     * Preserve a fully-extracted but not-ready rootfs for debugging.
     * If no active env exists, promote it to default without .setup-complete so the UI can open
     * a debug terminal. If a previous env exists, keep it untouched and move this one aside.
     */
    private fun preserveFailedStaging(stagingEnv: File): String {
        File(stagingEnv, ".setup-complete").delete()
        paths.envsDir.mkdirs()
        return if (!paths.rootfsDir.isDirectory) {
            paths.envDir.deleteRecursively()
            if (!stagingEnv.renameTo(paths.envDir)) {
                error("无法保留失败环境到 ${paths.envDir.absolutePath}")
            }
            paths.envDir.absolutePath
        } else {
            val failedDir = File(paths.envsDir, ".failed-import-${paths.envId}-${System.currentTimeMillis()}")
            if (!stagingEnv.renameTo(failedDir)) {
                error("无法保留失败环境到 ${failedDir.absolutePath}")
            }
            failedDir.absolutePath
        }
    }

    private fun atomicActivate(stagingEnv: File, activeEnv: File) {
        val envsDir = paths.envsDir.apply { mkdirs() }
        val backup = File(envsDir, "${activeEnv.name}.previous-${System.currentTimeMillis()}")
        if (backup.exists()) backup.deleteRecursively()

        if (activeEnv.exists()) {
            if (!activeEnv.renameTo(backup)) {
                error("无法备份旧环境: ${activeEnv.absolutePath}")
            }
        }
        if (!stagingEnv.renameTo(activeEnv)) {
            if (backup.exists()) backup.renameTo(activeEnv)
            error("无法激活新环境: ${stagingEnv.absolutePath}")
        }
        backup.deleteRecursively()
    }

    private fun checkUsableSpace(archiveSize: Long?): String? {
        val size = archiveSize?.takeIf { it > 0L } ?: return "无法获取 archive 大小；请确认私有目录有足够空间。"
        val recommended = (size * 2.5).toLong()
        val usable = paths.filesDir.usableSpace
        return if (usable < recommended) {
            "可用空间可能不足：建议至少 ${recommended / MIB} MiB，当前约 ${usable / MIB} MiB。"
        } else null
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val value = cursor.getLong(0)
            value.takeIf { it > 0L }
        }
    }.getOrNull()

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value != -1) bytesRead += 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) bytesRead += count.toLong()
            return count
        }
    }

    private enum class RootfsArchiveFormat(val label: String) {
        Tar("tar"),
        TarGz("tar.gz"),
        TarXz("tar.xz");

        companion object {
            fun detect(name: String): RootfsArchiveFormat {
                val lower = name.lowercase()
                return when {
                    lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TarGz
                    lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> TarXz
                    lower.endsWith(".tar") -> Tar
                    else -> error("不支持的 rootfs archive 格式：$name")
                }
            }
        }
    }

    private companion object {
        const val MAX_LOG_LINES = 160
        const val REPORT_BYTES = 256L * 1024L
        const val MIB = 1024L * 1024L
    }
}
