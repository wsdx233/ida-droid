package dev.idadroid.terminal

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

class TerminalLogStore(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val logFile: File get() = File(paths.logsDir, LOG_FILE_NAME)

    fun append(message: String) = synchronized(lock) {
        paths.logsDir.mkdirs()
        logFile.appendText("${Instant.now()}  $message\n")
        trimIfNeeded()
    }

    fun readTail(maxBytes: Int = DEFAULT_READ_BYTES): String = synchronized(lock) {
        if (!logFile.isFile) return@synchronized ""
        val bytes = logFile.readBytes()
        val start = (bytes.size - maxBytes.coerceAtLeast(1)).coerceAtLeast(0)
        bytes.copyOfRange(start, bytes.size).toString(StandardCharsets.UTF_8)
    }

    fun clear() = synchronized(lock) {
        if (logFile.exists()) logFile.delete()
    }

    fun absolutePath(): String = logFile.absolutePath

    private fun trimIfNeeded() {
        if (!logFile.isFile || logFile.length() <= MAX_BYTES) return
        val bytes = logFile.readBytes()
        val keepStart = (bytes.size - MAX_BYTES).coerceAtLeast(0)
        var start = keepStart
        while (start < bytes.lastIndex && bytes[start] != '\n'.code.toByte()) start++
        if (start < bytes.lastIndex) start++
        logFile.writeBytes(bytes.copyOfRange(start, bytes.size))
    }

    companion object {
        const val LOG_FILE_NAME = "terminal.log"
        private const val MAX_BYTES = 256 * 1024
        private const val DEFAULT_READ_BYTES = 64 * 1024
        private val lock = Any()
    }
}
