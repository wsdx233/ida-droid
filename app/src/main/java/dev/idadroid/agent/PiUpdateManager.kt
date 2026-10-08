package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import dev.idadroid.proot.ProotBinaryInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

internal const val PI_NPM_PACKAGE = "@earendil-works/pi-coding-agent"
private val PI_VERSION = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")

internal fun isNewerPiVersion(installed: String, available: String): Boolean {
    val current = PI_VERSION.matchEntire(installed) ?: error("无法识别已安装的 PI 版本")
    val latest = PI_VERSION.matchEntire(available) ?: error("无法识别 PI 最新版本")
    for (index in 1..3) {
        val comparison = latest.groupValues[index].toLong().compareTo(current.groupValues[index].toLong())
        if (comparison != 0) return comparison > 0
    }
    val currentPre = current.groupValues[4]
    val latestPre = latest.groupValues[4]
    if (currentPre.isEmpty()) return false
    if (latestPre.isEmpty()) return true
    val left = latestPre.split('.')
    val right = currentPre.split('.')
    for (index in 0 until minOf(left.size, right.size)) {
        val l = left[index]
        val r = right[index]
        val ln = l.toLongOrNull()
        val rn = r.toLongOrNull()
        val comparison = when {
            ln != null && rn != null -> ln.compareTo(rn)
            ln != null -> -1
            rn != null -> 1
            else -> l.compareTo(r)
        }
        if (comparison != 0) return comparison > 0
    }
    return left.size > right.size
}

data class PiUpdateInfo(val installedVersion: String, val availableVersion: String)
data class PiUpdateState(
    val checking: Boolean = false,
    val updating: Boolean = false,
    val available: PiUpdateInfo? = null,
    val message: String? = null
)

/** One process owner: rotation does not cancel npm or lose an update prompt. */
class PiUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val paths = EnvironmentPaths.of(appContext)
    private val runtime = IdaProotRuntime(appContext, paths)
    private val preferences = appContext.getSharedPreferences("pi-updates", Context.MODE_PRIVATE)
    private val ignoredVersionKey = "ignored-version-${paths.envId}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PiUpdateState())
    val state = _state.asStateFlow()

    fun checkAtStartup() {
        if (_state.value.checking || _state.value.updating || _state.value.available != null) return
        _state.update { it.copy(checking = true, message = null) }
        scope.launch {
            var installed: String? = null
            try {
                installed = withContext(Dispatchers.IO) {
                    ProotBinaryInstaller(appContext, paths).ensureInstalled()
                    installedVersion()
                }
                val latest = withContext(Dispatchers.IO) { latestVersion() }
                val update = if (isNewerPiVersion(installed, latest) && preferences.getString(ignoredVersionKey, null) != latest) {
                    PiUpdateInfo(installed, latest)
                } else null
                _state.update { it.copy(checking = false, available = update) }
            } catch (error: CancellationException) {
                _state.update { it.copy(checking = false) }
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(checking = false, message = if (installed == null)
                    "无法检测容器内 PI 版本；不会修改已安装的 PI"
                else "PI 更新检测失败，继续使用已安装版本 $installed") }
            }
        }
    }

    fun dismissUpdate(ignoreVersion: Boolean = false) {
        val update = _state.value.available ?: return
        if (_state.value.updating) return
        if (ignoreVersion) preferences.edit().putString(ignoredVersionKey, update.availableVersion).apply()
        _state.update { it.copy(available = null) }
    }

    fun update() {
        val update = _state.value.available ?: return
        if (_state.value.updating) return
        _state.update { it.copy(updating = true, message = null) }
        scope.launch {
            try {
                val result = runtime.run(piUpdateInstallScript(update.availableVersion), timeoutMs = 300_000)
                check(!result.timedOut && result.exitCode == 0) { "PI installation failed" }
                check(installedVersion() == update.availableVersion) { "PI version did not change" }
                _state.value = PiUpdateState(message = "PI 已更新到 ${update.availableVersion}；已打开的 PI 会话重新连接后生效")
            } catch (error: CancellationException) {
                _state.update { it.copy(updating = false) }
                throw error
            } catch (_: Exception) {
                // npm only touches a new prefix; failures leave the original launcher/package usable.
                _state.value = PiUpdateState(message = "PI 更新失败，继续使用已安装版本 ${update.installedVersion}")
            }
        }
    }

    fun clearMessage(message: String) {
        _state.update { if (it.message == message) it.copy(message = null) else it }
    }

    private suspend fun installedVersion(): String {
        val result = runtime.run("pi --version", timeoutMs = 15_000)
        check(!result.timedOut && result.exitCode == 0) { "PI version check failed" }
        return result.stdout.lineSequence().map(String::trim).firstOrNull { PI_VERSION.matches(it) }
            ?: result.stderr.lineSequence().map(String::trim).firstOrNull { PI_VERSION.matches(it) }
            ?: error("PI version not found")
    }

    private fun latestVersion(): String {
        val connection = URL("https://registry.npmjs.org/$PI_NPM_PACKAGE/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            check(connection.responseCode == 200) { "PI registry unavailable" }
            val metadata = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as JsonObject
            return metadata.getValue("version").jsonPrimitive.content.also { require(PI_VERSION.matches(it)) }
        } finally {
            connection.disconnect()
        }
    }
}

/** Stage and verify before replacing the launcher; never npm-install over the running package. */
internal fun piUpdateInstallScript(version: String): String {
    require(PI_VERSION.matches(version)) { "Invalid PI version" }
    return """
        set -eu
        launcher="${'$'}(command -v pi)"
        case "${'$'}launcher" in /*) ;; *) exit 1 ;; esac
        [ -f "${'$'}launcher" ]
        storage=/root/.local/share/idadroid/pi
        mkdir -p "${'$'}storage"
        release="${'$'}(mktemp -d "${'$'}storage/release-XXXXXX")"
        link="${'$'}launcher.idadroid-${'$'}${'$'}"
        committed=0
        cleanup() {
          rm -f "${'$'}link"
          if [ "${'$'}committed" = 0 ]; then rm -rf "${'$'}release"; fi
        }
        trap cleanup EXIT
        trap 'exit 1' HUP INT TERM
        npm install --prefix "${'$'}release" '$PI_NPM_PACKAGE@$version' --engine-strict --no-audit --no-fund --loglevel=error
        candidate="${'$'}release/node_modules/.bin/pi"
        [ "${'$'}("${'$'}candidate" --version 2>&1)" = '$version' ]
        ln -s "${'$'}candidate" "${'$'}link"
        mv -f "${'$'}link" "${'$'}launcher"
        committed=1
    """.trimIndent()
}
