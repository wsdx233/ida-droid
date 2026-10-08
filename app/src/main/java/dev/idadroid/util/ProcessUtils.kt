package dev.idadroid.util

/**
 * Utility extensions for [Process] management.
 *
 * Android's [java.lang.Process] does not expose the Java 9+ [Process.pid] API at
 * compile time, so PID retrieval must go through reflection. These helpers
 * centralise that logic so it is not duplicated across session managers.
 */
object ProcessUtils {

    /**
     * Returns the PID of this [Process], or `null` when it cannot be determined.
     *
     * The method first attempts the standard Java 9+ `pid()` method (available on
     * desktop JVMs and useful for unit tests) and falls back to Android's internal
     * `ProcessImpl.getPid()` when the former is absent.
     */
    fun pidOf(process: Process): Int? = runCatching {
        val pidMethod = runCatching { Process::class.java.getMethod("pid") }.getOrNull()
        val result = pidMethod?.invoke(process)
            ?: process::class.java.getMethod("getPid").invoke(process)
        (result as Number).toInt()
    }.getOrNull()?.takeIf { it > 0 }
}

/**
 * Convenience nullable-receiver wrapper around [ProcessUtils.pidOf].
     */
fun Process?.safePid(): Int? = this?.let { ProcessUtils.pidOf(it) }

/** Called on an IO thread; successful return means the child has exited. */
internal fun Process.terminateAndWait() {
    if (!isAlive) return
    destroy()
    if (!waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
        destroyForcibly()
        check(waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { "子进程未能停止" }
    }
}
