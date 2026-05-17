package dev.idadroid.env

import android.content.Context
import java.io.File

class EnvironmentPaths private constructor(
    private val context: Context,
    val envId: String = DEFAULT_ENV_ID
) {
    val filesDir: File get() = context.filesDir
    val cacheDir: File get() = context.cacheDir

    val prootDir: File get() = File(filesDir, "proot")
    val prootBinDir: File get() = File(prootDir, "bin")
    val prootBinary: File get() = File(prootBinDir, "proot")

    val envsDir: File get() = File(filesDir, "envs")
    val envDir: File get() = File(envsDir, envId)
    val rootfsDir: File get() = File(envDir, "rootfs")
    val hostTmpDir: File get() = File(envDir, "tmp")
    val metadataFile: File get() = File(envDir, "metadata.json")
    val readyMarker: File get() = File(envDir, ".setup-complete")
    val extractMarker: File get() = File(envDir, ".rootfs-extracted")
    val importLog: File get() = File(envDir, "import.log")
    val validateLog: File get() = File(envDir, "validate.log")
    val logsDir: File get() = File(envDir, "logs")

    fun stagingEnvDir(timestampMillis: Long = System.currentTimeMillis()): File =
        File(envsDir, ".importing-$envId-$timestampMillis")

    fun stagingRootfsDir(stagingEnvDir: File): File = File(stagingEnvDir, "rootfs")
    fun stagingTmpDir(stagingEnvDir: File): File = File(stagingEnvDir, "tmp")
    fun stagingMetadataFile(stagingEnvDir: File): File = File(stagingEnvDir, "metadata.json")
    fun stagingImportLog(stagingEnvDir: File): File = File(stagingEnvDir, "import.log")
    fun stagingValidateLog(stagingEnvDir: File): File = File(stagingEnvDir, "validate.log")

    companion object {
        const val DEFAULT_ENV_ID = "default"

        fun of(context: Context, envId: String = DEFAULT_ENV_ID): EnvironmentPaths =
            EnvironmentPaths(context.applicationContext, envId)
    }
}
