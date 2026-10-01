package dev.idadroid.proot

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import dev.idadroid.env.EnvironmentPaths

/**
 * Optional DSHA proroot loader. The files are APK native libraries, never copied to filesDir:
 * Android 10+ writable application directories may be mounted noexec.
 */
internal class ProrootSupport(
    context: Context,
    private val paths: EnvironmentPaths,
    private val rootfsDir: File,
    private val hostTmpDir: File
) {
    enum class Kind { PROROOT, PROOT }
    data class Selection(val kind: Kind, val reason: String)

    private val appContext = context.applicationContext
    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)
    private var cached: Selection? = null
    private var cachedKey: String? = null
    fun select(): Selection {
        val key = cacheKey()
        if (cachedKey == key) cached?.let { return it }
        cachedKey = key
        val abi = abiSupported()
        val intact = abi && payloadIntact()
        if (!intact) {
            val candidate = Selection(Kind.PROOT, if (!abi) "proroot unsupported ABI (arm64-v8a required)" else "proroot payload missing or integrity mismatch")
            cached = candidate
            writeCached(key, candidate)
            return candidate
        }
        readCached(key)?.let { cached = it; return it }
        val failure = probe()
        val candidate = if (failure == null) Selection(Kind.PROROOT, "proroot capability probe passed")
        else Selection(Kind.PROOT, "proroot probe failed: $failure")
        cached = candidate
        writeCached(key, candidate)
        return candidate
    }

    fun invalidate() { cached = null; cachedKey = null }

    fun prefix(kind: Kind, cwd: String): MutableList<String> {
        require(kind == Kind.PROROOT) { "proroot prefix requested for $kind" }
        val command = mutableListOf<String>()
        command += File(nativeDir, LIBS[0]).absolutePath
        command += "--static-loader"
        command += listOf("-r", rootfsDir.absolutePath, "-0", "-w", cwd)
        bind(command, "/dev", null)
        bind(command, "/proc", null)
        bind(command, "/sys", null)
        bind(command, "/system", null)
        bind(command, "/apex", null)
        bind(command, "/proc/self/fd", "/dev/fd")
        bind(command, "/dev/urandom", "/dev/random")
        bind(command, hostTmpDir, "/tmp")
        bind(command, hostTmpDir, "/var/tmp")
        if (File("/storage").exists()) bind(command, "/storage", null)
        if (File("/sdcard").exists()) bind(command, "/sdcard", null)
        val shm = File(appContext.cacheDir, "proroot-shm").apply { mkdirs() }
        bind(command, shm, "/dev/shm")
        command += "--link2symlink"
        return command
    }

    fun applyEnvironment(environment: MutableMap<String, String>) {
        environment["PROROOT_TMP_DIR"] = hostTmpDir.absolutePath
        environment["PROROOT_LIB_PATH"] = File(nativeDir, LIBS[1]).absolutePath
        environment["PROROOT_LINKER_PATH"] = File(nativeDir, LIBS[2]).absolutePath
        environment["PROROOT_STUB_LOADER"] = File(nativeDir, LIBS[3]).absolutePath
    }

    fun diagnostics(): String = select().let { selection ->
        val lifecycle = if (selection.kind == Kind.PROROOT) "; no kill-on-exit descendant guarantee" else "; kill-on-exit enabled"
        "runtime=${selection.kind.name.lowercase()} (${selection.reason}$lifecycle)"
    }

    private fun bind(command: MutableList<String>, source: Any, guest: String?) {
        val file = File(source.toString())
        if (!file.exists()) return
        command += "-b"
        command += if (guest == null) file.absolutePath else "${file.absolutePath}:$guest"
    }

    private fun abiSupported(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private fun payloadIntact(): Boolean = LIBS.all { name ->
        val file = File(nativeDir, name)
        file.isFile && file.length() > 0L && sha256(file) == HASHES[name]
    }

    private fun probe(): String? {
        if (!rootfsDir.isDirectory) return "rootfs missing"
        hostTmpDir.mkdirs()
        val argv = prefix(Kind.PROROOT, "/") + listOf(
            "/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/bin:/bin", "TMPDIR=/tmp",
            "/bin/sh", "-c", "test -d /root && test -w /root && test -d /proc && test -d /tmp"
        )
        return try {
            val process = ProcessBuilder(argv)
                .directory(paths.envDir.apply { mkdirs() })
                .redirectErrorStream(true)
                .also { applyEnvironment(it.environment()) }
                .start()
            val finished = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                "timeout"
            } else if (process.exitValue() != 0) {
                "exit ${process.exitValue()}"
            } else null
        } catch (error: Throwable) {
            error.message?.take(160) ?: error.javaClass.simpleName
        }
    }

    private fun cacheKey(): String = runCatching {
        val payload = LIBS.joinToString(",") { name ->
            val file = File(nativeDir, name)
            "$name:${file.length()}:${file.lastModified()}"
        }
        "${rootfsDir.canonicalPath}:${rootfsDir.lastModified()}:${rootfsDir.length()}:$nativeDir:$payload"
    }.getOrDefault(rootfsDir.absolutePath)

    private fun readCached(key: String): Selection? {
        val file = File(paths.prootDir, CACHE_FILE)
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != "schema=1" || lines.getOrNull(1) != "key=$key") return null
        val kind = lines.firstOrNull { it.startsWith("kind=") }?.substringAfter('=') ?: return null
        val reason = lines.firstOrNull { it.startsWith("reason=") }?.substringAfter('=') ?: return null
        return when (kind) {
            "proroot" -> Selection(Kind.PROROOT, reason)
            "proot" -> Selection(Kind.PROOT, reason)
            else -> null
        }
    }

    private fun writeCached(key: String, selection: Selection) {
        runCatching {
            paths.prootDir.mkdirs()
            val file = File(paths.prootDir, CACHE_FILE)
            val temp = File(file.path + ".tmp")
            temp.writeText("schema=1\nkey=$key\nkind=${selection.kind.name.lowercase()}\nreason=${selection.reason}\n")
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    private fun sha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrDefault("")

    companion object {
        const val PROBE_TIMEOUT_MS = 4_000L
        const val CACHE_FILE = ".proroot-capability"
        val LIBS = listOf(
            "libproroot.so", "libproroot-runtime.so", "libproroot-linker.so",
            "libproroot-stub-loader.so", "libproroot-bridge.so"
        )
        val HASHES = mapOf(
            "libproroot.so" to "a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01",
            "libproroot-runtime.so" to "8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34",
            "libproroot-linker.so" to "51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee",
            "libproroot-stub-loader.so" to "06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0",
            "libproroot-bridge.so" to "1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f"
        )
    }
}
