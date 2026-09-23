package dev.idadroid.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-level settings inspired by r2droid's SharedPreferences-backed SettingsManager.
 *
 * Centralizes all user-configurable options:
 * - GUI/VNC connection defaults
 * - IDA MCP HTTP server settings
 * - Terminal appearance
 * - Agent defaults
 * - App appearance (dark/light theme)
 * - Environment paths (IDA home, workspace)
 */
class IdaDroidSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _vncSettings = MutableStateFlow(readVncSettings())
    val vncSettings: StateFlow<VncSettings> = _vncSettings.asStateFlow()

    private val _mcpSettings = MutableStateFlow(readMcpSettings())
    val mcpSettings: StateFlow<McpSettings> = _mcpSettings.asStateFlow()

    private val _terminalSettings = MutableStateFlow(readTerminalSettings())
    val terminalSettings: StateFlow<TerminalSettings> = _terminalSettings.asStateFlow()

    private val _agentSettings = MutableStateFlow(readAgentSettings())
    val agentSettings: StateFlow<AgentSettings> = _agentSettings.asStateFlow()

    private val _appearanceSettings = MutableStateFlow(readAppearanceSettings())
    val appearanceSettings: StateFlow<AppearanceSettings> = _appearanceSettings.asStateFlow()

    private val _envSettings = MutableStateFlow(readEnvSettings())
    val envSettings: StateFlow<EnvSettings> = _envSettings.asStateFlow()

    private val _floatingWindowEnabled = MutableStateFlow(prefs.getBoolean(KEY_FLOATING_WINDOW, false))
    val floatingWindowEnabled: StateFlow<Boolean> = _floatingWindowEnabled.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            in VNC_KEYS -> _vncSettings.value = readVncSettings()
            in MCP_KEYS -> _mcpSettings.value = readMcpSettings()
            in TERMINAL_KEYS -> _terminalSettings.value = readTerminalSettings()
            in AGENT_KEYS -> _agentSettings.value = readAgentSettings()
            in APPEARANCE_KEYS -> _appearanceSettings.value = readAppearanceSettings()
            in ENV_KEYS -> _envSettings.value = readEnvSettings()
            KEY_FLOATING_WINDOW -> _floatingWindowEnabled.value = prefs.getBoolean(KEY_FLOATING_WINDOW, false)
        }
    }

    init {
        // Generate a random VNC password on first install if the default is still in use.
        ensureRandomPasswordIfFirstRun()
        // ensureRandomPasswordIfFirstRun 刚写入 prefs 的随机密码不会触发刚注册的
        // listener 重放，直接刷新内存值，避免首次 VNC 启动仍使用 LEGACY 密码。
        _vncSettings.value = readVncSettings()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    // ==================== VNC Settings ====================

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
            putString(KEY_VNC_PASSWORD, generateRandomPassword())
            putString(KEY_VNC_GEOMETRY, DEFAULT_VNC_GEOMETRY)
            putInt(KEY_VNC_DEPTH, DEFAULT_VNC_DEPTH)
            putInt(KEY_VNC_DISPLAY, DEFAULT_VNC_DISPLAY)
            putBoolean(KEY_STOP_GUI_ON_APP_EXIT, DEFAULT_STOP_GUI_ON_APP_EXIT)
        }
        _vncSettings.value = readVncSettings()
    }

    // ==================== MCP Settings ====================

    fun updateMcpPort(port: Int) {
        prefs.edit { putInt(KEY_MCP_PORT, port.coerceIn(1, 65535)) }
    }

    fun updateMcpBindHost(host: String) {
        prefs.edit { putString(KEY_MCP_BIND_HOST, sanitizeHost(host)) }
    }

    fun updateMcpAllowOrigin(origin: String) {
        prefs.edit { putString(KEY_MCP_ALLOW_ORIGIN, sanitizeSingleLine(origin.replace('\n', ','))) }
    }

    fun updateMcpStateless(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_MCP_STATELESS, enabled) }
    }

    fun updateMcpSessionKeepAlive(secs: Int) {
        prefs.edit { putInt(KEY_MCP_SESSION_KEEPALIVE, secs.coerceIn(0, 86_400)) }
    }

    fun updateMcpSseKeepAlive(secs: Int) {
        prefs.edit { putInt(KEY_MCP_SSE_KEEPALIVE, secs.coerceIn(0, 300)) }
    }

    fun updateMcpAutoRestart(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_MCP_AUTO_RESTART, enabled) }
    }

    fun resetMcpDefaults() {
        prefs.edit {
            putInt(KEY_MCP_PORT, DEFAULT_MCP_PORT)
            putString(KEY_MCP_BIND_HOST, DEFAULT_MCP_BIND_HOST)
            putString(KEY_MCP_ALLOW_ORIGIN, DEFAULT_MCP_ALLOW_ORIGIN)
            putBoolean(KEY_MCP_STATELESS, DEFAULT_MCP_STATELESS)
            putInt(KEY_MCP_SESSION_KEEPALIVE, DEFAULT_MCP_SESSION_KEEPALIVE)
            putInt(KEY_MCP_SSE_KEEPALIVE, DEFAULT_MCP_SSE_KEEPALIVE)
            putBoolean(KEY_MCP_AUTO_RESTART, DEFAULT_MCP_AUTO_RESTART)
        }
        _mcpSettings.value = readMcpSettings()
    }

    // ==================== Terminal Settings ====================

    fun updateTerminalFontSize(sp: Int) {
        prefs.edit { putInt(KEY_TERMINAL_FONT_SIZE, sp.coerceIn(6, 32)) }
    }

    fun updateTerminalInitialCwd(cwd: String) {
        prefs.edit { putString(KEY_TERMINAL_INITIAL_CWD, sanitizeSingleLine(cwd)) }
    }

    fun updateTerminalColorScheme(scheme: String) {
        prefs.edit { putString(KEY_TERMINAL_COLOR_SCHEME, scheme) }
    }

    fun resetTerminalDefaults() {
        prefs.edit {
            putInt(KEY_TERMINAL_FONT_SIZE, DEFAULT_TERMINAL_FONT_SIZE)
            putString(KEY_TERMINAL_INITIAL_CWD, DEFAULT_TERMINAL_INITIAL_CWD)
            putString(KEY_TERMINAL_COLOR_SCHEME, DEFAULT_TERMINAL_COLOR_SCHEME)
        }
        _terminalSettings.value = readTerminalSettings()
    }

    // ==================== Agent Settings ====================

    fun updateAgentDefaultThinking(level: String) {
        prefs.edit { putString(KEY_AGENT_DEFAULT_THINKING, sanitizeSingleLine(level)) }
    }

    fun updateAgentAutoCompaction(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AGENT_AUTO_COMPACTION, enabled) }
    }

    fun updateAgentPromptTimeoutSecs(secs: Int) {
        prefs.edit { putInt(KEY_AGENT_PROMPT_TIMEOUT, secs.coerceIn(30, 3600)) }
    }

    fun resetAgentDefaults() {
        prefs.edit {
            putString(KEY_AGENT_DEFAULT_THINKING, DEFAULT_AGENT_THINKING)
            putBoolean(KEY_AGENT_AUTO_COMPACTION, DEFAULT_AGENT_AUTO_COMPACTION)
            putInt(KEY_AGENT_PROMPT_TIMEOUT, DEFAULT_AGENT_PROMPT_TIMEOUT)
        }
        _agentSettings.value = readAgentSettings()
    }

    // ==================== Appearance Settings ====================

    fun updateThemeMode(mode: String) {
        val valid = if (mode in setOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)) mode else THEME_SYSTEM
        prefs.edit { putString(KEY_THEME_MODE, valid) }
    }

    fun updateDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
    }

    // ==================== Environment Settings ====================

    fun updateIdaHome(path: String) {
        prefs.edit { putString(KEY_IDA_HOME, sanitizeSingleLine(path).ifBlank { DEFAULT_IDA_HOME }) }
    }

    fun updateWorkspacePath(path: String) {
        prefs.edit { putString(KEY_WORKSPACE_PATH, sanitizeSingleLine(path).ifBlank { DEFAULT_WORKSPACE_PATH }) }
    }

    fun resetEnvDefaults() {
        prefs.edit {
            putString(KEY_IDA_HOME, DEFAULT_IDA_HOME)
            putString(KEY_WORKSPACE_PATH, DEFAULT_WORKSPACE_PATH)
        }
        _envSettings.value = readEnvSettings()
    }

    // ==================== Floating Window ====================

    fun setFloatingWindowEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FLOATING_WINDOW, enabled) }
        _floatingWindowEnabled.value = enabled
    }

    // ==================== Read helpers ====================

    private fun readVncSettings(): VncSettings = VncSettings(
        port = prefs.getInt(KEY_VNC_PORT, DEFAULT_VNC_PORT).coerceIn(1, 65535),
        password = prefs.getString(KEY_VNC_PASSWORD, DEFAULT_VNC_PASSWORD)?.let(::sanitizeSingleLine) ?: DEFAULT_VNC_PASSWORD,
        geometry = sanitizeGeometry(prefs.getString(KEY_VNC_GEOMETRY, DEFAULT_VNC_GEOMETRY) ?: DEFAULT_VNC_GEOMETRY),
        depth = prefs.getInt(KEY_VNC_DEPTH, DEFAULT_VNC_DEPTH).let { if (it in setOf(16, 24, 32)) it else DEFAULT_VNC_DEPTH },
        display = prefs.getInt(KEY_VNC_DISPLAY, DEFAULT_VNC_DISPLAY).coerceIn(1, 99),
        stopGuiOnAppExit = prefs.getBoolean(KEY_STOP_GUI_ON_APP_EXIT, DEFAULT_STOP_GUI_ON_APP_EXIT)
    )

    private fun readMcpSettings(): McpSettings = McpSettings(
        port = prefs.getInt(KEY_MCP_PORT, DEFAULT_MCP_PORT).coerceIn(1, 65535),
        bindHost = sanitizeHost(prefs.getString(KEY_MCP_BIND_HOST, DEFAULT_MCP_BIND_HOST) ?: DEFAULT_MCP_BIND_HOST),
        allowOrigin = prefs.getString(KEY_MCP_ALLOW_ORIGIN, DEFAULT_MCP_ALLOW_ORIGIN)?.let(::sanitizeSingleLine) ?: DEFAULT_MCP_ALLOW_ORIGIN,
        stateless = prefs.getBoolean(KEY_MCP_STATELESS, DEFAULT_MCP_STATELESS),
        sessionKeepAliveSecs = prefs.getInt(KEY_MCP_SESSION_KEEPALIVE, DEFAULT_MCP_SESSION_KEEPALIVE).coerceIn(0, 86_400),
        sseKeepAliveSecs = prefs.getInt(KEY_MCP_SSE_KEEPALIVE, DEFAULT_MCP_SSE_KEEPALIVE).coerceIn(0, 300),
        autoRestart = prefs.getBoolean(KEY_MCP_AUTO_RESTART, DEFAULT_MCP_AUTO_RESTART)
    )

    private fun readTerminalSettings(): TerminalSettings = TerminalSettings(
        fontSizeSp = prefs.getInt(KEY_TERMINAL_FONT_SIZE, DEFAULT_TERMINAL_FONT_SIZE).coerceIn(6, 32),
        initialCwd = prefs.getString(KEY_TERMINAL_INITIAL_CWD, DEFAULT_TERMINAL_INITIAL_CWD) ?: DEFAULT_TERMINAL_INITIAL_CWD,
        colorScheme = prefs.getString(KEY_TERMINAL_COLOR_SCHEME, DEFAULT_TERMINAL_COLOR_SCHEME) ?: DEFAULT_TERMINAL_COLOR_SCHEME
    )

    private fun readAgentSettings(): AgentSettings = AgentSettings(
        defaultThinkingLevel = prefs.getString(KEY_AGENT_DEFAULT_THINKING, DEFAULT_AGENT_THINKING) ?: DEFAULT_AGENT_THINKING,
        autoCompaction = prefs.getBoolean(KEY_AGENT_AUTO_COMPACTION, DEFAULT_AGENT_AUTO_COMPACTION),
        promptTimeoutSecs = prefs.getInt(KEY_AGENT_PROMPT_TIMEOUT, DEFAULT_AGENT_PROMPT_TIMEOUT).coerceIn(30, 3600)
    )

    private fun readAppearanceSettings(): AppearanceSettings = AppearanceSettings(
        themeMode = prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM,
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
    )

    private fun readEnvSettings(): EnvSettings = EnvSettings(
        idaHome = prefs.getString(KEY_IDA_HOME, DEFAULT_IDA_HOME) ?: DEFAULT_IDA_HOME,
        workspacePath = prefs.getString(KEY_WORKSPACE_PATH, DEFAULT_WORKSPACE_PATH) ?: DEFAULT_WORKSPACE_PATH
    )

    // ==================== Sanitizers ====================

    private fun sanitizeSingleLine(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()

    private fun sanitizeGeometry(value: String): String {
        val trimmed = sanitizeSingleLine(value)
        return if (GEOMETRY_REGEX.matches(trimmed)) trimmed else DEFAULT_VNC_GEOMETRY
    }

    private fun sanitizeHost(value: String): String {
        val trimmed = value.trim().ifBlank { DEFAULT_MCP_BIND_HOST }
        return if (Regex("^[A-Za-z0-9_.:-]+$").matches(trimmed)) trimmed else DEFAULT_MCP_BIND_HOST
    }

    private fun ensureRandomPasswordIfFirstRun() {
        if (prefs.getBoolean(KEY_FIRST_RUN_DONE, false)) return
        prefs.edit {
            putBoolean(KEY_FIRST_RUN_DONE, true)
            // Only generate if the password is still the old hardcoded default or empty.
            val current = prefs.getString(KEY_VNC_PASSWORD, DEFAULT_VNC_PASSWORD)
            if (current == null || current == LEGACY_VNC_PASSWORD || current.isBlank()) {
                putString(KEY_VNC_PASSWORD, generateRandomPassword())
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "idadroid_settings"

        // First-run flag
        private const val KEY_FIRST_RUN_DONE = "first_run_done"

        // VNC keys
        private const val KEY_VNC_PORT = "vnc_port"
        private const val KEY_VNC_PASSWORD = "vnc_password"
        private const val KEY_VNC_GEOMETRY = "vnc_geometry"
        private const val KEY_VNC_DEPTH = "vnc_depth"
        private const val KEY_VNC_DISPLAY = "vnc_display"
        private const val KEY_STOP_GUI_ON_APP_EXIT = "stop_gui_on_app_exit"

        // MCP keys
        private const val KEY_MCP_PORT = "mcp_port"
        private const val KEY_MCP_BIND_HOST = "mcp_bind_host"
        private const val KEY_MCP_ALLOW_ORIGIN = "mcp_allow_origin"
        private const val KEY_MCP_STATELESS = "mcp_stateless"
        private const val KEY_MCP_SESSION_KEEPALIVE = "mcp_session_keepalive"
        private const val KEY_MCP_SSE_KEEPALIVE = "mcp_sse_keepalive"
        private const val KEY_MCP_AUTO_RESTART = "mcp_auto_restart"

        // Terminal keys
        private const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size"
        private const val KEY_TERMINAL_INITIAL_CWD = "terminal_initial_cwd"
        private const val KEY_TERMINAL_COLOR_SCHEME = "terminal_color_scheme"

        // Agent keys
        private const val KEY_AGENT_DEFAULT_THINKING = "agent_default_thinking"
        private const val KEY_AGENT_AUTO_COMPACTION = "agent_auto_compaction"
        private const val KEY_AGENT_PROMPT_TIMEOUT = "agent_prompt_timeout"

        // Appearance keys
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"

        // Environment keys
        private const val KEY_IDA_HOME = "ida_home"
        private const val KEY_WORKSPACE_PATH = "workspace_path"

        // Floating window
        private const val KEY_FLOATING_WINDOW = "floating_window_enabled"

        // VNC defaults
        const val DEFAULT_VNC_PORT = 5901
        const val LEGACY_VNC_PASSWORD = "Zbt7nba5"
        const val DEFAULT_VNC_GEOMETRY = "1280x800"
        const val DEFAULT_VNC_DEPTH = 24
        const val DEFAULT_VNC_DISPLAY = 1
        const val DEFAULT_STOP_GUI_ON_APP_EXIT = false

        // MCP defaults
        const val DEFAULT_MCP_PORT = 8765
        const val DEFAULT_MCP_BIND_HOST = "127.0.0.1"
        const val DEFAULT_MCP_ALLOW_ORIGIN = "http://localhost,http://127.0.0.1"
        const val DEFAULT_MCP_STATELESS = false
        const val DEFAULT_MCP_SESSION_KEEPALIVE = 1800
        const val DEFAULT_MCP_SSE_KEEPALIVE = 15
        const val DEFAULT_MCP_AUTO_RESTART = true

        // Terminal defaults
        const val DEFAULT_TERMINAL_FONT_SIZE = 14
        const val DEFAULT_TERMINAL_INITIAL_CWD = "/root"
        const val DEFAULT_TERMINAL_COLOR_SCHEME = "dark"

        // Agent defaults
        const val DEFAULT_AGENT_THINKING = "medium"
        const val DEFAULT_AGENT_AUTO_COMPACTION = true
        const val DEFAULT_AGENT_PROMPT_TIMEOUT = 300

        // Appearance defaults
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        // Environment defaults
        const val DEFAULT_IDA_HOME = "/root/ida-pro-9.3"
        const val DEFAULT_WORKSPACE_PATH = "/root/pi_workspace"

        // Default VNC password (legacy, kept for reference; new installs get random)
        val DEFAULT_VNC_PASSWORD: String = LEGACY_VNC_PASSWORD

        private val GEOMETRY_REGEX = Regex("^[1-9][0-9]{2,4}x[1-9][0-9]{2,4}$")

        private val VNC_KEYS = setOf(
            KEY_VNC_PORT, KEY_VNC_PASSWORD, KEY_VNC_GEOMETRY,
            KEY_VNC_DEPTH, KEY_VNC_DISPLAY, KEY_STOP_GUI_ON_APP_EXIT
        )
        private val MCP_KEYS = setOf(
            KEY_MCP_PORT, KEY_MCP_BIND_HOST, KEY_MCP_ALLOW_ORIGIN,
            KEY_MCP_STATELESS, KEY_MCP_SESSION_KEEPALIVE, KEY_MCP_SSE_KEEPALIVE,
            KEY_MCP_AUTO_RESTART
        )
        private val TERMINAL_KEYS = setOf(
            KEY_TERMINAL_FONT_SIZE, KEY_TERMINAL_INITIAL_CWD, KEY_TERMINAL_COLOR_SCHEME
        )
        private val AGENT_KEYS = setOf(
            KEY_AGENT_DEFAULT_THINKING, KEY_AGENT_AUTO_COMPACTION, KEY_AGENT_PROMPT_TIMEOUT
        )
        private val APPEARANCE_KEYS = setOf(KEY_THEME_MODE, KEY_DYNAMIC_COLOR)
        private val ENV_KEYS = setOf(KEY_IDA_HOME, KEY_WORKSPACE_PATH)

        /** Generate a random 8-character alphanumeric password suitable for VNC auth. */
        fun generateRandomPassword(): String {
            val chars = ('A'..'Z') + ('a'..'z') + ('2'..'9')
            val rng = java.security.SecureRandom()
            return (1..8).map { chars[rng.nextInt(chars.size)] }.joinToString("")
        }
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

data class McpSettings(
    val port: Int = IdaDroidSettings.DEFAULT_MCP_PORT,
    val bindHost: String = IdaDroidSettings.DEFAULT_MCP_BIND_HOST,
    val allowOrigin: String = IdaDroidSettings.DEFAULT_MCP_ALLOW_ORIGIN,
    val stateless: Boolean = IdaDroidSettings.DEFAULT_MCP_STATELESS,
    val sessionKeepAliveSecs: Int = IdaDroidSettings.DEFAULT_MCP_SESSION_KEEPALIVE,
    val sseKeepAliveSecs: Int = IdaDroidSettings.DEFAULT_MCP_SSE_KEEPALIVE,
    val autoRestart: Boolean = IdaDroidSettings.DEFAULT_MCP_AUTO_RESTART
) {
    val bind: String get() {
        val host = bindHost.ifBlank { "127.0.0.1" }
        // Bracket IPv6 addresses (e.g. ::1 → [::1]:port) to avoid ambiguity
        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "$formattedHost:${port.coerceIn(1, 65535)}"
    }
    val endpoint: String get() = "http://$bind"
}

data class TerminalSettings(
    val fontSizeSp: Int = IdaDroidSettings.DEFAULT_TERMINAL_FONT_SIZE,
    val initialCwd: String = IdaDroidSettings.DEFAULT_TERMINAL_INITIAL_CWD,
    val colorScheme: String = IdaDroidSettings.DEFAULT_TERMINAL_COLOR_SCHEME
)

data class AgentSettings(
    val defaultThinkingLevel: String = IdaDroidSettings.DEFAULT_AGENT_THINKING,
    val autoCompaction: Boolean = IdaDroidSettings.DEFAULT_AGENT_AUTO_COMPACTION,
    val promptTimeoutSecs: Int = IdaDroidSettings.DEFAULT_AGENT_PROMPT_TIMEOUT
)

data class AppearanceSettings(
    val themeMode: String = IdaDroidSettings.THEME_SYSTEM,
    val dynamicColor: Boolean = true
)

data class EnvSettings(
    val idaHome: String = IdaDroidSettings.DEFAULT_IDA_HOME,
    val workspacePath: String = IdaDroidSettings.DEFAULT_WORKSPACE_PATH
)
