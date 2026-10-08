package dev.idadroid.proot

import android.content.Context
import android.os.Build
import android.system.Os
import dev.idadroid.env.EnvironmentPaths
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ProotBinaryInstaller(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext

    data class InstallResult(
        val binary: File,
        val assetName: String,
        val copied: Boolean
    )

    fun ensureInstalled(): InstallResult {
        val target = paths.prootBinary
        val lock = installLocks.getOrPut(target.canonicalPath) { Any() }
        return synchronized(lock) {
            check(paths.prootBinDir.isDirectory || paths.prootBinDir.mkdirs()) {
                "Cannot create proot directory: ${paths.prootBinDir.absolutePath}"
            }
            val assetName = findProotAsset()
            val expected = appContext.assets.open(assetName).use { sha256(it) }
            if (target.isFile && runCatching { target.inputStream().use { sha256(it) } }.getOrNull()
                ?.contentEquals(expected) == true
            ) {
                chmodExecutable(target)
                return@synchronized InstallResult(target, assetName, copied = false)
            }

            val temp = File.createTempFile("proot-", ".tmp", paths.prootBinDir)
            try {
                appContext.assets.open(assetName).use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                check(temp.inputStream().use { sha256(it) }.contentEquals(expected)) {
                    "Incomplete proot asset copy: $assetName"
                }
                chmodExecutable(temp)
                // Both paths are on the same filesystem; never expose the in-progress copy.
                Os.rename(temp.absolutePath, target.absolutePath)
                InstallResult(target, assetName, copied = true)
            } finally {
                temp.delete()
            }
        }
    }

    fun isInstalled(): Boolean = runCatching {
        val target = paths.prootBinary
        val assetName = findProotAsset()
        target.isFile && target.canExecute() && appContext.assets.open(assetName).use { sha256(it) }
            .contentEquals(target.inputStream().use { sha256(it) })
    }.getOrDefault(false)

    private fun findProotAsset(): String {
        val supported = Build.SUPPORTED_ABIS.toList()
        val rejected = mutableListOf<String>()
        for (candidate in prootAssetCandidates()) {
            val header = try {
                appContext.assets.open(candidate).use { ElfAbi.read(it) }
            } catch (_: java.io.FileNotFoundException) {
                continue
            }
            if (header != null && ElfAbi.supported(header, supported)) return candidate
            rejected += "$candidate (${header?.description ?: "unknown/non-ELF"})"
        }
        error("No compatible proot binary asset for device ABIs ${supported.joinToString()}. " +
            "Available but incompatible: ${rejected.joinToString().ifBlank { "none" }}. " +
            "Provide an Android proot binary under proot/<supported-ABI>/proot; " +
            "proot does not translate CPU instructions by itself.")
    }

    private fun prootAssetCandidates(): List<String> {
        val abiSpecific = Build.SUPPORTED_ABIS.flatMap { abi ->
            listOf(
                "proot/$abi/proot",
                "proot-$abi",
                "proot_$abi"
            )
        }
        return (abiSpecific + "proot").distinct()
    }

    private fun chmodExecutable(file: File) {
        Os.chmod(file.absolutePath, 493) // 0755; failures must not publish an unusable copy.
        check(file.canExecute()) { "proot binary is not executable: ${file.absolutePath}" }
    }

    private fun sha256(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private companion object {
        val installLocks = ConcurrentHashMap<String, Any>()
    }
}
