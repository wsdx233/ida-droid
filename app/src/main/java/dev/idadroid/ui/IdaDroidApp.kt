package dev.idadroid.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Http
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.R
import dev.idadroid.agent.AgentUiState
import dev.idadroid.agent.ChatAttachment
import dev.idadroid.agent.ChatMessage
import dev.idadroid.agent.DraftAttachment
import dev.idadroid.agent.PiAgentManager
import dev.idadroid.agent.pretty
import dev.idadroid.data.EnvironmentMetadata
import dev.idadroid.data.ValidationReport
import dev.idadroid.env.EnvironmentManager
import dev.idadroid.env.EnvironmentState
import dev.idadroid.env.ImportProgress
import dev.idadroid.env.ImportStage
import dev.idadroid.files.ContainerFileEntry
import dev.idadroid.files.ContainerFileManager
import dev.idadroid.mcp.IdaMcpLaunchSettings
import dev.idadroid.mcp.IdaMcpSessionManager
import dev.idadroid.mcp.IdaMcpSessionState
import dev.idadroid.mcp.IdaMcpStatus
import dev.idadroid.settings.IdaDroidSettings
import dev.idadroid.terminal.ProotTerminalActivity
import dev.idadroid.terminal.TerminalLogStore
import dev.idadroid.vnc.GuiSessionState
import dev.idadroid.vnc.GuiStatus
import dev.idadroid.vnc.VncSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class IdaDroidScreen { Home, Settings, Agent }

