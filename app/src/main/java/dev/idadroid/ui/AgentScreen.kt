@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.idadroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.idadroid.agent.AgentSessionRecord
import dev.idadroid.agent.AgentUiState
import dev.idadroid.agent.ChatMessage
import dev.idadroid.agent.PiAgentManager
import dev.idadroid.vnc.GuiSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class AgentPanel { Sessions, Tools, Configuration, Workspace, Search }

@Composable
fun AgentScreen(
    envReady: Boolean,
    guiState: GuiSessionState,
    agentState: AgentUiState,
    manager: PiAgentManager,
    onBack: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onLaunchGui: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AgentDraftStore(context) }
    val sessionId = agentState.activeSessionId
    val latestSessionId by rememberUpdatedState(sessionId)
    val draft = remember(store.revision, sessionId) { sessionId?.let(store::get) ?: AgentDraft() }
    var panel by rememberSaveable { mutableStateOf<AgentPanel?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var followLatest by rememberSaveable(sessionId) { mutableStateOf(true) }
    val session = agentState.activeSession
    val busy = agentState.isWorking || agentState.status == "starting"

    fun updateText(value: String) {
        sessionId?.let { id -> store.put(id, store.get(id).copy(text = value)) }
    }
    fun insertText(value: String) {
        sessionId?.let { id -> val current = store.get(id); store.put(id, current.copy(text = appendComposerText(current.text, value))) }
    }
    suspend fun send(mode: String?) {
        val id = sessionId ?: return
        if (preparing) return
        val submitted = store.get(id)
        preparing = true
        try {
            val payload = store.materialize(submitted)
            if (latestSessionId != id) {
                feedback = "Session 已切换，消息仍保留在原会话草稿中。"
            } else if (manager.sendPrompt(submitted.text, payload, mode)) {
                store.clearAccepted(id, submitted)
                followLatest = true
                feedback = null
            } else feedback = "消息未被接收。草稿已保留，请检查模型与当前任务状态。"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            feedback = "发送失败，草稿已保留：${error.message.orEmpty()}"
        } finally {
            preparing = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = pickerSessionId
        pickerSessionId = null
        if (target != null && uris.isNotEmpty()) scope.launch {
            preparing = true
            try {
                for (uri in uris) store.attach(target, manager.readDraftAttachment(uri))
                feedback = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                feedback = "部分附件未添加：${error.message.orEmpty()}。已添加的附件仍保留。"
            } finally { preparing = false }
        }
    }

    // User movement, not incoming deltas, changes follow mode. A tall last message is
    // not considered 'near the bottom' merely because its item is visible.
    LaunchedEffect(listState, sessionId) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) snapshotFlow {
                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()
                !listState.canScrollForward || (last?.index == layout.totalItemsCount - 1 && last.offset + last.size <= layout.viewportEndOffset + 96)
            }.collect { followLatest = it }
        }
    }
    LaunchedEffect(sessionId, agentState.messagesLoading, agentState.messages, busy) {
        if (!agentState.messagesLoading && followLatest && !listState.isScrollInProgress) {
            androidx.compose.runtime.withFrameNanos { }
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.scrollToItem(last)
        }
    }
    BackHandler { if (panel != null) panel = null else onBack() }

    if (!envReady) {
        AgentEnvironmentGate(onOpenEnvironment, onBack)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Surface(Modifier.width(280.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                AgentSessions(agentState, manager, onSelected = {}, onBack = onBack)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                AgentTopBar(
                    session, agentState,
                    onSessions = { panel = AgentPanel.Sessions },
                    onNew = { manager.createSession() },
                    onSearch = { panel = AgentPanel.Search },
                    onWorkspace = { panel = AgentPanel.Workspace },
                    onTools = { panel = AgentPanel.Tools },
                    onConfigure = { panel = AgentPanel.Configuration },
                    onRefresh = { manager.refresh(createDefaultIfReady = true); manager.loadMessages() },
                    onBack = onBack
                )
                if (session == null) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("开始一项新任务", style = MaterialTheme.typography.headlineSmall)
                            Text("会话、文件与工具都留在你的设备上。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { manager.createSession() }) { Text("新建 Session") }
                            OutlinedButton(onClick = { panel = AgentPanel.Configuration }) { Text("配置 Provider / Model") }
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        LazyColumn(
                            state = listState, modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            // Always one header: search indices remain stable in loading/error states.
                            item(key = "timeline-header") {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (agentState.messagesLoading) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Text("正在读取会话…", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (!agentState.canUseActiveSession) {
                                        OutlinedCard(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("选择模型后即可开始", style = MaterialTheme.typography.titleMedium)
                                                Text("配置 API 凭据、Provider 和 Model，或在输入栏选择已有模型。")
                                                TextButton(onClick = { panel = AgentPanel.Configuration }) { Text("打开 Agent 配置") }
                                            }
                                        }
                                    }
                                    agentState.error?.takeIf(String::isNotBlank)?.let { error ->
                                        AgentErrorCard(error, onRetry = { manager.startSession(session.id); manager.loadMessages() }, onDetails = { detail = listOf(error, agentState.stderrTail).filter(String::isNotBlank).joinToString("\n\n") })
                                    }
                                    if (!agentState.messagesLoading && agentState.messages.isEmpty()) AgentWelcome { insertText(it) }
                                }
                            }
                            items(agentState.messages, key = { it.id }) { message ->
                                MessageBubble(
                                    message, autoOpenProgress = false,
                                    isLatestMessage = message.id == agentState.messages.lastOrNull()?.id,
                                    streaming = busy && agentState.processingPhase == "receiving" && message.id == agentState.streamingMessageId,
                                    onShowDialog = { detail = message.text.ifBlank { message.toolResult.orEmpty() } },
                                    onSaveToWorkspace = { saveMessage = message }
                                )
                            }
                            item(key = "timeline-end") { Spacer(Modifier.height(1.dp)) }
                        }
                        if (!followLatest && agentState.messages.isNotEmpty()) FilledTonalButton(
                            onClick = { followLatest = true; scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1) } },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        ) { Icon(Icons.Rounded.ArrowDownward, null); Spacer(Modifier.width(8.dp)); Text("回到最新") }
                    }
                    Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally).imePadding()) {
                        if (busy) AgentProcessingStatus(agentState)
                        else if (agentState.activity.isNotBlank()) Text(agentState.activity, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        feedback?.let { value ->
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IconButton(onClick = { feedback = null }) { Icon(Icons.Rounded.Close, "关闭提示") }
                            }
                        }
                        AgentComposer(
                            draft, agentState, manager, preparing,
                            onText = ::updateText,
                            onAttach = { pickerSessionId = sessionId; picker.launch(arrayOf("*/*")) },
                            onRemove = { item -> sessionId?.let { id -> scope.launch { store.remove(id, item) } } },
                            onSend = ::send,
                            onConfigure = { panel = AgentPanel.Configuration }
                        )
                    }
                }
            }
        }
        when (panel) {
            AgentPanel.Sessions -> AgentFullPanel("Sessions", onClose = { panel = null }) { AgentSessions(agentState, manager, onSelected = { panel = null }) }
            AgentPanel.Tools -> AgentFullPanel("工作区与工具", onClose = { panel = null }) {
                ToolsSidePane(agentState, guiState, manager, onOpenTerminal, onLaunchGui, onClose = { panel = null }, onOpenModelsEditor = { panel = AgentPanel.Configuration }, onInsertComposer = { insertText(it); panel = null })
            }
            AgentPanel.Configuration -> AgentFullPanel("Agent 配置", onClose = { panel = null }) { PiSettingsTab(agentState, manager) }
            AgentPanel.Workspace -> AgentFullPanel("工作区", onClose = { panel = null }) {
                WorkspaceSidePane(agentState, manager, onClose = { panel = null }, onInsertComposer = { insertText(it); panel = null })
            }
            AgentPanel.Search -> SearchMessagesSheet(agentState.messages, onDismiss = { panel = null }, onJump = { message ->
                panel = null
                followLatest = false
                val index = agentState.messages.indexOfFirst { it.id == message.id }
                if (index >= 0) scope.launch { listState.animateScrollToItem(index + 1) }
            })
            null -> Unit
        }
    }
    detail?.let { MessageDialog(it, onDismiss = { detail = null }) }
    saveMessage?.let { message -> InputDialogLite("保存到工作区", "reply-${message.timestamp}", onDismiss = { saveMessage = null }, onConfirm = { name ->
        manager.saveAssistantMessageToWorkspace(message.id, name) { ok, info -> feedback = if (ok) "已保存：$info" else "保存失败：$info" }
        saveMessage = null
    }) }
}

