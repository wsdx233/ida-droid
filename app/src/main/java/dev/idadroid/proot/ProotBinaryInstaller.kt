package dev.idadroid.proot

import android.content.Context
import android.os.Build
import android.system.Os
import dev.idadroid.env.EnvironmentPaths
import java.io.File
import java.io.FileOutputStream

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
        paths.prootBinDir.mkdirs()

        if (target.isFile && target.length() > 0L) {
            chmodExecutable(target)
            return InstallResult(target, assetName = "existing", copied = false)
        }

        val assetName = findProotAsset()
            ?: error(
                "proot binary asset is missing. Expected one of: " +
                    prootAssetCandidates().joinToString()
            )

        appContext.assets.open(assetName).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        chmodExecutable(target)
        return InstallResult(target, assetName, copied = true)
    }

    fun isInstalled(): Boolean = paths.prootBinary.isFile && paths.prootBinary.canExecute()

    private fun findProotAsset(): String? = prootAssetCandidates().firstOrNull { candidate ->
        runCatching { appContext.assets.open(candidate).close() }.isSuccess
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
        runCatching { Os.chmod(file.absolutePath, 493) } // 0755
        file.setExecutable(true, false)
        file.setReadable(true, false)
        file.setWritable(true, true)
    }
}