@Composable
fun IdaDroidApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val context = LocalContext.current
            val manager = remember { EnvironmentManager(context.applicationContext) }
            val settingsStore = remember { IdaDroidSettings(context.applicationContext) }
            val vncManager = remember { VncSessionManager(context.applicationContext, settingsStore) }
            val agentManager = remember { PiAgentManager(context.applicationContext) }
            val fileManager = remember { ContainerFileManager(context.applicationContext) }
            val mcpManager = remember { IdaMcpSessionManager(context.applicationContext) }
            val envState by manager.state.collectAsState()
            val guiState by vncManager.state.collectAsState()
            val agentState by agentManager.state.collectAsState()
            val mcpState by mcpManager.state.collectAsState()
            val vncSettings by settingsStore.vncSettings.collectAsState()
            val scope = rememberCoroutineScope()
            var currentScreen by remember { mutableStateOf(IdaDroidScreen.Home) }
            var importProgress by remember { mutableStateOf<ImportProgress?>(null) }
            var validationBusy by remember { mutableStateOf(false) }
            var transientMessage by remember { mutableStateOf<String?>(null) }
            var showTerminalDiagnostics by remember { mutableStateOf(false) }
            var showVncLog by remember { mutableStateOf(false) }
            var showMcpLog by remember { mutableStateOf(false) }
            var pendingAttachments by remember { mutableStateOf<List<DraftAttachment>>(emptyList()) }

            LaunchedEffect(Unit) {
                manager.refresh()
                agentManager.refresh()
            }
            LaunchedEffect(envState, vncSettings.port) {
                if (envState.isReady) {
                    vncManager.probe()
                    mcpManager.probe()
                    agentManager.refresh(createDefaultIfReady = true)
                }
            }

            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    scope.launch {
                        importProgress = ImportProgress(ImportStage.Preflight, 0f, "准备导入")
                        manager.importRootfs(uri).collect { progress -> importProgress = progress }
                    }
                }
            }

            val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isNotEmpty()) {
                    scope.launch {
                        val loaded = uris.mapNotNull { uri ->
                            runCatching { agentManager.readDraftAttachment(uri) }
                                .onFailure { error -> transientMessage = "读取附件失败：${error.message}" }
                                .getOrNull()
                        }
                        pendingAttachments = pendingAttachments + loaded
                    }
                }
            }

            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(transientMessage) {
                transientMessage
                    ?.takeIf { it.isNotBlank() }
                    ?.let { snackbarHostState.showSnackbar(it) }
            }

            Box(Modifier.fillMaxSize()) {
                val progress = importProgress
                if (progress?.isRunning == true) {
                ImportProgressAnalysisScreen(progress)
            } else if (currentScreen == IdaDroidScreen.Settings) {
                BackHandler { currentScreen = IdaDroidScreen.Home }
                SettingsScreen(
                    settingsStore = settingsStore,
                    onBackClick = { currentScreen = IdaDroidScreen.Home },
                    onShowTerminalLog = { showTerminalDiagnostics = true },
                    onShowVncLog = { showVncLog = true }
                )
            } else if (currentScreen == IdaDroidScreen.Agent) {
                BackHandler { currentScreen = IdaDroidScreen.Home }
                BoxedAgentLikeScreen(
                    envReady = envState.isReady,
                    guiState = guiState,
                    agentState = agentState,
                    manager = agentManager,
                    onBack = { currentScreen = IdaDroidScreen.Home },
                    onLaunchGui = {
                        scope.launch {
                            if (guiState.status == GuiStatus.Starting) return@launch
                            transientMessage = "正在启动 IDA GUI/VNC..."
                            vncManager.startGui(openViewer = true)
                                .onSuccess { transientMessage = it.message }
                                .onFailure { error -> transientMessage = "启动 IDA GUI/VNC 失败：${error.message}" }
                        }
                    },
                    onOpenTerminal = { context.startActivity(Intent(context, ProotTerminalActivity::class.java)) }
                )
            } else {
                MainContent(
                    envState = envState,
                    guiState = guiState,
                    mcpState = mcpState,
                    fileManager = fileManager,
                    lastImportProgress = progress,
                    validationBusy = validationBusy,
                    transientMessage = transientMessage,
                    onChooseArchive = { picker.launch(arrayOf("*/*")) },
                    onOpenSettings = { currentScreen = IdaDroidScreen.Settings },
                    onOpenAgent = { currentScreen = IdaDroidScreen.Agent },
                    onOpenTerminal = {
                        context.startActivity(Intent(context, ProotTerminalActivity::class.java))
                    },
                    onRunTerminalCommand = { command ->
                        context.startActivity(Intent(context, ProotTerminalActivity::class.java).apply {
                            putExtra(ProotTerminalActivity.EXTRA_STARTUP_COMMAND, command)
                        })
                    },
                    onShowTerminalDiagnostics = { showTerminalDiagnostics = true },
                    onShowVncLog = { showVncLog = true },
                    onLaunchGui = {
                        scope.launch {
                            if (guiState.status == GuiStatus.Starting) return@launch
                            transientMessage = "正在启动 IDA GUI/VNC..."
                            vncManager.startGui(openViewer = true)
                                .onSuccess { transientMessage = it.message }
                                .onFailure { error -> transientMessage = "启动 IDA GUI/VNC 失败：${error.message}" }
                        }
                    },
                    onStopGui = {
                        scope.launch {
                            vncManager.stopGui()
                                .onSuccess { transientMessage = "已停止 IDA GUI/VNC" }
                                .onFailure { error -> transientMessage = "停止 GUI 失败：${error.message}" }
                        }
                    },
                    onReconnectGui = {
                        scope.launch {
                            vncManager.connectViewer()
                                .onSuccess { transientMessage = "已打开 VNC viewer" }
                                .onFailure { error -> transientMessage = "打开 viewer 失败：${error.message}" }
                        }
                    },
                    onRestartGui = {
                        scope.launch {
                            if (guiState.status == GuiStatus.Starting) return@launch
                            transientMessage = "正在重启 IDA GUI/VNC..."
                            vncManager.restartGui(openViewer = true)
                                .onSuccess { transientMessage = it.message }
                                .onFailure { error -> transientMessage = "重启 GUI 失败：${error.message}" }
                        }
                    },
                    onStartMcp = { settings ->
                        scope.launch {
                            transientMessage = "正在启动 IDA MCP HTTP..."
                            mcpManager.start(settings)
                                .onSuccess { transientMessage = it.message }
                                .onFailure { error -> transientMessage = "启动 IDA MCP 失败：${error.message}" }
                        }
                    },
                    onStopMcp = {
                        scope.launch {
                            mcpManager.stop()
                                .onSuccess { transientMessage = "已停止 IDA MCP" }
                                .onFailure { error -> transientMessage = "停止 IDA MCP 失败：${error.message}" }
                        }
                    },
                    onRestartMcp = { settings ->
                        scope.launch {
                            transientMessage = "正在重启 IDA MCP HTTP..."
                            mcpManager.restart(settings)
                                .onSuccess { transientMessage = it.message }
                                .onFailure { error -> transientMessage = "重启 IDA MCP 失败：${error.message}" }
                        }
                    },
                    onShowMcpLog = { showMcpLog = true },
                    onRevalidate = {
                        scope.launch {
                            validationBusy = true
                            transientMessage = null
                            runCatching { manager.revalidate() }
                                .onSuccess { report ->
                                    transientMessage = if (report.ok) "验证通过" else "验证失败：${report.fatal.joinToString("；")}" 
                                }
                                .onFailure { error -> transientMessage = "验证异常：${error.message}" }
                            validationBusy = false
                        }
                    },
                    onDelete = {
                        scope.launch {
                            mcpManager.stop()
                            vncManager.stopGui()
                            manager.deleteEnvironment()
                            importProgress = null
                            transientMessage = "已删除 active 环境"
                        }
                    }
                )
            }

            if (showTerminalDiagnostics) {
                val terminalLog = remember(showTerminalDiagnostics) { TerminalLogStore(context.applicationContext).readTail() }
                AlertDialog(
                    onDismissRequest = { showTerminalDiagnostics = false },
                    confirmButton = {
                        TextButton(onClick = { showTerminalDiagnostics = false }) { Text("关闭") }
                    },
                    title = { Text("终端启动日志") },
                    text = {
                        Column(
                            modifier = Modifier
                                .height(360.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                terminalLog.ifBlank { "暂无日志。打开终端后，启动命令、pid、exit code 和 TerminalView 错误会写入这里。" },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                )
            }

            if (showVncLog) {
                val vncLog = remember(showVncLog, guiState) { vncManager.readLogTail() }
                AlertDialog(
                    onDismissRequest = { showVncLog = false },
                    confirmButton = {
                        TextButton(onClick = { showVncLog = false }) { Text("关闭") }
                    },
                    title = { Text("IDA GUI/VNC 日志") },
                    text = {
                        Column(
                            modifier = Modifier
                                .height(420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                vncLog.ifBlank { "暂无 VNC 日志。启动 GUI 后会写入 /root/pi_workspace/.idadroid/logs/ida-vnc.log。" },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                )
            }

            if (showMcpLog) {
                val mcpLog = remember(showMcpLog, mcpState) { mcpManager.readLogTail() }
                AlertDialog(
                    onDismissRequest = { showMcpLog = false },
                    confirmButton = {
                        TextButton(onClick = { showMcpLog = false }) { Text("关闭") }
                    },
                    title = { Text("IDA MCP 日志") },
                    text = {
                        Column(
                            modifier = Modifier
                                .height(420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                mcpLog.ifBlank { "暂无 MCP 日志。启动 IDA MCP 后会写入 ida-mcp-http.log。" },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                )
            }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }
}