@Composable
private fun AgentFullPanel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回对话") } })
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun AgentEnvironmentGate(onConfigure: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.Terminal, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("先准备工作环境", style = MaterialTheme.typography.headlineSmall)
            Text("导入并验证 rootfs 后，PI Agent 会通过本机 RPC 在 Linux 工作区中运行。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) { Text("导入 / 验证 rootfs") }
            TextButton(onClick = onBack) { Text("返回首页") }
        }
    }
}

@Composable
private fun AgentTopBar(session: AgentSessionRecord?, state: AgentUiState, onSessions: () -> Unit, onNew: () -> Unit, onSearch: () -> Unit, onWorkspace: () -> Unit, onTools: () -> Unit, onConfigure: () -> Unit, onRefresh: () -> Unit, onBack: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Column { Text(session?.name ?: "IDAdroid Agent", maxLines = 1, overflow = TextOverflow.Ellipsis); Text(session?.model ?: "本机 PI · RPC", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        navigationIcon = { IconButton(onClick = onSessions) { Icon(Icons.Rounded.Menu, "会话列表") } },
        actions = {
            IconButton(onClick = onNew) { Icon(Icons.Rounded.Add, "新建会话") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "会话操作") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("搜索消息") }, onClick = { menu = false; onSearch() })
                DropdownMenuItem(text = { Text("工作区 · ${state.workspace.workspaceName.ifBlank { "选择文件夹" }}") }, onClick = { menu = false; onWorkspace() })
                DropdownMenuItem(text = { Text("Terminal / Files / IDA") }, onClick = { menu = false; onTools() })
                DropdownMenuItem(text = { Text("Agent 配置") }, onClick = { menu = false; onConfigure() })
                DropdownMenuItem(text = { Text("刷新会话") }, onClick = { menu = false; onRefresh() })
                DropdownMenuItem(text = { Text("返回首页") }, onClick = { menu = false; onBack() })
            }
        }
    )
}

