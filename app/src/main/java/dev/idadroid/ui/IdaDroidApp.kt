package dev.idadroid.ui

import android.os.Build
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.R
import dev.idadroid.agent.DraftAttachment
import dev.idadroid.agent.PiAgentManager
import dev.idadroid.env.EnvironmentManager
import dev.idadroid.env.ImportProgress
import dev.idadroid.env.ImportStage
import dev.idadroid.files.ContainerFileManager
import dev.idadroid.files.RootfsFileSharing
import dev.idadroid.mcp.IdaMcpSessionManager
import dev.idadroid.settings.IdaDroidSettings
import dev.idadroid.terminal.ProotTerminalActivity
import dev.idadroid.terminal.TerminalLogStore
import dev.idadroid.vnc.GuiStatus
import dev.idadroid.vnc.VncSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class IdaDroidScreen { Home, Settings, Agent, About }

@Composable
fun IdaDroidApp(
    initialAgent: Boolean = false,
    agentRequest: StateFlow<Int>? = null
) {
    val context = LocalContext.current
    val settingsStore = remember { IdaDroidSettings(context.applicationContext) }
    val appearance by settingsStore.appearanceSettings.collectAsState()

    val isDark = when (appearance.themeMode) {
        IdaDroidSettings.THEME_DARK -> true
        IdaDroidSettings.THEME_LIGHT -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    val colorScheme = when {
        appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val manager = remember { EnvironmentManager(context.applicationContext) }
            val vncManager = remember { VncSessionManager(context.applicationContext, settingsStore) }
            val agentManager = remember { PiAgentManager(context.applicationContext) }
            val fileManager = remember { ContainerFileManager(context.applicationContext) }
            val mcpManager = remember { IdaMcpSessionManager.get(context.applicationContext, settingsStore) }
            val envState by manager.state.collectAsState()
            val guiState by vncManager.state.collectAsState()
            val agentState by agentManager.state.collectAsState()
            val mcpState by mcpManager.state.collectAsState()
            val vncSettings by settingsStore.vncSettings.collectAsState()
            val scope = rememberCoroutineScope()
            var currentScreen by remember { mutableStateOf(if (initialAgent) IdaDroidScreen.Agent else IdaDroidScreen.Home) }
            // React to Agent-screen requests arriving while the activity already
            // exists (floating window "Agent 聊天" routes through onNewIntent).
            val agentRequestTick by (agentRequest ?: remember { MutableStateFlow(0) }).collectAsState()
            LaunchedEffect(agentRequestTick) {
                if (agentRequestTick > 0) currentScreen = IdaDroidScreen.Agent
            }
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
                            val result = runCatching { agentManager.readDraftAttachment(uri) }
                            // runCatching 会吞 CancellationException，重新抛出以保留取消信号
                            val cause = result.exceptionOrNull()
                            if (cause is kotlinx.coroutines.CancellationException) throw cause
                            result
                                .onFailure { error -> transientMessage = "读取附件失败：${error.message}" }
                                .getOrNull()
                        }
                        pendingAttachments = pendingAttachments + loaded
                    }
                }
            }

            val mcpUploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    scope.launch {
                        val uploadResult = runCatching { mcpManager.transfers.transferUri(uri) }
                        // runCatching 会吞 CancellationException，重新抛出以保留取消信号
                        val uploadCause = uploadResult.exceptionOrNull()
                        if (uploadCause is kotlinx.coroutines.CancellationException) throw uploadCause
                        uploadResult
                            .onSuccess { entry ->
                                transientMessage = "已上传到 MCP: ${entry.name} → ${entry.prootPath}"
                            }
                            .onFailure { error ->
                                transientMessage = "MCP 上传失败: ${error.message}"
                            }
                    }
                }
            }

            val openInIdaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    scope.launch {
                        transientMessage = "正在传输并在 IDA 中打开…"
                        // 先把文件传进容器，拿到容器内路径，再调用 ida-mcp open_file
                        val entry = runCatching { mcpManager.transfers.transferUri(uri) }
                        // runCatching 会吞 CancellationException，重新抛出以保留取消信号
                        val entryCause = entry.exceptionOrNull()
                        if (entryCause is kotlinx.coroutines.CancellationException) throw entryCause
                        entry.onSuccess { e ->
                            mcpManager.openInIda(hostPath = e.hostPath)
                                .onSuccess { toolResult ->
                                    transientMessage = "已在 IDA 中打开：${toolResult.take(120)}"
                                }
                                .onFailure { error ->
                                    transientMessage = "在 IDA 中打开失败：${error.message}"
                                }
                        }.onFailure { error ->
                            transientMessage = "文件传输失败：${error.message}"
                        }
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
                    onShowVncLog = { showVncLog = true },
                    onShowMcpLog = { showMcpLog = true }
                )
            } else if (currentScreen == IdaDroidScreen.Agent) {
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
            } else if (currentScreen == IdaDroidScreen.About) {
                BackHandler { currentScreen = IdaDroidScreen.Home }
                AboutScreen(onBackClick = { currentScreen = IdaDroidScreen.Home })
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
                    onOpenAbout = { currentScreen = IdaDroidScreen.About },
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
                    onToggleMcpMonitoring = { enabled ->
                        mcpManager.setMonitoringEnabled(enabled)
                    },
                    onMcpHealthCheck = {
                        scope.launch {
                            val ok = mcpManager.healthCheck()
                            transientMessage = if (ok) "健康检查通过" else "健康检查失败"
                        }
                    },
                    onUploadToMcp = {
                        mcpUploadPicker.launch(arrayOf("*/*"))
                    },
                    onOpenInIda = {
                        openInIdaPicker.launch(arrayOf("*/*"))
                    },
                    onRevalidate = {
                        scope.launch {
                            validationBusy = true
                            transientMessage = null
                            val revalidateResult = runCatching { manager.revalidate() }
                            // runCatching 会吞 CancellationException，重新抛出以保留取消信号
                            val revalidateCause = revalidateResult.exceptionOrNull()
                            if (revalidateCause is kotlinx.coroutines.CancellationException) throw revalidateCause
                            revalidateResult
                                .onSuccess { report ->
                                    transientMessage = if (report.ok) "验证通过" else "验证失败：${report.fatal.joinToString("；")}"
                                }
                                .onFailure { error -> transientMessage = "验证异常：${error.message}" }
                            validationBusy = false
                        }
                    },
                    onDelete = {
                        scope.launch {
                            // 即使 MCP/VNC 停止失败也继续删除环境，避免残留状态；
                            // 但把异常汇总展示给用户，避免静默失败造成误解。
                            // 注意：必须使用 runCatchingSuspending，普通 runCatching 会
                            // 吞掉 CancellationException 破坏协程取消语义。
                            val errors = buildList {
                                dev.idadroid.util.runCatchingSuspending { mcpManager.stop() }
                                    .exceptionOrNull()?.let { add("MCP: ${it.message}") }
                                dev.idadroid.util.runCatchingSuspending { vncManager.stopGui() }
                                    .exceptionOrNull()?.let { add("VNC: ${it.message}") }
                                dev.idadroid.util.runCatchingSuspending { manager.deleteEnvironment() }
                                    .exceptionOrNull()?.let { add("Env: ${it.message}") }
                            }
                            importProgress = null
                            transientMessage = if (errors.isEmpty()) "已删除 active 环境" else "已删除 active 环境（部分组件异常：${errors.joinToString("；")}）"
                        }
                    }
                )
            }

            if (showTerminalDiagnostics) {
                // 异步加载终端日志，避免在主线程上同步读取文件导致 ANR
                var terminalLog by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(showTerminalDiagnostics) {
                    if (!showTerminalDiagnostics) return@LaunchedEffect
                    terminalLog = withContext(Dispatchers.IO) {
                        TerminalLogStore(context.applicationContext).readTail()
                    }
                }
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
                                (terminalLog ?: "加载中…").ifBlank { "暂无日志。打开终端后，启动命令、pid、exit code 和 TerminalView 错误会写入这里。" },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                )
            }

            if (showVncLog) {
                // 异步加载 VNC 日志，避免在主线程上同步读取文件导致 ANR
                var vncLog by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(showVncLog, guiState) {
                    if (!showVncLog) return@LaunchedEffect
                    vncLog = withContext(Dispatchers.IO) { vncManager.readLogTail() }
                }
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
                                (vncLog ?: "加载中…").ifBlank { "暂无 VNC 日志。启动 GUI 后会写入 /root/pi_workspace/.idadroid/logs/ida-vnc.log。" },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                )
            }

            if (showMcpLog) {
                // 异步加载 MCP 日志，避免在主线程上同步读取文件导致 ANR
                var mcpLog by remember { mutableStateOf<String?>(null) }
                var exportingLog by remember { mutableStateOf(false) }
                LaunchedEffect(showMcpLog, mcpState) {
                    if (!showMcpLog) return@LaunchedEffect
                    mcpLog = withContext(Dispatchers.IO) { mcpManager.readLogTail() }
                }
                AlertDialog(
                    onDismissRequest = { showMcpLog = false },
                    confirmButton = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!exportingLog) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            exportingLog = true
                                            kotlin.runCatching {
                                                val exported = withContext(Dispatchers.IO) {
                                                    val src = mcpManager.logFile()
                                                    require(src.isFile && src.length() > 0) { "暂无 MCP 日志文件" }
                                                    val sharedDir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
                                                    val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                                                        .format(java.util.Date())
                                                    val dest = java.io.File(sharedDir, "ida-mcp-http-$ts.log")
                                                    src.copyTo(dest, overwrite = true)
                                                    dest
                                                }
                                                RootfsFileSharing.shareFile(context, exported, "text/plain")
                                            }
                                                .onSuccess { transientMessage = "已导出 MCP 日志" }
                                                .onFailure { e -> transientMessage = "导出 MCP 日志失败：${e.message}" }
                                            exportingLog = false
                                        }
                                    }
                                ) { Text("导出分享") }
                            }
                            TextButton(onClick = { showMcpLog = false }) { Text("关闭") }
                        }
                    },
                    title = { Text("IDA MCP 日志") },
                    text = {
                        Column(
                            modifier = Modifier
                                .height(420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                (mcpLog ?: "加载中…").ifBlank { "暂无 MCP 日志。启动 IDA MCP 后会写入 ida-mcp-http.log。" },
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
