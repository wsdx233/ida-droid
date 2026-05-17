package dev.idadroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.R
import dev.idadroid.data.EnvironmentMetadata
import dev.idadroid.data.ValidationReport
import dev.idadroid.env.EnvironmentState
import dev.idadroid.env.ImportProgress
import dev.idadroid.env.ImportStage
import dev.idadroid.files.ContainerFileManager
import dev.idadroid.mcp.IdaMcpLaunchSettings
import dev.idadroid.mcp.IdaMcpSessionState
import dev.idadroid.mcp.IdaMcpStatus
import dev.idadroid.vnc.GuiSessionState
import dev.idadroid.vnc.GuiStatus

@Composable
fun MainContent(
    envState: EnvironmentState,
    guiState: GuiSessionState,
    mcpState: IdaMcpSessionState,
    fileManager: ContainerFileManager,
    lastImportProgress: ImportProgress?,
    validationBusy: Boolean,
    transientMessage: String?,
    onChooseArchive: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenTerminal: () -> Unit,
    onRunTerminalCommand: (String) -> Unit,
    onShowTerminalDiagnostics: () -> Unit,
    onShowVncLog: () -> Unit,
    onLaunchGui: () -> Unit,
    onStopGui: () -> Unit,
    onReconnectGui: () -> Unit,
    onRestartGui: () -> Unit,
    onStartMcp: (IdaMcpLaunchSettings) -> Unit,
    onStopMcp: () -> Unit,
    onRestartMcp: (IdaMcpLaunchSettings) -> Unit,
    onShowMcpLog: () -> Unit,
    onRevalidate: () -> Unit,
    onDelete: () -> Unit
) {
    var showGuiPanel by remember { mutableStateOf(false) }
    var showMcpPanel by remember { mutableStateOf(false) }
    var showFilePanel by remember { mutableStateOf(false) }
    var showMaintenancePanel by remember { mutableStateOf(false) }
    BackHandler(enabled = showGuiPanel || showMcpPanel || showFilePanel || showMaintenancePanel) {
        showGuiPanel = false
        showMcpPanel = false
        showFilePanel = false
        showMaintenancePanel = false
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header(onOpenSettings)
            when (envState) {
                EnvironmentState.NoEnvironment -> {
                    WelcomeCard(onChooseArchive, modifier = Modifier.fillMaxWidth())
                }
                is EnvironmentState.NotReady -> {
                    WelcomeCard(onChooseArchive, modifier = Modifier.fillMaxWidth())
                    StatusBanner(
                        title = "环境未准备好",
                        body = envState.reason,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        icon = Icons.Rounded.Info
                    )
                }
                is EnvironmentState.Ready -> HomeReady(
                    metadata = envState.metadata,
                    guiState = guiState,
                    mcpState = mcpState,
                    onOpenSettings = onOpenSettings,
                    onOpenAgent = onOpenAgent,
                    onOpenTerminal = onOpenTerminal,
                    onLaunchGui = onLaunchGui,
                    onOpenGuiPanel = { showGuiPanel = true },
                    onOpenMcpPanel = { showMcpPanel = true },
                    onOpenFilePanel = { showFilePanel = true },
                    onOpenMaintenancePanel = { showMaintenancePanel = true }
                )
            }
        }

        HomePanelOverlay(
            visible = showGuiPanel,
            fromStart = false,
            title = "GUI / VNC",
            subtitle = "启动、重连、重启或停止 IDA 图形界面",
            onClose = { showGuiPanel = false }
        ) {
            GuiFunctionPanel(
                guiState = guiState,
                onLaunchGui = onLaunchGui,
                onStopGui = onStopGui,
                onReconnectGui = onReconnectGui,
                onRestartGui = onRestartGui,
                onOpenSettings = onOpenSettings
            )
        }
        HomePanelOverlay(
            visible = showMcpPanel,
            fromStart = false,
            title = "IDA MCP",
            subtitle = "一键启动、停止或重启 Streamable HTTP MCP",
            onClose = { showMcpPanel = false }
        ) {
            McpFunctionPanel(
                mcpState = mcpState,
                onStartMcp = onStartMcp,
                onStopMcp = onStopMcp,
                onRestartMcp = onRestartMcp,
                onShowMcpLog = onShowMcpLog
            )
        }
        HomePanelOverlay(
            visible = showFilePanel,
            fromStart = false,
            title = "文件浏览器",
            subtitle = "管理 /root/pi_workspace 文件与导入 APK",
            onClose = { showFilePanel = false }
        ) {
            HomeFileBrowserPanel(fileManager = fileManager)
        }
        HomePanelOverlay(
            visible = showMaintenancePanel,
            fromStart = false,
            title = "维护",
            subtitle = "验证环境、快捷命令与重新导入",
            onClose = { showMaintenancePanel = false }
        ) {
            MaintenancePanel(
                lastImportProgress = lastImportProgress,
                validationBusy = validationBusy,
                onRunTerminalCommand = onRunTerminalCommand,
                onChooseArchive = onChooseArchive,
                onRevalidate = onRevalidate,
                onDelete = onDelete
            )
        }
    }
}