@Composable
private fun AgentSessions(state: AgentUiState, manager: PiAgentManager, onSelected: () -> Unit, onBack: (() -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var rename by remember { mutableStateOf<AgentSessionRecord?>(null) }
    var delete by remember { mutableStateOf<AgentSessionRecord?>(null) }
    val filtered = remember(state.sessions, query) { state.sessions.filter { "${it.name} ${it.provider} ${it.model}".contains(query, ignoreCase = true) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (onBack != null) TextButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Text("IDAdroid") }
        Button(onClick = { manager.createSession(); onSelected() }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("新建 Session") }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("搜索会话") }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { item ->
                var actions by remember { mutableStateOf(false) }
                Surface(color = if (item.id == state.activeSessionId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().clickable { manager.selectSession(item.id); onSelected() }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
                            Text(item.name.ifBlank { "Session" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(item.model ?: "选择模型", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            IconButton(onClick = { actions = true }) { Icon(Icons.Rounded.MoreVert, "管理会话 ${item.name}") }
                            DropdownMenu(actions, onDismissRequest = { actions = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { actions = false; rename = item })
                                DropdownMenuItem(text = { Text(if (item.status in listOf("running", "working", "starting")) "停止进程" else "启动进程") }, onClick = { actions = false; if (item.status in listOf("running", "working", "starting")) manager.stopSession(item.id) else manager.startSession(item.id) })
                                DropdownMenuItem(text = { Text("删除会话", color = MaterialTheme.colorScheme.error) }, onClick = { actions = false; delete = item })
                            }
                        }
                    }
                }
            }
            if (filtered.isEmpty()) item { Text(if (query.isBlank()) "暂无会话" else "没有匹配的会话", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    rename?.let { item -> InputDialogLite("重命名会话", item.name, onDismiss = { rename = null }, onConfirm = { manager.renameSession(item.id, it); rename = null }) }
    delete?.let { item -> ConfirmDialogLite("删除会话", "删除「${item.name}」？此操作不可撤销。", onDismiss = { delete = null }, onConfirm = { manager.deleteSession(item.id); delete = null }) }
}

@Composable
private fun AgentWelcome(onPrompt: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("你想探索什么？", style = MaterialTheme.typography.headlineSmall)
        Text("描述目标，查看思考与工具执行过程。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onPrompt("确认当前 IDAdroid 环境，说明可用工具。") }) { Text("确认环境与可用工具") }
        TextButton(onClick = { onPrompt("请先阅读 /root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md，然后连接当前 IDA 并运行 MCP 诊断。") }) { Text("连接 IDA MCP") }
    }
}

@Composable
private fun AgentErrorCard(error: String, onRetry: () -> Unit, onDetails: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row { TextButton(onClick = onRetry) { Text("重新连接") }; TextButton(onClick = onDetails) { Text("查看诊断") } }
            Text("可在输入栏编辑并重新发送；不会自动重复执行任务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun AgentProcessingStatus(state: AgentUiState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, state.promptSentAt) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) { now = System.currentTimeMillis(); delay(1000) } }
    }
    val elapsed = if (state.promptSentAt > 0L) (now - state.promptSentAt).coerceAtLeast(0L) / 1000 else 0
    val label = when {
        state.status == "starting" -> "正在启动 PI"
        state.processingPhase == "connecting" -> "等待 Agent 响应"
        state.processingPhase == "receiving" -> "正在接收回复"
        state.processingPhase == "executing_tool" -> "正在执行工具"
        state.processingPhase == "compacting" -> "正在压缩上下文"
        else -> "PI 正在处理"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text("$label · ${elapsed}s", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
