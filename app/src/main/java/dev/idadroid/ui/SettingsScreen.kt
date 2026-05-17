package dev.idadroid.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.idadroid.settings.IdaDroidSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: IdaDroidSettings,
    onBackClick: () -> Unit,
    onShowTerminalLog: () -> Unit = {},
    onShowVncLog: () -> Unit = {}
) {
    val context = LocalContext.current
    val vnc by settingsStore.vncSettings.collectAsState()

    var editPort by remember { mutableStateOf(false) }
    var editPassword by remember { mutableStateOf(false) }
    var editGeometry by remember { mutableStateOf(false) }
    var editDisplay by remember { mutableStateOf(false) }
    var chooseDepth by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
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
            item { SettingsSectionHeader("GUI / VNC") }
            item {
                SettingsItem(
                    title = "VNC 端口",
                    subtitle = "127.0.0.1:${vnc.port}（默认 5901）",
                    icon = Icons.Default.Computer,
                    onClick = { editPort = true }
                )
            }
            item {
                SettingsItem(
                    title = "VNC 密码",
                    subtitle = if (vnc.password.isBlank()) "无密码（仅 localhost）" else "已设置：${vnc.password.length} 位；VNCAuth 通常只使用前 8 位",
                    icon = Icons.Default.Lock,
                    onClick = { editPassword = true }
                )
            }
            item {
                SettingsItem(
                    title = "Display",
                    subtitle = ":${vnc.display}",
                    icon = Icons.Default.Settings,
                    onClick = { editDisplay = true }
                )
            }
            item {
                SettingsItem(
                    title = "分辨率",
                    subtitle = vnc.geometry,
                    icon = Icons.Default.Computer,
                    onClick = { editGeometry = true }
                )
            }
            item {
                SettingsItem(
                    title = "色深",
                    subtitle = vnc.depth.toString(),
                    icon = Icons.Default.Settings,
                    onClick = { chooseDepth = true }
                )
            }
            item {
                SettingsToggleItem(
                    title = "退出 App 时停止 GUI",
                    subtitle = "关闭后 VNC/IDA 会继续运行，可从主页手动停止。",
                    checked = vnc.stopGuiOnAppExit,
                    onCheckedChange = settingsStore::updateStopGuiOnAppExit
                )
            }
            item {
                SettingsItem(
                    title = "恢复 VNC 默认值",
                    subtitle = "端口 5901，密码 Zbt7nba5，1280x800，depth 24",
                    icon = Icons.Default.Refresh,
                    onClick = { confirmReset = true }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SettingsSectionHeader("日志") }
            item {
                SettingsItem(
                    title = "终端启动日志",
                    subtitle = "查看 proot 终端启动命令、pid、exit code 和错误信息。",
                    icon = Icons.Default.OpenInNew,
                    onClick = onShowTerminalLog
                )
            }
            item {
                SettingsItem(
                    title = "IDA GUI / VNC 日志",
                    subtitle = "查看 VNC/IDA 启动脚本输出。",
                    icon = Icons.Default.OpenInNew,
                    onClick = onShowVncLog
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SettingsSectionHeader("AVNC Viewer") }
            item {
                SettingsItem(
                    title = "打开 AVNC",
                    subtitle = "当前阶段通过 vnc:// 调起 AVNC；AVNC 内的手势、键盘、缩放设置沿用其实现。",
                    icon = Icons.Default.OpenInNew,
                    onClick = { openAvnc(context) }
                )
            }
            item {
                Text(
                    text = "提示：默认 rootfs 的 VNC 脚本输出端口 5901、密码 Zbt7nba5。这里修改后，IDAdroid 生成的 start-ida-vnc.sh 会把新端口/密码写入 TigerVNC/x11vnc 启动参数，并用同一配置打开 AVNC。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
    }

    if (editPort) {
        NumberEditDialog(
            title = "VNC 端口",
            initialValue = vnc.port.toString(),
            label = "端口（1-65535）",
            onDismiss = { editPort = false },
            onSave = { value ->
                val port = value.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    Toast.makeText(context, "端口必须在 1-65535", Toast.LENGTH_SHORT).show()
                } else {
                    settingsStore.updateVncPort(port)
                    editPort = false
                }
            }
        )
    }

    if (editPassword) {
        TextEditDialog(
            title = "VNC 密码",
            initialValue = vnc.password,
            label = "密码（留空则尝试无密码，仅 localhost）",
            helper = "TigerVNC 的 VNCAuth 通常只使用前 8 个字符；默认密码为 Zbt7nba5。",
            onDismiss = { editPassword = false },
            onSave = { value ->
                settingsStore.updateVncPassword(value)
                editPassword = false
            }
        )
    }

    if (editGeometry) {
        TextEditDialog(
            title = "VNC 分辨率",
            initialValue = vnc.geometry,
            label = "例如 1280x800 / 1920x1080",
            helper = "修改分辨率后需要重启 GUI 才生效。",
            onDismiss = { editGeometry = false },
            onSave = { value ->
                if (!Regex("^[1-9][0-9]{2,4}x[1-9][0-9]{2,4}$").matches(value.trim())) {
                    Toast.makeText(context, "格式应为 1280x800", Toast.LENGTH_SHORT).show()
                } else {
                    settingsStore.updateVncGeometry(value)
                    editGeometry = false
                }
            }
        )
    }

    if (editDisplay) {
        NumberEditDialog(
            title = "Display",
            initialValue = vnc.display.toString(),
            label = "Display number（1-99）",
            onDismiss = { editDisplay = false },
            onSave = { value ->
                val display = value.toIntOrNull()
                if (display == null || display !in 1..99) {
                    Toast.makeText(context, "Display 必须在 1-99", Toast.LENGTH_SHORT).show()
                } else {
                    settingsStore.updateVncDisplay(display)
                    editDisplay = false
                }
            }
        )
    }

    if (chooseDepth) {
        AlertDialog(
            onDismissRequest = { chooseDepth = false },
            title = { Text("色深") },
            text = {
                Column {
                    listOf(16, 24, 32).forEach { depth ->
                        TextButton(
                            onClick = {
                                settingsStore.updateVncDepth(depth)
                                chooseDepth = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (depth == vnc.depth) "$depth ✓" else depth.toString())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooseDepth = false }) { Text("关闭") }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复 VNC 默认值？") },
            text = { Text("会恢复端口、密码、display、分辨率、色深和退出行为。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsStore.resetVncDefaults()
                        confirmReset = false
                    }
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
    )
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
        title = title,
        initialValue = initialValue,
        label = label,
        helper = null,
        keyboardType = KeyboardType.Number,
        onDismiss = onDismiss,
        onSave = onSave
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
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
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
        runCatching { context.startActivity(webIntent) }
        Toast.makeText(context, "未安装 AVNC", Toast.LENGTH_SHORT).show()
    }
}