@Composable
fun Header(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Text(
                "IDAdroid",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "IDA Pro on Android",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WelcomeCard(onChooseArchive: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("导入 rootfs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("选择 rootfs archive，验证通过后即可使用 IDA / Agent。", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f))
                }
            }
            Button(onClick = onChooseArchive, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("选择 rootfs archive")
            }
        }
    }
}

@Composable
private fun HomeReady(
    metadata: EnvironmentMetadata,
    guiState: GuiSessionState,
    mcpState: IdaMcpSessionState,
    onOpenSettings: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchGui: () -> Unit,
    onOpenGuiPanel: () -> Unit,
    onOpenMcpPanel: () -> Unit,
    onOpenFilePanel: () -> Unit,
    onOpenMaintenancePanel: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        HomeActionCard(
            title = "Agent Chat",
            description = "AI自动逆向",
            icon = Icons.Rounded.SmartToy,
            onClick = onOpenAgent,
            modifier = Modifier.weight(1f),
            containerColor = Color(0xFFEDE7F6),
            contentColor = Color(0xFF5E35B1)
        )
        HomeActionCard(
            title = if (guiState.status == GuiStatus.Running) "打开 Viewer" else if (guiState.status == GuiStatus.Starting) "启动中…" else "启动 IDA",
            description = "图形化分析",
            icon = Icons.Rounded.Visibility,
            onClick = onLaunchGui,
            modifier = Modifier.weight(1f),
            actionIcon = Icons.Rounded.MoreVert,
            onActionClick = onOpenGuiPanel,
            enabled = guiState.status != GuiStatus.Starting,
            loading = guiState.status == GuiStatus.Starting,
            containerColor = Color(0xFFE0F2F1),
            contentColor = Color(0xFF00695C)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        HomeActionCard(
            title = if (mcpState.status == IdaMcpStatus.Running) "MCP 已运行" else if (mcpState.status == IdaMcpStatus.Starting) "MCP 启动中…" else "IDA MCP",
            description = "多客户端接口",
            icon = Icons.Rounded.Api,
            onClick = onOpenMcpPanel,
            modifier = Modifier.weight(1f),
            loading = mcpState.status == IdaMcpStatus.Starting,
            containerColor = Color(0xFFE3F2FD),
            contentColor = Color(0xFF1565C0)
        )
        HomeActionCard(
            title = "文件浏览器",
            description = "容器文件管理",
            icon = Icons.Rounded.Folder,
            onClick = onOpenFilePanel,
            modifier = Modifier.weight(1f),
            containerColor = Color(0xFFFFF3E0),
            contentColor = Color(0xFFE65100)
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        HomeActionCard(
            title = "Terminal",
            description = "proot命令行",
            icon = Icons.Rounded.Terminal,
            onClick = onOpenTerminal,
            modifier = Modifier.weight(1f),
            actionIcon = Icons.Rounded.MoreVert,
            onActionClick = onOpenMaintenancePanel,
            containerColor = Color(0xFFE8F5E9),
            contentColor = Color(0xFF2E7D32)
        )
        HomeActionCard(
            title = "设置",
            description = "运行参数配置",
            icon = Icons.Rounded.Settings,
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
            containerColor = Color(0xFFF3E5F5),
            contentColor = Color(0xFF7B1FA2)
        )
    }
    StatusCard(metadata.validation)
}

@Composable
private fun HomeActionCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector? = null,
    onActionClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    ElevatedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor)
    ) {
        Box(Modifier.fillMaxWidth().padding(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(contentColor.copy(alpha = 0.10f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = contentColor
                        )
                    } else {
                        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
                    }
                }
                Text(title, fontWeight = FontWeight.Black, color = contentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(description, color = contentColor.copy(alpha = .72f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (actionIcon != null && onActionClick != null) {
                IconButton(onClick = onActionClick, modifier = Modifier.align(Alignment.TopEnd).size(34.dp)) {
                    Icon(actionIcon, contentDescription = "更多", tint = contentColor.copy(alpha = .78f), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun HomePanelOverlay(
    visible: Boolean,
    fromStart: Boolean,
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + slideInHorizontally(tween(260)) { full -> if (fromStart) -full else full },
        exit = fadeOut(tween(120)) + slideOutHorizontally(tween(230)) { full -> if (fromStart) -full else full }
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.size(42.dp)) { Icon(Icons.Rounded.Close, contentDescription = "关闭") }
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Black, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}

@Composable
private fun GuiFunctionPanel(
    guiState: GuiSessionState,
    onLaunchGui: () -> Unit,
    onStopGui: () -> Unit,
    onReconnectGui: () -> Unit,
    onRestartGui: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val running = guiState.status == GuiStatus.Running
    val starting = guiState.status == GuiStatus.Starting
    LazyPanelColumn {
        item {
            PanelStatusCard(
                title = "IDA GUI / VNC",
                body = "${guiState.status.name} · port ${guiState.port ?: "-"} · display ${guiState.display?.let { ":$it" } ?: "-"}",
                ok = running
            )
        }
        item {
            Button(onClick = onLaunchGui, enabled = !starting, modifier = Modifier.fillMaxWidth()) {
                if (starting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (running) "打开 Viewer" else if (starting) "启动中…" else "启动 IDA GUI")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onReconnectGui, enabled = running, modifier = Modifier.weight(1f)) { Text("Reconnect") }
                OutlinedButton(onClick = onRestartGui, enabled = !starting, modifier = Modifier.weight(1f)) { Text("Restart") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onStopGui, enabled = running || starting, modifier = Modifier.weight(1f)) { Text("Stop") }
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("VNC 设置") }
            }
        }
        guiState.message.takeIf { it.isNotBlank() }?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun MaintenancePanel(
    lastImportProgress: ImportProgress?,
    validationBusy: Boolean,
    onRunTerminalCommand: (String) -> Unit,
    onChooseArchive: () -> Unit,
    onRevalidate: () -> Unit,
    onDelete: () -> Unit
) {
    LazyPanelColumn {
        if (lastImportProgress?.stage == ImportStage.Error) {
            item {
                StatusBanner(
                    title = "上次导入失败",
                    body = lastImportProgress.error ?: lastImportProgress.message,
                    color = MaterialTheme.colorScheme.errorContainer,
                    icon = Icons.Rounded.ErrorOutline
                )
            }
        }
        item { HomeSectionTitle("快捷命令", Icons.Rounded.Terminal) }
        item { TerminalCommandButtons(onRunTerminalCommand) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onRevalidate, enabled = !validationBusy, modifier = Modifier.weight(1f)) {
                    Text(if (validationBusy) "验证中…" else "重新验证")
                }
                OutlinedButton(onClick = onChooseArchive, modifier = Modifier.weight(1f)) { Text("重新导入") }
            }
        }
        item { TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("删除环境", color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
fun LazyPanelColumn(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
fun PanelStatusCard(title: String, body: String, ok: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).background(if (ok) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Black)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun HomeSectionTitle(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun StatusBanner(
    title: String,
    body: String,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    icon: ImageVector = Icons.Rounded.Info
) {
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TerminalCommandButtons(onRunTerminalCommand: (String) -> Unit) {
    val commands = listOf(
        "pwd" to "pwd",
        "whoami" to "whoami",
        "ls IDA" to "ls ~/ida-pro-9.3",
        "pi --version" to "pi --version",
        "validate" to "/root/pi_workspace/.idadroid/scripts/validate.sh"
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        commands.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, command) ->
                    OutlinedButton(onClick = { onRunTerminalCommand(command) }, modifier = Modifier.weight(1f)) {
                        Text(label)
                    }
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusCard(report: ValidationReport) {
    val accent = if (report.ok) Color(0xFF16A34A) else MaterialTheme.colorScheme.error
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (report.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (report.ok) "环境状态" else "环境需要处理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    Text("arch ${report.arch.ifBlank { "unknown" }} · pi ${report.pi.version.ifBlank { "-" }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RotatingFanIcon(accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CheckItem("IDA", report.ida.exists, Modifier.weight(1f))
                CheckItem("MCP", report.idaMcp.exists && report.idaMcp.executable, Modifier.weight(1f))
                CheckItem("pi", report.pi.path.isNotBlank(), Modifier.weight(1f))
                CheckItem("VNC", report.vnc.mode.isNotBlank(), Modifier.weight(1f))
            }
            val issue = report.fatal.firstOrNull() ?: report.warnings.firstOrNull()
            issue?.let { Text(it, color = if (report.fatal.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun RotatingFanIcon(color: Color) {
    val transition = rememberInfiniteTransition(label = "environment-fan")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "environment-fan-rotation"
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(color.copy(alpha = .12f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_toy_fan),
            contentDescription = "环境运行中",
            tint = color,
            modifier = Modifier
                .size(22.dp)
                .rotate(rotation)
        )
    }
}

@Composable
private fun CheckItem(label: String, ok: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (ok) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (ok) Color(0xFF166534) else MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(label, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

