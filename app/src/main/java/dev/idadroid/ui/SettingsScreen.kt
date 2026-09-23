package dev.idadroid.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.idadroid.service.FloatingWindowService
import dev.idadroid.settings.IdaDroidSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: IdaDroidSettings,
    onBackClick: () -> Unit,
    onShowTerminalLog: () -> Unit = {},
    onShowVncLog: () -> Unit = {},
    onShowMcpLog: () -> Unit = {}
) {
    val context = LocalContext.current
    val vnc by settingsStore.vncSettings.collectAsState()
    val mcp by settingsStore.mcpSettings.collectAsState()
    val terminal by settingsStore.terminalSettings.collectAsState()
    val agent by settingsStore.agentSettings.collectAsState()
    val appearance by settingsStore.appearanceSettings.collectAsState()
    val env by settingsStore.envSettings.collectAsState()
    val overlayEnabled by settingsStore.floatingWindowEnabled.collectAsState()

    val overlayPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) {
            FloatingWindowService.start(context)
        } else {
            Toast.makeText(context, "未授予悬浮窗权限，无法开启悬浮窗", Toast.LENGTH_SHORT).show()
        }
    }

    // Dialog state
    var editVncPort by remember { mutableStateOf(false) }
    var editVncPassword by remember { mutableStateOf(false) }
    var editVncGeometry by remember { mutableStateOf(false) }
    var editVncDisplay by remember { mutableStateOf(false) }
    var chooseVncDepth by remember { mutableStateOf(false) }
    var confirmVncReset by remember { mutableStateOf(false) }

    var editMcpPort by remember { mutableStateOf(false) }
    var editMcpHost by remember { mutableStateOf(false) }
    var editMcpOrigin by remember { mutableStateOf(false) }
    var editMcpSessionKa by remember { mutableStateOf(false) }
    var editMcpSseKa by remember { mutableStateOf(false) }
    var confirmMcpReset by remember { mutableStateOf(false) }

    var editTerminalFont by remember { mutableStateOf(false) }
    var editTerminalCwd by remember { mutableStateOf(false) }
    var chooseTerminalColor by remember { mutableStateOf(false) }
    var confirmTerminalReset by remember { mutableStateOf(false) }

    var editAgentThinking by remember { mutableStateOf(false) }
    var editAgentTimeout by remember { mutableStateOf(false) }
    var confirmAgentReset by remember { mutableStateOf(false) }

    var editIdaHome by remember { mutableStateOf(false) }
    var editWorkspace by remember { mutableStateOf(false) }
    var confirmEnvReset by remember { mutableStateOf(false) }

    var confirmResetAll by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ==================== VNC / GUI ====================
            item { SettingsSectionHeader("GUI / VNC", Icons.Default.Computer) }
            item {
                SettingsItem(
                    title = "VNC 端口",
                    subtitle = "127.0.0.1:${vnc.port}",
                    icon = Icons.Default.Computer,
                    onClick = { editVncPort = true }
                )
            }
            item {
                SettingsItem(
                    title = "VNC 密码",
                    subtitle = if (vnc.password.isBlank()) "无密码（仅 localhost）" else "已设置：${vnc.password.length} 位",
                    icon = Icons.Default.Lock,
                    onClick = { editVncPassword = true }
                )
            }
            item {
                SettingsItem(
                    title = "Display",
                    subtitle = ":${vnc.display}（端口 ${5900 + vnc.display}）",
                    icon = Icons.Default.Settings,
                    onClick = { editVncDisplay = true }
                )
            }
            item {
                SettingsItem(
                    title = "分辨率",
                    subtitle = vnc.geometry,
                    icon = Icons.Default.Computer,
                    onClick = { editVncGeometry = true }
                )
            }
            item {
                SettingsItem(
                    title = "色深",
                    subtitle = "${vnc.depth} bit",
                    icon = Icons.Default.Settings,
                    onClick = { chooseVncDepth = true }
                )
            }
            item {
                SettingsToggleItem(
                    title = "退出 App 时关闭 GUI",
                    subtitle = "关闭后 VNC/IDA 继续运行，可从主页手动停止",
                    checked = vnc.stopGuiOnAppExit,
                    onCheckedChange = settingsStore::updateStopGuiOnAppExit
                )
            }
            item {
                SettingsItem(
                    title = "重新生成随机密码",
                    subtitle = "生成新的 8 位随机密码",
                    icon = Icons.Default.Refresh,
                    onClick = {
                        settingsStore.updateVncPassword(IdaDroidSettings.generateRandomPassword())
                        Toast.makeText(context, "已生成新密码", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            item {
                SettingsItem(
                    title = "恢复 VNC 默认设置",
                    subtitle = "端口 5901，随机密码，1280×800，24 bit",
                    icon = Icons.Default.Refresh,
                    onClick = { confirmVncReset = true }
                )
            }

            // ==================== MCP ====================
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SettingsSectionHeader("IDA MCP 服务", Icons.Default.Dashboard) }
            item {
                SettingsItem(
                    title = "MCP HTTP 端口",
                    subtitle = "${mcp.bindHost}:${mcp.port}",
                    icon = Icons.Default.Dashboard,
                    onClick = { editMcpPort = true }
                )
            }
            item {
                SettingsItem(
                    title = "绑定地址",
                    subtitle = mcp.bindHost,
                    icon = Icons.Default.Computer,
                    onClick = { editMcpHost = true }
                )
            }
            item {
                SettingsItem(
                    title = "允许的 Origin (CORS)",
                    subtitle = mcp.allowOrigin.ifBlank { "无限制" },
                    icon = Icons.Default.Lock,
                    onClick = { editMcpOrigin = true }
                )
            }
            item {
                SettingsToggleItem(
                    title = "无状态模式",
                    subtitle = "每个请求独立处理，不保持 session 状态",
                    checked = mcp.stateless,
                    onCheckedChange = settingsStore::updateMcpStateless
                )
            }
            item {
                SettingsItem(
                    title = "Session 保活",
                    subtitle = "${mcp.sessionKeepAliveSecs} 秒",
                    icon = Icons.Default.Settings,
                    onClick = { editMcpSessionKa = true }
                )
            }
            item {
                SettingsItem(
                    title = "SSE 保活",
                    subtitle = "${mcp.sseKeepAliveSecs} 秒",
                    icon = Icons.Default.Settings,
                    onClick = { editMcpSseKa = true }
                )
            }
            item {
                SettingsToggleItem(
                    title = "自动重启",
                    subtitle = "MCP 服务异常退出后自动重启",
                    checked = mcp.autoRestart,
                    onCheckedChange = settingsStore::updateMcpAutoRestart
                )
            }
            item {
                SettingsItem(
                    title = "恢复 MCP 默认设置",
                    subtitle = "端口 8765，127.0.0.1",
                    icon = Icons.Default.Refresh,
                    onClick = { confirmMcpReset = true }
                )
            }

            // ==================== Terminal ====================
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SettingsSectionHeader("终端", Icons.Default.Terminal) }
            item {
                SettingsItem(
                    title = "字体大小",
                    subtitle = "${terminal.fontSizeSp} sp",
                    icon = Icons.Default.Terminal,
                    onClick = { editTerminalFont = true }
                )
            }
            item {
                SettingsItem(
                    title = "初始工作目录",
                    subtitle = terminal.initialCwd,
                    icon = Icons.Default.Folder,
                    onClick = { editTerminalCwd = true }
                )
            }
            item {
                SettingsItem(
                    title = "配色方案",
                    subtitle = when (terminal.colorScheme) {
                        "dark" -> "深色"
                        "light" -> "浅色"
                        "solarized" -> "Solarized"
                        else -> terminal.colorScheme
                    },
                    icon = Icons.Default.Palette,
                    onClick = { chooseTerminalColor = true }
                )
            }
            item {
                SettingsItem(
                    title = "恢复终端默认设置",
                    subtitle = "14 sp，/root，深色",
                    icon = Icons.Default.Refresh,
                    onClick = { confirmTerminalReset = true }
                )
            }

            // ==================== Agent ====================
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SettingsSectionHeader("AI Agent", Icons.Default.SmartToy) }
            item {
                SettingsItem(
                    title = "默认思考级别",
                    subtitle = when (agent.defaultThinkingLevel) {
                        "low" -> "低"
                        "medium" -> "中"
                        "high" -> "高"
                        else -> agent.defaultThinkingLevel
                    },
                    icon = Icons.Default.SmartToy,
                    onClick = { editAgentThinking = true }
                )
            }
            item {
                SettingsToggleItem(
                    title = "自动压缩上下文",
                    subtitle = "上下文接近窗口上限时自动压缩历史",
                    checked = agent.autoCompaction,
                    onCheckedChange = settingsStore::updateAgentAutoCompaction
                )
            }
            item {
                SettingsItem(
                    title = "单次对话超时",
                    subtitle = "${agent.promptTimeoutSecs} 秒",
                    icon = Icons.Default.Settings,
                    onClick = { editAgentTimeout = true }
                )
            }
            item {
                SettingsItem(
                    title = "恢复 Agent 默认设置",
                    subtitle = "medium，自动压缩，300 秒",
                    icon = Icons.Default.Refresh,
                    onClick = { confirmAgentReset = true }
                )
            }

            // ==================== Appearance ====================
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SettingsSectionHeader("外观", Icons.Default.Palette) }
            item {
                SettingsItem(
                    title = "主题模式",
                    subtitle = when (appearance.themeMode) {
                        IdaDroidSettings.THEME_SYSTEM -> "跟随系统"
                        IdaDroidSettings.THEME_LIGHT -> "浅色"
                        IdaDroidSettings.THEME_DARK -> "深色"
                        else -> "跟随系统"
                    },
                    icon = if (appearance.themeMode == IdaDroidSettings.THEME_DARK) Icons.Default.DarkMode else Icons.Default.Palette,
                    onClick = {} // handled by chips below
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        IdaDroidSettings.THEME_SYSTEM to "跟随系统",
                        IdaDroidSettings.THEME_LIGHT to "浅色",
                        IdaDroidSettings.THEME_DARK to "深色"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = appearance.themeMode == mode,
                            onClick = { settingsStore.updateThemeMode(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }
            item {
                SettingsToggleItem(
                    title = "动态取色 (Android 12+)",
                    subtitle = "根据壁纸自动生成配色方案",
                    checked = appearance.dynamicColor,
                    onCheckedChange = settingsStore::updateDynamicColor
                )
            }

            // ==================== Floating Window ====================
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SettingsSectionHeader("悬浮窗", Icons.Default.Dashboard) }
            item {
                SettingsToggleItem(
                    title = "启用悬浮窗",
                    subtitle = if (Settings.canDrawOverlays(context)) {
                        "在其它应用上方显示悬浮快捷球：环境 / IDA / Agent / MCP 状态 + 快捷操作。"
                    } else {
                        "需要授予“显示在其他应用上层”权限。"
                    },
                    checked = overlayEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            if (Settings.canDrawOverlays(context)) {
                                FloatingWindowService.start(context)
                            } else {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                runCatching { overlayPermissionLauncher.launch(intent) }
                                    .onFailure {
                                        runCatching {
                                            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                                        }.onFailure {
                                            Toast.makeText(context, "请到系统设置中手动授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            }
                        } else {
                            // Record user intent before stopping the service;
                            // the service no longer clears the preference itself.
                            settingsStore.setFloatingWindowEnabled(false)
                            FloatingWindowService.stop(context)
                        }
                    }
                )
            }

            // ==================== Environment Paths ====================
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SettingsSectionHeader("环境路径", Icons.Default.Folder) }
            item {
                SettingsItem(
                    title = "IDA 安装路径",
                    subtitle = env.idaHome,
                    icon = Icons.Default.Folder,
                    onClick = { editIdaHome = true }
                )
            }
            item {
                SettingsItem(
                    title = "Pi 工作区路径",
                    subtitle = env.workspacePath,
                    icon = Icons.Default.Folder,
                    onClick = { editWorkspace = true }
                )
            }
            item {
                SettingsItem(
                    title = "恢复路径默认设置",
                    subtitle = "IDA Pro 与工作区路径配置",
                    icon = Icons.Default.Refresh,
                    onClick = { confirmEnvReset = true }
                )
            }

            // ==================== Diagnostics ====================
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SettingsSectionHeader("诊断与日志", Icons.Default.BugReport) }
            item {
                SettingsItem(
                    title = "终端启动日志",
                    subtitle = "查看 proot 终端启动命令、pid、exit code",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = onShowTerminalLog
                )
            }
            item {
                SettingsItem(
                    title = "IDA GUI / VNC 日志",
                    subtitle = "查看 VNC/IDA 启动脚本输出",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = onShowVncLog
                )
            }
            item {
                SettingsItem(
                    title = "IDA MCP 日志",
                    subtitle = "查看 MCP HTTP 服务运行日志",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = onShowMcpLog
                )
            }
            item {
                SettingsItem(
                    title = "清除缓存",
                    subtitle = "清除 App 缓存目录（不影响 rootfs）",
                    icon = Icons.Default.Refresh,
                    onClick = {
                        context.cacheDir.deleteRecursively()
                        Toast.makeText(context, "已清除缓存", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // ==================== AVNC ====================
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SettingsSectionHeader("AVNC Viewer", Icons.AutoMirrored.Filled.OpenInNew) }
            item {
                SettingsItem(
                    title = "打开 AVNC",
                    subtitle = "通过 AVNC 连接 VNC 服务",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = { openAvnc(context) }
                )
            }

            // ==================== Reset All ====================
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                SettingsItem(
                    title = "恢复全部默认设置",
                    subtitle = "重置所有设置为初始值",
                    icon = Icons.Default.Refresh,
                    onClick = { confirmResetAll = true }
                )
            }
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    // ==================== VNC Dialogs ====================
    if (editVncPort) {
        NumberEditDialog(
            title = "VNC 端口", initialValue = vnc.port.toString(), label = "端口（1-65535）",
            onDismiss = { editVncPort = false },
            onSave = { value ->
                val port = value.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    Toast.makeText(context, "端口必须在 1-65535", Toast.LENGTH_SHORT).show()
                } else {
                    settingsStore.updateVncPort(port)
                    editVncPort = false
                }
            }
        )
    }
    if (editVncPassword) {
        TextEditDialog(
            title = "VNC 密码", initialValue = vnc.password,
            label = "密码（留空则尝试无密码，仅 localhost）",
            helper = "TigerVNC 的 VNCAuth 通常只使用前 8 个字符。",
            onDismiss = { editVncPassword = false },
            onSave = { value -> settingsStore.updateVncPassword(value); editVncPassword = false }
        )
    }
    if (editVncGeometry) {
        TextEditDialog(
            title = "VNC 分辨率", initialValue = vnc.geometry,
            label = "例如 1280x800 / 1920x1080",
            helper = "修改分辨率后需要重启 GUI 才生效。",
            onDismiss = { editVncGeometry = false },
            onSave = { value ->
                if (!Regex("^[1-9][0-9]{2,4}x[1-9][0-9]{2,4}$").matches(value.trim())) {
                    Toast.makeText(context, "格式应为 1280x800", Toast.LENGTH_SHORT).show()
                } else {
                    settingsStore.updateVncGeometry(value)
                    editVncGeometry = false
                }
            }
        )
    }
    if (editVncDisplay) {
        NumberEditDialog(
            title = "Display", initialValue = vnc.display.toString(), label = "Display number（1-99）",
            onDismiss = { editVncDisplay = false },
            onSave = { value ->
                val display = value.toIntOrNull()
                if (display == null || display !in 1..99) {
                    Toast.makeText(context, "Display 必须在 1-99", Toast.LENGTH_SHORT).show()
                } else {
                    settingsStore.updateVncDisplay(display)
                    editVncDisplay = false
                }
            }
        )
    }
    if (chooseVncDepth) {
        ChoiceDialog(
            title = "色深",
            options = listOf(16, 24, 32),
            current = vnc.depth,
            labelFor = { "$it bit" },
            onDismiss = { chooseVncDepth = false },
            onSelect = { settingsStore.updateVncDepth(it); chooseVncDepth = false }
        )
    }
    if (confirmVncReset) {
        ConfirmDialog(
            title = "恢复 VNC 默认设置？",
            message = "会恢复端口、密码（随机生成）、display、分辨率、色深和退出行为。",
            onConfirm = { settingsStore.resetVncDefaults(); confirmVncReset = false },
            onDismiss = { confirmVncReset = false }
        )
    }

    // ==================== MCP Dialogs ====================
    if (editMcpPort) {
        NumberEditDialog(
            title = "MCP HTTP 端口", initialValue = mcp.port.toString(), label = "端口（1-65535）",
            onDismiss = { editMcpPort = false },
            onSave = { value ->
                val port = value.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    Toast.makeText(context, "端口必须在 1-65535", Toast.LENGTH_SHORT).show()
                } else {
                    settingsStore.updateMcpPort(port)
                    editMcpPort = false
                }
            }
        )
    }
    if (editMcpHost) {
        TextEditDialog(
            title = "绑定地址", initialValue = mcp.bindHost, label = "IP 地址或主机名",
            helper = "建议保持 127.0.0.1 以避免外部访问。",
            onDismiss = { editMcpHost = false },
            onSave = { value -> settingsStore.updateMcpBindHost(value); editMcpHost = false }
        )
    }
    if (editMcpOrigin) {
        TextEditDialog(
            title = "允许的 Origin (CORS)", initialValue = mcp.allowOrigin,
            label = "逗号分隔的 Origin 列表",
            helper = "留空则不限制 Origin。",
            onDismiss = { editMcpOrigin = false },
            onSave = { value -> settingsStore.updateMcpAllowOrigin(value); editMcpOrigin = false }
        )
    }
    if (editMcpSessionKa) {
        NumberEditDialog(
            title = "Session 保活", initialValue = mcp.sessionKeepAliveSecs.toString(),
            label = "秒（0-86400）",
            onDismiss = { editMcpSessionKa = false },
            onSave = { value ->
                val secs = value.toIntOrNull()
                if (secs != null) {
                    settingsStore.updateMcpSessionKeepAlive(secs)
                    editMcpSessionKa = false
                }
            }
        )
    }
    if (editMcpSseKa) {
        NumberEditDialog(
            title = "SSE 保活", initialValue = mcp.sseKeepAliveSecs.toString(),
            label = "秒（0-300）",
            onDismiss = { editMcpSseKa = false },
            onSave = { value ->
                val secs = value.toIntOrNull()
                if (secs != null) {
                    settingsStore.updateMcpSseKeepAlive(secs)
                    editMcpSseKa = false
                }
            }
        )
    }
    if (confirmMcpReset) {
        ConfirmDialog(
            title = "恢复 MCP 默认设置？",
            message = "会恢复端口、绑定地址、CORS、无状态模式和保活时间。",
            onConfirm = { settingsStore.resetMcpDefaults(); confirmMcpReset = false },
            onDismiss = { confirmMcpReset = false }
        )
    }

    // ==================== Terminal Dialogs ====================
    if (editTerminalFont) {
        NumberEditDialog(
            title = "字体大小", initialValue = terminal.fontSizeSp.toString(),
            label = "sp（6-32）",
            onDismiss = { editTerminalFont = false },
            onSave = { value ->
                val sp = value.toIntOrNull()
                if (sp != null && sp in 6..32) {
                    settingsStore.updateTerminalFontSize(sp)
                    editTerminalFont = false
                } else {
                    Toast.makeText(context, "字号必须在 6-32", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    if (editTerminalCwd) {
        TextEditDialog(
            title = "初始工作目录", initialValue = terminal.initialCwd,
            label = "容器内路径",
            helper = "例如 /root 或 /root/pi_workspace（可在下方自定义）",
            onDismiss = { editTerminalCwd = false },
            onSave = { value -> settingsStore.updateTerminalInitialCwd(value); editTerminalCwd = false }
        )
    }
    if (chooseTerminalColor) {
        ChoiceDialog(
            title = "配色方案",
            options = listOf("dark", "light", "solarized"),
            current = terminal.colorScheme,
            labelFor = { when (it) { "dark" -> "深色"; "light" -> "浅色"; "solarized" -> "Solarized"; else -> it } },
            onDismiss = { chooseTerminalColor = false },
            onSelect = { settingsStore.updateTerminalColorScheme(it); chooseTerminalColor = false }
        )
    }
    if (confirmTerminalReset) {
        ConfirmDialog(
            title = "恢复终端默认设置？",
            message = "会恢复字体大小、初始目录和配色方案。",
            onConfirm = { settingsStore.resetTerminalDefaults(); confirmTerminalReset = false },
            onDismiss = { confirmTerminalReset = false }
        )
    }

    // ==================== Agent Dialogs ====================
    if (editAgentThinking) {
        ChoiceDialog(
            title = "默认思考级别",
            options = listOf("low", "medium", "high"),
            current = agent.defaultThinkingLevel,
            labelFor = { when (it) { "low" -> "低"; "medium" -> "中"; "high" -> "高"; else -> it } },
            onDismiss = { editAgentThinking = false },
            onSelect = { settingsStore.updateAgentDefaultThinking(it); editAgentThinking = false }
        )
    }
    if (editAgentTimeout) {
        NumberEditDialog(
            title = "单次对话超时", initialValue = agent.promptTimeoutSecs.toString(),
            label = "秒（30-3600）",
            onDismiss = { editAgentTimeout = false },
            onSave = { value ->
                val secs = value.toIntOrNull()
                if (secs != null && secs in 30..3600) {
                    settingsStore.updateAgentPromptTimeoutSecs(secs)
                    editAgentTimeout = false
                } else {
                    Toast.makeText(context, "超时必须在 30-3600 秒", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    if (confirmAgentReset) {
        ConfirmDialog(
            title = "恢复 Agent 默认设置？",
            message = "会恢复思考级别、自动压缩和超时设置。",
            onConfirm = { settingsStore.resetAgentDefaults(); confirmAgentReset = false },
            onDismiss = { confirmAgentReset = false }
        )
    }

    // ==================== Environment Dialogs ====================
    if (editIdaHome) {
        TextEditDialog(
            title = "IDA 安装路径", initialValue = env.idaHome,
            label = "容器内 IDA 安装目录",
            helper = "默认 /root/ida-pro-9.3",
            onDismiss = { editIdaHome = false },
            onSave = { value -> settingsStore.updateIdaHome(value); editIdaHome = false }
        )
    }
    if (editWorkspace) {
        TextEditDialog(
            title = "Pi 工作区路径", initialValue = env.workspacePath,
            label = "容器内 pi 工作区路径",
            helper = "默认 /root/pi_workspace，可改为 /sdcard/xxx 等外部路径",
            onDismiss = { editWorkspace = false },
            onSave = { value -> settingsStore.updateWorkspacePath(value); editWorkspace = false }
        )
    }
    if (confirmEnvReset) {
        ConfirmDialog(
            title = "恢复路径默认设置？",
            message = "会恢复 IDA 安装路径和工作区路径。",
            onConfirm = { settingsStore.resetEnvDefaults(); confirmEnvReset = false },
            onDismiss = { confirmEnvReset = false }
        )
    }

    // ==================== Reset All ====================
    if (confirmResetAll) {
        ConfirmDialog(
            title = "恢复全部默认设置？",
            message = "此操作会重置所有设置（VNC、MCP、终端、Agent、外观、路径），不可撤销。",
            onConfirm = {
                settingsStore.resetVncDefaults()
                settingsStore.resetMcpDefaults()
                settingsStore.resetTerminalDefaults()
                settingsStore.resetAgentDefaults()
                settingsStore.resetEnvDefaults()
                settingsStore.updateThemeMode(IdaDroidSettings.THEME_SYSTEM)
                settingsStore.updateDynamicColor(true)
                // 恢复全部默认设置时同时关闭悬浮窗（含停止正在运行的服务），
                // 否则已启用的覆盖层会保持可见，与“恢复默认”语义不一致。
                settingsStore.setFloatingWindowEnabled(false)
                FloatingWindowService.stop(context)
                confirmResetAll = false
                Toast.makeText(context, "已恢复全部默认设置", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { confirmResetAll = false }
        )
    }
}

// ==================== Reusable Composables ====================

@Composable
private fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Text("›", style = MaterialTheme.typography.headlineSmall) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    )
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@Composable
private fun NumberEditDialog(
    title: String,
    initialValue: String,
    label: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    TextEditDialog(
        title = title, initialValue = initialValue, label = label,
        helper = null, keyboardType = KeyboardType.Number,
        onDismiss = onDismiss, onSave = onSave
    )
}

@Composable
private fun TextEditDialog(
    title: String,
    initialValue: String,
    label: String,
    helper: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (helper != null) {
                    Text(
                        helper,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    current: T,
    labelFor: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    TextButton(
                        onClick = { onSelect(option) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (option == current) "${labelFor(option)} ✓" else labelFor(option))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun openAvnc(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.gaurav.avnc")
    if (launchIntent != null) {
        context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.gaurav.avnc"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gujjwal00/avnc"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(marketIntent)
    } catch (_: ActivityNotFoundException) {
        // 市场未安装时退到 GitHub 主页；只有网页也打不开才提示用户
        if (!runCatching { context.startActivity(webIntent) }.isSuccess) {
            Toast.makeText(context, "未安装 AVNC，也无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }
}
