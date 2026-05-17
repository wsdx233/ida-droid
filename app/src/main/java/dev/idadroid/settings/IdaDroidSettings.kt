package dev.idadroid.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-level settings inspired by r2droid's SharedPreferences-backed SettingsManager.
 * Keep this small for now: Phase 3 only needs GUI/VNC connection defaults.
 */
class IdaDroidSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _vncSettings = MutableStateFlow(readVncSettings())
    val vncSettings: StateFlow<VncSettings> = _vncSettings.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in VNC_KEYS) _vncSettings.value = readVncSettings()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun updateVncPort(port: Int) {
        prefs.edit { putInt(KEY_VNC_PORT, port.coerceIn(1, 65535)) }
    }

    fun updateVncPassword(password: String) {
        prefs.edit { putString(KEY_VNC_PASSWORD, sanitizeSingleLine(password)) }
    }

    fun updateVncGeometry(geometry: String) {
        prefs.edit { putString(KEY_VNC_GEOMETRY, sanitizeGeometry(geometry)) }
    }

    fun updateVncDepth(depth: Int) {
        prefs.edit { putInt(KEY_VNC_DEPTH, if (depth in setOf(16, 24, 32)) depth else DEFAULT_VNC_DEPTH) }
    }

    fun updateVncDisplay(display: Int) {
        prefs.edit { putInt(KEY_VNC_DISPLAY, display.coerceIn(1, 99)) }
    }

    fun updateStopGuiOnAppExit(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_STOP_GUI_ON_APP_EXIT, enabled) }
    }

    fun resetVncDefaults() {
        prefs.edit {
            putInt(KEY_VNC_PORT, DEFAULT_VNC_PORT)
            putString(KEY_VNC_PASSWORD, DEFAULT_VNC_PASSWORD)
            putString(KEY_VNC_GEOMETRY, DEFAULT_VNC_GEOMETRY)
            putInt(KEY_VNC_DEPTH, DEFAULT_VNC_DEPTH)
            putInt(KEY_VNC_DISPLAY, DEFAULT_VNC_DISPLAY)
            putBoolean(KEY_STOP_GUI_ON_APP_EXIT, DEFAULT_STOP_GUI_ON_APP_EXIT)
        }
        _vncSettings.value = readVncSettings()
    }

    private fun readVncSettings(): VncSettings = VncSettings(
        port = prefs.getInt(KEY_VNC_PORT, DEFAULT_VNC_PORT).coerceIn(1, 65535),
        password = prefs.getString(KEY_VNC_PASSWORD, DEFAULT_VNC_PASSWORD)?.let(::sanitizeSingleLine) ?: DEFAULT_VNC_PASSWORD,
        geometry = sanitizeGeometry(prefs.getString(KEY_VNC_GEOMETRY, DEFAULT_VNC_GEOMETRY) ?: DEFAULT_VNC_GEOMETRY),
        depth = prefs.getInt(KEY_VNC_DEPTH, DEFAULT_VNC_DEPTH).let { if (it in setOf(16, 24, 32)) it else DEFAULT_VNC_DEPTH },
        display = prefs.getInt(KEY_VNC_DISPLAY, DEFAULT_VNC_DISPLAY).coerceIn(1, 99),
        stopGuiOnAppExit = prefs.getBoolean(KEY_STOP_GUI_ON_APP_EXIT, DEFAULT_STOP_GUI_ON_APP_EXIT)
    )

    private fun sanitizeSingleLine(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

    private fun sanitizeGeometry(value: String): String {
        val trimmed = sanitizeSingleLine(value)
        return if (GEOMETRY_REGEX.matches(trimmed)) trimmed else DEFAULT_VNC_GEOMETRY
    }

    companion object {
        private const val PREFS_NAME = "idadroid_settings"

        private const val KEY_VNC_PORT = "vnc_port"
        private const val KEY_VNC_PASSWORD = "vnc_password"
        private const val KEY_VNC_GEOMETRY = "vnc_geometry"
        private const val KEY_VNC_DEPTH = "vnc_depth"
        private const val KEY_VNC_DISPLAY = "vnc_display"
        private const val KEY_STOP_GUI_ON_APP_EXIT = "stop_gui_on_app_exit"

        const val DEFAULT_VNC_PORT = 5901
        const val DEFAULT_VNC_PASSWORD = "Zbt7nba5"
        const val DEFAULT_VNC_GEOMETRY = "1280x800"
        const val DEFAULT_VNC_DEPTH = 24
        const val DEFAULT_VNC_DISPLAY = 1
        const val DEFAULT_STOP_GUI_ON_APP_EXIT = false

        private val GEOMETRY_REGEX = Regex("^[1-9][0-9]{2,4}x[1-9][0-9]{2,4}$")
        private val VNC_KEYS = setOf(
            KEY_VNC_PORT,
            KEY_VNC_PASSWORD,
            KEY_VNC_GEOMETRY,
            KEY_VNC_DEPTH,
            KEY_VNC_DISPLAY,
            KEY_STOP_GUI_ON_APP_EXIT
        )
    }
}

data class VncSettings(
    val port: Int = IdaDroidSettings.DEFAULT_VNC_PORT,
    val password: String = IdaDroidSettings.DEFAULT_VNC_PASSWORD,
    val geometry: String = IdaDroidSettings.DEFAULT_VNC_GEOMETRY,
    val depth: Int = IdaDroidSettings.DEFAULT_VNC_DEPTH,
    val display: Int = IdaDroidSettings.DEFAULT_VNC_DISPLAY,
    val stopGuiOnAppExit: Boolean = IdaDroidSettings.DEFAULT_STOP_GUI_ON_APP_EXIT
)
