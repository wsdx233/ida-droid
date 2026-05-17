package dev.idadroid.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.AgentSessionRecord
import dev.idadroid.agent.AgentUiState
import dev.idadroid.agent.ChatAttachment
import dev.idadroid.agent.ChatMessage
import dev.idadroid.agent.DraftAttachment
import dev.idadroid.agent.FileEntry
import dev.idadroid.agent.PiAgentManager
import dev.idadroid.agent.PiConfigSnapshot
import dev.idadroid.agent.PiModel
import dev.idadroid.agent.SessionStats
import dev.idadroid.agent.attachmentSummary
import dev.idadroid.agent.pretty
import dev.idadroid.agent.providerNameOrNull
import dev.idadroid.vnc.GuiSessionState
import dev.idadroid.vnc.GuiStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.max

private enum class AgentToolTab { Terminal, Files, Pi, Ida }
private val ThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh")
private val UiJson = Json { prettyPrint = true; ignoreUnknownKeys = true; explicitNulls = false }
private val MarkdownParagraphBreakRegex = Regex("\\n{2,}")
private val MarkdownHeadingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val InlineMarkdownRegex = Regex("(\\*\\*[^*]+\\*\\*|(?<!\\*)\\*[^*\\n]+\\*(?!\\*)|`[^`]+`|\\[[^\\]]+\\]\\([^)]+\\))")
private const val TOOL_CODE_COLLAPSED_CHARS = 12_000
private fun ColorScheme.isDarkLike(): Boolean = background.luminance() < 0.5f || surface.luminance() < 0.5f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BoxedAgentLikeScreen(
    envReady: Boolean,
    guiState: GuiSessionState,
    agentState: AgentUiState,
    manager: PiAgentManager,
    onBack: () -> Unit,
    onLaunchGui: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(agentState.activeSessionId) { mutableStateOf("") }
    var attachments by remember(agentState.activeSessionId) { mutableStateOf<List<DraftAttachment>>(emptyList()) }
    var showSessions by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showThinkingMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showCompactMenu by remember { mutableStateOf(false) }
    var showSendModeMenu by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var dialogMessage by remember { mutableStateOf<String?>(null) }
    var quickActionsVisible by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = uris.mapNotNull { uri -> runCatching { manager.readDraftAttachment(uri) }.getOrNull() }
            attachments = attachments + picked
            val refs = picked.joinToString(" ") { manager.fileRef("/root/pi_workspace/.upload/${it.name}") }
            text = appendComposerText(text, refs)
        }
    }

    if (!envReady) {
        CenterWelcomeBox("环境未 ready", "请先导入并验证 rootfs，然后再打开 Agent Chat。", null)
        return
    }

    BackHandler(enabled = showSessions || showTools) {
        showSessions = false
        showTools = false
    }

    val session = agentState.activeSession
    LaunchedEffect(session?.id, session?.status) {
        if (session != null && session.status in setOf("idle", "stopped")) manager.startSession(session.id)
    }
    val canSend = text.isNotBlank() || attachments.isNotEmpty()
    val lastMessage = agentState.messages.lastOrNull()
    val latestProgress = agentState.messages.lastOrNull { it.role == "tool" || (it.role == "assistant" && it.thinking?.isNotBlank() == true) }
    val autoOpenProgressId = if (agentState.isWorking) latestProgress?.id else null
    val latestProgressArgsKey = latestProgress?.toolArgs?.toString()?.let { "${it.length}:${it.hashCode()}" }
    val stickToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            total == 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 3
        }
    }

    LaunchedEffect(
        agentState.messages.size,
        lastMessage?.id,
        lastMessage?.text?.length,
        lastMessage?.thinking?.length,
        lastMessage?.toolResult?.length,
        lastMessage?.toolStatus,
        latestProgress?.id,
        latestProgress?.thinking?.length,
        latestProgress?.toolResult?.length,
        latestProgressArgsKey,
        latestProgress?.toolStatus,
        agentState.isWorking
    ) {
        if ((agentState.messages.isNotEmpty() || agentState.isWorking) && stickToBottom) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.scrollToItem(total - 1)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) quickActionsVisible = false
                else {
                    quickActionsVisible = true
                    delay(2400)
                    quickActionsVisible = false
                }
            }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (session == null) {
            EmptyAgentTopBar(onSessions = { showSessions = true }, onTools = { showTools = true })
            CenterWelcomeBox("选择或创建 Session", "点击左上角打开 Sessions。", null)
        } else {
            ChatTopBar(
                session = session,
                stats = agentState.activeStats,
                autoCompact = session.autoCompactionEnabled != false,
                isWorking = agentState.isWorking,
                onSessions = { showSessions = true },
                onTools = { showTools = true },
                onRefresh = { manager.refresh(createDefaultIfReady = true); manager.loadMessages() }
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (agentState.messagesLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (!agentState.messagesLoading && agentState.messages.isEmpty()) item { WelcomePrompts(onPrompt = { text = it }) }
                    items(agentState.messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            autoOpenProgress = msg.id == autoOpenProgressId,
                            isLatestMessage = msg.id == lastMessage?.id,
                            streaming = agentState.isWorking && msg.role == "assistant" && msg.id == agentState.messages.lastOrNull { it.role == "assistant" }?.id,
                            onShowDialog = { dialogMessage = msg.text.ifBlank { msg.toolResult.orEmpty() } }
                        )
                    }
                    if (agentState.isWorking) item { ProcessingCard() }
                    item { Spacer(Modifier.height(1.dp)) }
                }
                ScrollQuickActions(
                    visible = quickActionsVisible && agentState.messages.any { it.role == "user" },
                    listState = listState,
                    messages = agentState.messages,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                )
            }
            Box(Modifier.fillMaxWidth().imePadding()) {
                ChatComposer(
                    text = text,
                    onTextChange = { text = it },
                    attachments = attachments,
                    onRemoveAttachment = { attachment ->
                        attachments = attachments - attachment
                        text = removeComposerRef(text, "/root/pi_workspace/.upload/${attachment.name}")
                    },
                    onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onSearchClick = { showSearch = true },
                    canSend = canSend,
                    isWorking = agentState.isWorking,
                    thinking = session.thinkingLevel ?: "medium",
                    model = session.model ?: "模型",
                    autoCompact = session.autoCompactionEnabled != false,
                    showThinkingMenu = showThinkingMenu,
                    onShowThinkingMenu = { showThinkingMenu = it },
                    showModelMenu = showModelMenu,
                    onShowModelMenu = { showModelMenu = it; if (it) manager.loadSessionModels() },
                    showCompactMenu = showCompactMenu,
                    onShowCompactMenu = { showCompactMenu = it },
                    showSendModeMenu = showSendModeMenu,
                    onShowSendModeMenu = { showSendModeMenu = it },
                    state = agentState,
                    manager = manager,
                    onSend = { mode ->
                        manager.sendPrompt(text, attachments, mode)
                        text = ""
                        attachments = emptyList()
                    },
                    onAbort = { manager.abort() }
                )
            }
        }
    }

    SideOverlay(
        visible = showSessions,
        fromStart = true,
        title = "Sessions",
        subtitle = "本机 pi RPC 会话",
        onClose = { showSessions = false }
    ) {
        SessionsSidePane(state = agentState, manager = manager, onClose = { showSessions = false })
    }
    SideOverlay(
        visible = showTools,
        fromStart = false,
        title = "Tools",
        subtitle = "Terminal、Files、Pi、IDA",
        onClose = { showTools = false }
    ) {
        ToolsSidePane(
            state = agentState,
            guiState = guiState,
            manager = manager,
            onOpenTerminal = onOpenTerminal,
            onLaunchGui = onLaunchGui,
            onClose = { showTools = false },
            onInsertComposer = { insert -> text = appendComposerText(text, insert) }
        )
    }
    }
    if (showSearch) {
        SearchMessagesSheet(
            agentState.messages,
            onDismiss = { showSearch = false },
            onJump = { msg ->
                showSearch = false
                val index = agentState.messages.indexOfFirst { it.id == msg.id }
                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
            }
        )
    }
    dialogMessage?.let { MessageDialog(it, onDismiss = { dialogMessage = null }) }
}

@Composable
private fun SideOverlay(
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
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
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
private fun SessionsSidePane(state: AgentUiState, manager: PiAgentManager, onClose: () -> Unit) {
    var rename by remember { mutableStateOf<AgentSessionRecord?>(null) }
    var delete by remember { mutableStateOf<AgentSessionRecord?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Button(onClick = { manager.createSession(); onClose() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("新建 Session")
            }
        }
        if (state.sessions.isEmpty()) {
            item { EmptySideCard("还没有 Session", "新建后即可开始 pi Agent 对话。") }
        }
        items(state.sessions, key = { it.id }) { session ->
            SessionRow(
                session = session,
                active = session.id == state.activeSessionId,
                onSelect = { manager.selectSession(session.id); onClose() },
                onStartStop = { if (session.status == "running" || session.status == "working") manager.stopSession(session.id) else manager.startSession(session.id) },
                onRename = { rename = session },
                onDelete = { delete = session }
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    rename?.let { s -> InputDialogLite("重命名 Session", s.name, onDismiss = { rename = null }, onConfirm = { manager.renameSession(s.id, it); rename = null }) }
    delete?.let { s -> ConfirmDialogLite("删除 Session", "删除 ${s.name}?", onDismiss = { delete = null }, onConfirm = { manager.deleteSession(s.id); delete = null }) }
}

@Composable
private fun ToolsSidePane(
    state: AgentUiState,
    guiState: GuiSessionState,
    manager: PiAgentManager,
    onOpenTerminal: () -> Unit,
    onLaunchGui: () -> Unit,
    onClose: () -> Unit,
    onInsertComposer: (String) -> Unit
) {
    var tab by remember { mutableStateOf(AgentToolTab.Files) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal) {
            AgentToolTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = { Icon(when (t) { AgentToolTab.Terminal -> Icons.Rounded.Terminal; AgentToolTab.Files -> Icons.Rounded.Folder; AgentToolTab.Pi -> Icons.Rounded.Settings; AgentToolTab.Ida -> Icons.Rounded.Code }, contentDescription = null) },
                    text = { Text(when (t) { AgentToolTab.Terminal -> "Terminal"; AgentToolTab.Files -> "Files"; AgentToolTab.Pi -> "Pi"; AgentToolTab.Ida -> "IDA" }) }
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                AgentToolTab.Terminal -> TerminalToolTab(onOpenTerminal)
                AgentToolTab.Files -> FileBrowserTab(manager, onInsertComposer)
                AgentToolTab.Pi -> PiSettingsTab(state, manager)
                AgentToolTab.Ida -> IdaToolTab(guiState, onLaunchGui, onOpenTerminal, onInsertComposer, onClose)
            }
        }
    }
}

@Composable
private fun EmptySideCard(title: String, subtitle: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScrollQuickActions(visible: Boolean, listState: LazyListState, messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val total = listState.layoutInfo.totalItemsCount
    val userIndexes = remember(messages) { messages.mapIndexedNotNull { index, msg -> index.takeIf { msg.role == "user" } } }
    fun jump(target: Int?) {
        if (target == null) return
        scope.launch { listState.animateScrollToItem(target.coerceIn(0, maxOf(0, total - 1))) }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it },
        exit = fadeOut(tween(150)) + slideOutHorizontally(tween(190)) { it }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            QuickJumpButton(Icons.Rounded.KeyboardDoubleArrowUp, "顶部") { jump(0) }
            QuickJumpButton(Icons.Rounded.KeyboardArrowUp, "上一条用户消息") { jump(userIndexes.lastOrNull { it < listState.firstVisibleItemIndex }) }
            QuickJumpButton(Icons.Rounded.KeyboardArrowDown, "下一条用户消息") { jump(userIndexes.firstOrNull { it > listState.firstVisibleItemIndex }) }
            QuickJumpButton(Icons.Rounded.KeyboardDoubleArrowDown, "底部") { jump(total - 1) }
        }
    }
}

@Composable
private fun QuickJumpButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .94f), shadowElevation = 8.dp) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) { Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(30.dp)) }
    }
}

@Composable
private fun EmptyAgentTopBar(onSessions: () -> Unit, onTools: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onSessions) { Icon(Icons.Rounded.Menu, contentDescription = "Sessions") }
        Text("IDAdroid Agent", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = onTools) { Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Tools") }
    }
}

@Composable
private fun CenterWelcomeBox(title: String, subtitle: String, onBack: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ElevatedCard(Modifier.padding(22.dp).fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                onBack?.let { OutlinedButton(onClick = it) { Text("返回") } }
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    session: AgentSessionRecord,
    stats: SessionStats?,
    autoCompact: Boolean,
    isWorking: Boolean,
    onSessions: () -> Unit,
    onTools: () -> Unit,
    onRefresh: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onSessions, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Menu, contentDescription = "Sessions", modifier = Modifier.size(30.dp), tint = colors.onSurface) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(session.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(session.provider ?: "默认助手", session.model).filterNotNull().joinToString(" / "), fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isWorking) StatusChip("working")
            IconButton(onClick = onTools, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Tools", modifier = Modifier.size(28.dp), tint = colors.onSurfaceVariant) }
            IconButton(onClick = onRefresh, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(28.dp), tint = colors.onSurfaceVariant) }
        }
        TopStatsLine(stats, autoCompact, Modifier.fillMaxWidth().padding(start = 48.dp, end = 2.dp))
    }
}

@Composable
private fun TopStatsLine(stats: SessionStats?, autoCompact: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tokens = stats?.tokens
    val context = stats?.contextUsage
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StatItem(Icons.Rounded.ArrowUpward, formatTokens(tokens?.input ?: 0), colors.onSurfaceVariant)
        StatItem(Icons.Rounded.ArrowDownward, formatTokens(tokens?.output ?: 0), colors.onSurfaceVariant)
        StatItem(Icons.Rounded.Memory, "R${formatTokens(tokens?.cacheRead ?: 0)}", colors.onSurfaceVariant)
        StatItem(Icons.Rounded.Paid, stats?.cost?.let { "$${"%.3f".format(it)}" } ?: "$0.000", colors.onSurfaceVariant)
        StatItem(Icons.Rounded.DataUsage, "${context?.percent?.let { "%.1f%%".format(it) } ?: "—%"}/${context?.contextWindow?.let { formatTokens(it) } ?: "ctx"}", colors.onSurfaceVariant)
        Text(if (autoCompact) "(auto)" else "(manual)", color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun StatItem(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        Text(text, color = color, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun StatusChip(status: String) {
    val color = when (status) {
        "working", "running" -> Color(0xFF16A34A)
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = .13f), contentColor = color, border = BorderStroke(1.dp, color.copy(alpha = .35f))) {
        Text(status, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WelcomePrompts(onPrompt: (String) -> Unit) {
    val prompts = listOf(
        "你好，请确认当前 IDAdroid 环境，并说明你能用哪些工具。" to "环境确认",
        "请先阅读 /root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md，然后告诉我如何连接当前 IDA。" to "IDA MCP",
        "请列出 /root/pi_workspace 下的关键文件。" to "查看文件",
        "请帮我分析附件内容，并给出下一步逆向计划。" to "分析附件"
    )
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("准备好开始了吗？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text("描述任务即可开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                prompts.forEach { (prompt, label) -> AssistChip(onClick = { onPrompt(prompt) }, label = { Text(label) }) }
            }
        }
    }
}

@Composable
private fun ProcessingCard() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f), contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Text("pi 正在处理…", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, autoOpenProgress: Boolean, isLatestMessage: Boolean, streaming: Boolean, onShowDialog: () -> Unit) {
    when (message.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp), modifier = Modifier.widthIn(max = 330.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (message.text.isNotBlank()) SelectionContainer { Text(message.text) }
                    AttachmentGallery(message.attachments)
                }
            }
        }
        "assistant" -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message.thinking?.takeIf { it.isNotBlank() }?.let { ExpandableBlock("思考过程", it, autoOpen = autoOpenProgress, autoCollapse = !isLatestMessage, stateKey = message.id) }
            if (message.text.isNotBlank()) MarkdownishText(message.text, selectable = true, lightweight = streaming) else Spacer(Modifier.height(1.dp))
            AttachmentGallery(message.attachments)
            if (message.text.isNotBlank()) AssistantActions(message.text, onShowDialog)
        }
        "tool" -> ToolMessageCard(message, autoOpen = autoOpenProgress, autoCollapse = !isLatestMessage)
        else -> SelectionContainer { AssistChip(onClick = {}, leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) }, label = { Text(message.text) }) }
    }
}

@Composable
private fun AttachmentGallery(attachments: List<ChatAttachment>) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        attachments.forEach { attachment ->
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .35f)) {
                Text(
                    when (attachment) {
                        is ChatAttachment.File -> "📄 ${attachment.path}"
                        is ChatAttachment.Image -> "🖼 ${attachment.path ?: attachment.name}"
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun AssistantActions(text: String, onShowDialog: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.ContentCopy, contentDescription = "复制", tint = color) }
        IconButton(onClick = onShowDialog, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.OpenInFull, contentDescription = "查看完整内容", tint = color) }
    }
}

@Composable
private fun MarkdownishText(text: String, selectable: Boolean = false, lightweight: Boolean = false) {
    val blocks = remember(text, lightweight) { if (lightweight) listOf(MdBlock.Text(text)) else parseMarkdownBlocks(text) }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Code -> CodeBlock(block.language.ifBlank { "code" }, block.code)
                    is MdBlock.Text -> MarkdownTextBlock(block.text)
                }
            }
        }
    }
    if (selectable) SelectionContainer { content() } else content()
}

private sealed interface MdBlock {
    data class Text(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
}

private fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val re = Regex("```([^\\n`]*)\\n([\\s\\S]*?)```")
    var last = 0
    for (match in re.findAll(text)) {
        if (match.range.first > last) out += MdBlock.Text(text.substring(last, match.range.first).trim('\n'))
        out += MdBlock.Code(match.groupValues[1].trim(), match.groupValues[2].trimEnd('\n'))
        last = match.range.last + 1
    }
    if (last < text.length) out += MdBlock.Text(text.substring(last).trim('\n'))
    return out.filterNot { it is MdBlock.Text && it.text.isBlank() }
}

@Composable
private fun MarkdownTextBlock(text: String) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.isDarkLike()
    val inlineCodeBackground = if (dark) Color(0xFF312D36) else Color(0xFFE8EAF6)
    val inlineCodeColor = if (dark) Color(0xFFEADDFF) else Color(0xFF5B3DB5)
    val paragraphs = remember(text) { text.split(MarkdownParagraphBreakRegex).filter { it.isNotBlank() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paragraphs.forEach { para ->
            val lines = para.lines()
            val heading = lines.firstOrNull()?.let { MarkdownHeadingRegex.find(it) }
            when {
                heading != null && lines.size == 1 -> {
                    val level = heading.groupValues[1].length
                    Text(
                        inlineMarkdown(heading.groupValues[2], colors.onSurface, inlineCodeBackground, inlineCodeColor),
                        color = colors.onSurface,
                        fontSize = if (level <= 2) 22.sp else 18.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = if (level <= 2) 28.sp else 24.sp
                    )
                }
                lines.all { isMarkdownBullet(it) } -> {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        lines.forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                                Text(markdownBulletMarker(line), color = colors.primary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    inlineMarkdown(markdownBulletText(line), colors.onSurface, inlineCodeBackground, inlineCodeColor),
                                    style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                lines.all { it.trimStart().startsWith(">") } -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = colors.surfaceContainerHighest.copy(alpha = .55f),
                        border = BorderStroke(1.dp, colors.outlineVariant)
                    ) {
                        Text(
                            inlineMarkdown(lines.joinToString("\n") { it.trimStart().removePrefix(">").trimStart() }, colors.onSurfaceVariant, inlineCodeBackground, inlineCodeColor),
                            style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
                lines.size == 1 && lines.first().trim().matches(Regex("-{3,}|\\*{3,}|_{3,}")) -> HorizontalDivider(color = colors.outlineVariant)
                isMarkdownTable(lines) -> MarkdownTable(lines)
                else -> Text(
                    inlineMarkdown(para, colors.onSurface, inlineCodeBackground, inlineCodeColor),
                    style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
                )
            }
        }
    }
}

private fun isMarkdownBullet(line: String): Boolean {
    val t = line.trimStart()
    return t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") || Regex("^\\d+[.)]\\s+.+").containsMatchIn(t) || Regex("^- \\[([ xX])]\\s+.+").containsMatchIn(t)
}

private fun markdownBulletMarker(line: String): String {
    val t = line.trimStart()
    Regex("^(\\d+[.)])\\s+").find(t)?.let { return it.groupValues[1] }
    Regex("^- \\[([ xX])]\\s+").find(t)?.let { return if (it.groupValues[1].equals("x", true)) "☑" else "☐" }
    return "•"
}

private fun markdownBulletText(line: String): String {
    val t = line.trimStart()
    Regex("^\\d+[.)]\\s+").find(t)?.let { return t.substring(it.range.last + 1).trim() }
    Regex("^- \\[[ xX]]\\s+").find(t)?.let { return t.substring(it.range.last + 1).trim() }
    return t.removePrefix("- ").removePrefix("* ").removePrefix("• ").trim()
}

private fun isMarkdownTable(lines: List<String>): Boolean = lines.size >= 2 && lines[0].contains('|') && lines[1].trim().matches(Regex("^[|:\\-\\s]+$"))

@Composable
private fun MarkdownTable(lines: List<String>) {
    val rows = remember(lines) { lines.filterIndexed { index, _ -> index != 1 }.map { line -> line.trim().trim('|').split('|').map { it.trim() } } }
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth()) {
            rows.forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth().background(if (rowIndex == 0) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)) {
                    row.forEach { cell ->
                        Text(
                            cell,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp),
                            fontSize = 12.sp,
                            fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (rowIndex < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private fun inlineMarkdown(text: String, baseColor: Color, inlineCodeBackground: Color, inlineCodeColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    fun appendPlain(until: Int) {
        if (until > last) withStyle(SpanStyle(color = baseColor)) { append(text.substring(last, until)) }
    }
    for (m in InlineMarkdownRegex.findAll(text)) {
        appendPlain(m.range.first)
        val token = m.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(color = baseColor, fontWeight = FontWeight.Black)) { append(token.removePrefix("**").removeSuffix("**")) }
            token.startsWith("*") -> withStyle(SpanStyle(color = baseColor, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(token.removePrefix("*").removeSuffix("*")) }
            token.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = inlineCodeBackground, color = inlineCodeColor, fontWeight = FontWeight.SemiBold)) { append(token.removePrefix("`").removeSuffix("`")) }
            token.startsWith("[") -> {
                val label = token.substringAfter("[").substringBefore("]")
                withStyle(SpanStyle(color = inlineCodeColor, fontWeight = FontWeight.SemiBold)) { append(label.ifBlank { token }) }
            }
        }
        last = m.range.last + 1
    }
    appendPlain(text.length)
}

@Composable
private fun CodeBlock(
    title: String,
    code: String,
    collapsedChars: Int = TOOL_CODE_COLLAPSED_CHARS,
    maxCollapsedLines: Int = 120,
    highlight: Boolean = true
) {
    val clipboard = LocalClipboardManager.current
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val lines = remember(code) { code.replace("\r\n", "\n").split("\n") }
    val lineClipped = lines.size > maxCollapsedLines
    val charClipped = code.length > collapsedChars
    val clipped = lineClipped || charClipped
    var expanded by rememberSaveable(code) { mutableStateOf(!clipped) }
    val collapsedText = remember(code, collapsedChars, maxCollapsedLines) {
        val lineLimited = if (lines.size > maxCollapsedLines) lines.take(maxCollapsedLines).joinToString("\n") else code
        if (lineLimited.length > collapsedChars) lineLimited.take(collapsedChars) else lineLimited
    }
    val displayText = if (clipped && !expanded) collapsedText else code
    val dark = MaterialTheme.colorScheme.isDarkLike()
    val highlighted = remember(displayText, title, dark, highlight) { if (highlight) fallbackHighlightCode(displayText, title, dark) else AnnotatedString(displayText) }
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = bg), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (clipped) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(13.dp)); Text(if (expanded) "收起" else "展开全部", fontSize = 12.sp) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp)); Text("复制", fontSize = 12.sp) }
        }
        Text(highlighted, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(bg).padding(10.dp), lineHeight = 18.sp)
        if (clipped && !expanded) {
            val hiddenLines = (lines.size - maxCollapsedLines).coerceAtLeast(0)
            val hiddenChars = (code.length - displayText.length).coerceAtLeast(0)
            Text(
                "… ${listOfNotNull(hiddenLines.takeIf { it > 0 }?.let { "折叠 $it 行，共 ${lines.size} 行" }, hiddenChars.takeIf { it > 0 }?.let { "隐藏 $it 个字符" }).joinToString("；")}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(bg).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
            )
        }
    }
}

private fun fallbackHighlightCode(code: String, language: String, dark: Boolean): AnnotatedString = buildAnnotatedString {
    val keywords = setOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package", "const", "let", "function", "export", "from", "def", "async", "await", "try", "catch", "finally", "true", "false", "null", "None", "public", "private", "suspend", "data", "echo", "cd", "ls", "grep", "find", "cat", "sed", "awk")
    val token = Regex("(//.*|#.*|\\\"(?:\\\\.|[^\\\"])*\\\"|'(?:\\\\.|[^'])*'|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b)")
    var last = 0
    for (m in token.findAll(code)) {
        append(code.substring(last, m.range.first))
        val s = m.value
        val color = when {
            s.startsWith("//") || (s.startsWith("#") && !language.contains("json", true)) -> Color(0xFF8B949E)
            s.startsWith("\"") || s.startsWith("'") -> Color(0xFFA5D6FF)
            s.firstOrNull()?.isDigit() == true -> Color(0xFFFFD580)
            s in keywords -> Color(0xFFD0BCFF)
            else -> Color.Unspecified
        }
        withStyle(SpanStyle(color = color, fontWeight = if (s in keywords) FontWeight.Bold else FontWeight.Normal)) { append(s) }
        last = m.range.last + 1
    }
    append(code.substring(last))
}

private enum class ToolKind { Read, Edit, Write, Bash, Ls, Grep, Find, Unknown }

@Composable
private fun ToolMessageCard(message: ChatMessage, autoOpen: Boolean, autoCollapse: Boolean) {
    val status = message.toolStatus ?: "pending"
    val kind = toolKindForName(message.toolName)
    val accent = toolKindAccent(kind)
    val border = when (status) {
        "running" -> accent.copy(alpha = .50f)
        "error" -> MaterialTheme.colorScheme.error.copy(alpha = .55f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    var open by rememberSaveable(message.id) { mutableStateOf(autoOpen) }
    var openedByAuto by rememberSaveable(message.id) { mutableStateOf(autoOpen) }
    LaunchedEffect(autoOpen, autoCollapse, message.id) {
        when {
            autoOpen -> { open = true; openedByAuto = true }
            autoCollapse && openedByAuto -> { open = false; openedByAuto = false }
        }
    }
    OutlinedCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, border)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open; openedByAuto = false }.padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                ToolKindIcon(kind, status)
                Text(
                    toolLabel(kind, message.toolName),
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.widthIn(max = 92.dp)
                )
                ToolOverview(message, modifier = Modifier.weight(1f))
                ToolStatusBadge(status)
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            if (open) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f))
                ToolPreview(message)
            }
        }
    }
}

@Composable
private fun ToolKindIcon(kind: ToolKind, status: String) {
    val accent = toolKindAccent(kind)
    Surface(shape = RoundedCornerShape(9.dp), color = accent.copy(alpha = .13f), contentColor = accent, modifier = Modifier.size(26.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(toolIcon(kind), contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun toolKindAccent(kind: ToolKind): Color = when (kind) {
    ToolKind.Read, ToolKind.Edit -> MaterialTheme.colorScheme.primary
    ToolKind.Write -> MaterialTheme.colorScheme.tertiary
    ToolKind.Bash -> MaterialTheme.colorScheme.secondary
    ToolKind.Ls, ToolKind.Grep, ToolKind.Find -> MaterialTheme.colorScheme.primary
    ToolKind.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun toolIcon(kind: ToolKind): ImageVector = when (kind) {
    ToolKind.Read -> Icons.Rounded.Visibility
    ToolKind.Edit -> Icons.Rounded.Edit
    ToolKind.Write -> Icons.Rounded.NoteAdd
    ToolKind.Bash -> Icons.Rounded.Terminal
    ToolKind.Ls -> Icons.Rounded.Folder
    ToolKind.Grep -> Icons.Rounded.Search
    ToolKind.Find -> Icons.Rounded.Search
    ToolKind.Unknown -> Icons.Rounded.Build
}

@Composable
private fun toolLabel(kind: ToolKind, name: String?): String = when (kind) {
    ToolKind.Read -> "读取文件"
    ToolKind.Edit -> "编辑文件"
    ToolKind.Write -> "写入文件"
    ToolKind.Bash -> "执行命令"
    ToolKind.Ls -> "列出目录"
    ToolKind.Grep -> "搜索文本"
    ToolKind.Find -> "查找文件"
    ToolKind.Unknown -> name?.takeIf { it.isNotBlank() } ?: "tool"
}

@Composable
private fun ToolStatusBadge(status: String) {
    val color = when (status) {
        "running" -> MaterialTheme.colorScheme.primary
        "error" -> MaterialTheme.colorScheme.error
        "done" -> Color(0xFF16A34A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

private fun toolKindForName(name: String?): ToolKind {
    val raw = name.orEmpty().trim().lowercase()
    val last = raw.split(Regex("[./:]")).filter { it.isNotBlank() }.lastOrNull() ?: raw
    val keys = listOf(raw, last).map { it.replace(Regex("[\\s_-]"), "") }
    fun has(vararg values: String): Boolean = keys.any { key -> values.any { it == key } }
    return when {
        has("read", "readfile", "fileread", "view") -> ToolKind.Read
        has("edit", "editfile", "fileedit", "replace", "strreplace") -> ToolKind.Edit
        has("write", "writefile", "filewrite", "create", "createfile") -> ToolKind.Write
        has("bash", "shell", "terminal", "runcommand", "exec", "execute") -> ToolKind.Bash
        has("ls", "list", "listdir", "listdirectory") -> ToolKind.Ls
        has("grep", "rg", "ripgrep", "search", "searchtext") -> ToolKind.Grep
        has("find", "findfile", "findfiles", "glob") -> ToolKind.Find
        else -> ToolKind.Unknown
    }
}

@Composable
private fun ToolOverview(message: ChatMessage, modifier: Modifier = Modifier) {
    val kind = toolKindForName(message.toolName)
    val args = message.toolArgs.toolObjectOrNull()
    val result = message.toolResult.orEmpty()
    val path = args.toolPath()
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        when (kind) {
            ToolKind.Edit -> {
                val edits = editDiffInputs(args, path)
                val stats = editChangeStats(edits)
                val paths = uniqueStrings(listOf(path) + edits.map { it.path })
                if (paths.isNotEmpty()) ToolMuted("${paths.size} files")
                if (edits.isNotEmpty()) ToolChangeStats(stats.added, stats.removed) else ToolMuted(previewText(result) ?: "等待 diff")
                paths.firstOrNull()?.let { ToolFileRef(it) }
                if (paths.size > 1) ToolMuted("+${paths.size - 1}")
                if (paths.isEmpty() && edits.isEmpty() && result.isBlank()) ToolMuted("点击查看详情")
            }
            ToolKind.Read -> {
                if (path != null) ToolFileRef(path) else ToolMuted("file")
                readLineSummary(args, result)?.takeIf { it.isNotBlank() }?.let { ToolMuted(it) }
            }
            ToolKind.Write -> {
                val content = args.firstString("content", "newText", "new_text", "replacement", "text", "value", "data")
                val added = content?.let { splitTextLines(it).size } ?: lineCount(result)
                if (added > 0) ToolChangeStats(added, 0)
                if (path != null) ToolFileRef(path) else ToolMuted(previewText(result) ?: "file")
            }
            ToolKind.Bash -> {
                val command = bashCommand(message.toolArgs)
                if (command != null) ToolInlineCode("$ ${previewText(command, 180)}") else ToolMuted(previewText(result) ?: "shell")
            }
            ToolKind.Ls -> if (path != null) ToolFileRef(path) else ToolMuted("当前目录")
            ToolKind.Grep, ToolKind.Find -> {
                args.firstString("pattern", "query", "regex", "name", "value")?.let { ToolInlineCode(previewText(it, 80) ?: it) }
                path?.let { ToolFileRef(it) }
                if (path == null && args.firstString("pattern", "query", "regex", "name", "value") == null) ToolMuted(previewText(message.toolArgs?.let { pretty(it) } ?: result) ?: "点击查看详情")
            }
            ToolKind.Unknown -> ToolMuted(compactText(path?.let { fileNameFromPath(it) }, previewText(message.toolArgs?.let { pretty(it) }), previewText(result)))
        }
    }
}

@Composable
private fun ToolMuted(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ToolInlineCode(text: String) {
    Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f), contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(fallbackHighlightCode(text, "bash", MaterialTheme.colorScheme.isDarkLike()), fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
private fun ToolChangeStats(added: Int, removed: Int) {
    val add = added.coerceAtLeast(0)
    val del = removed.coerceAtLeast(0)
    if (add == 0 && del == 0) { ToolMuted("无行变更"); return }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (add > 0) Text("+$add", color = Color(0xFF16A34A), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1)
        if (del > 0) Text("-$del", color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ToolFileRef(path: String) {
    val name = fileNameFromPath(path)
    Row(Modifier.widthIn(max = 190.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ToolFileIcon(path)
        Text(name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .88f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolPathPill(path: String?, fallback: String = "file") {
    val label = path?.let { fileNameFromPath(it) } ?: fallback
    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.widthIn(max = 220.dp).padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ToolFileIcon(path ?: label)
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ToolFileIcon(path: String) {
    val icon = when (fileExtension(path)) {
        "sh", "bash", "zsh", "fish" -> Icons.Rounded.Terminal
        "md", "txt", "log" -> Icons.Rounded.Description
        "json", "yml", "yaml", "xml", "toml" -> Icons.Rounded.Code
        "kt", "kts", "java", "js", "ts", "tsx", "py", "c", "cpp", "h", "go", "rs" -> Icons.Rounded.Code
        else -> Icons.Rounded.Description
    }
    Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp)) }
    }
}

@Composable
private fun ToolPreview(message: ChatMessage) {
    val kind = toolKindForName(message.toolName)
    val args = message.toolArgs.toolObjectOrNull()
    val result = message.toolResult.orEmpty()
    val path = args.toolPath()
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        when (kind) {
            ToolKind.Write -> {
                val content = args.firstString("content", "newText", "new_text", "replacement", "text", "value", "data")
                ToolPreviewHeader("写入 diff", path)
                if (!content.isNullOrBlank()) DiffBlock("Unified diff", buildWriteDiff(content, path))
                else if (result.isNotBlank()) ToolCodeBlock("输出预览", result)
                else EmptyToolPreview("没有可显示的写入内容")
                if (result.isNotBlank() && content != null) SmallToolDetails("工具结果") { ToolCodeBlock("输出预览", result, maxLines = 10) }
            }
            ToolKind.Edit -> {
                val edits = editDiffInputs(args, path)
                val diffs = buildEditDiffs(args, path)
                ToolPreviewHeader("编辑 diff", path ?: edits.firstOrNull()?.path, if (edits.size > 1) "${edits.size} blocks" else null)
                if (diffs.isNotEmpty()) DiffBlock("Unified diff", diffs)
                else if (result.isNotBlank()) ToolCodeBlock("输出预览", result)
                else EmptyToolPreview("没有可显示的编辑内容")
                if (result.isNotBlank() && diffs.isNotEmpty()) SmallToolDetails("工具结果") { ToolCodeBlock("输出预览", result, maxLines = 10) }
                else if (message.toolArgs != null && diffs.isEmpty()) SmallToolDetails("参数") { ToolCodeBlock("json", pretty(message.toolArgs), maxLines = 10) }
            }
            ToolKind.Read -> {
                val display = result.ifBlank { message.toolArgs?.let { pretty(it) }.orEmpty() }
                ToolPreviewHeader("读取", path, readLineSummary(args, result))
                if (display.isNotBlank()) { ToolCodeBlock(languageForPath(path).ifBlank { "text" }, display); ToolMetaNotice(message.toolResultMeta, result.ifBlank { display }) }
                else EmptyToolPreview("没有可显示的读取结果")
            }
            ToolKind.Bash -> {
                val command = bashCommand(message.toolArgs)
                if (!command.isNullOrBlank()) {
                    ToolPreviewHeader("执行命令", null)
                    ToolCodeBlock("$ command", command, maxLines = 24)
                }
                if (result.isNotBlank()) {
                    ToolPreviewHeader("输出预览", null)
                    ToolCodeBlock("terminal", result)
                    ToolMetaNotice(message.toolResultMeta, result)
                }
                if (command.isNullOrBlank() && result.isBlank()) EmptyToolPreview("暂无输出")
            }
            ToolKind.Ls, ToolKind.Grep, ToolKind.Find, ToolKind.Unknown -> {
                if (message.toolArgs != null) {
                    ToolPreviewHeader("参数", path)
                    ToolCodeBlock("json", pretty(message.toolArgs), maxLines = 10)
                }
                if (result.isNotBlank()) {
                    ToolPreviewHeader("输出预览", path)
                    ToolCodeBlock("输出", result)
                    ToolMetaNotice(message.toolResultMeta, result)
                }
                if (message.toolArgs == null && result.isBlank()) EmptyToolPreview("没有可显示的工具内容")
            }
        }
    }
}

@Composable
private fun ToolPreviewHeader(title: String, path: String?, extra: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1)
        path?.let { ToolPathPill(it) }
        extra?.takeIf { it.isNotBlank() }?.let { ToolMuted(it) }
    }
}

@Composable
private fun ToolCodeBlock(title: String, text: String, maxLines: Int = 18) {
    CodeBlock(title, text, maxCollapsedLines = maxLines, highlight = false)
}

@Composable
private fun ToolMetaNotice(meta: dev.idadroid.agent.ToolResultMeta?, text: String) {
    val enriched = remember(meta, text) { enrichToolMeta(meta, text) } ?: return
    val parts = buildList {
        enriched.label?.takeIf { it.isNotBlank() }?.let { add(it) }
        val shown = enriched.shownLines
        val total = enriched.totalLines
        val omitted = enriched.omittedLines
        if (shown != null && total != null && total != shown) add("显示 $shown/$total 行") else if (total != null) add("共 $total 行")
        if (omitted != null) add("省略 $omitted 行")
        val shownBytes = enriched.shownBytes
        val totalBytes = enriched.totalBytes
        if (shownBytes != null && totalBytes != null && totalBytes != shownBytes) add("${formatBytes(shownBytes)}/${formatBytes(totalBytes)}") else if (totalBytes != null) add(formatBytes(totalBytes))
    }
    if (parts.isEmpty() && enriched.truncated != true) return
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f), contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(listOfNotNull(if (enriched.truncated == true) "工具输出已截断" else null, parts.joinToString(" · ").takeIf { it.isNotBlank() }).joinToString(" · "), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}

private fun enrichToolMeta(meta: dev.idadroid.agent.ToolResultMeta?, text: String): dev.idadroid.agent.ToolResultMeta? {
    val lines = lineCount(text).toLong()
    var next = meta ?: return null
    if (lines > 0 && next.shownLines == null) next = next.copy(shownLines = lines)
    if (lines > 0 && next.totalLines == null && next.truncated != true) next = next.copy(totalLines = lines)
    if (next.omittedLines != null && next.shownLines != null && next.totalLines == null) next = next.copy(totalLines = next.shownLines + next.omittedLines)
    if (next.omittedLines == null && next.totalLines != null && next.shownLines != null && next.totalLines > next.shownLines) next = next.copy(omittedLines = next.totalLines - next.shownLines)
    return next
}

@Composable
private fun EmptyToolPreview(text: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .55f)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun SmallToolDetails(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            if (open) Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) { content() }
        }
    }
}

private data class EditDiffInput(val oldText: String, val newText: String, val path: String?)
private data class ChangeStats(val added: Int, val removed: Int)
private data class DiffLine(val type: DiffLineType, val text: String)
private enum class DiffLineType { Add, Del, Meta, Ctx }

@Composable
private fun DiffBlock(title: String, lines: List<DiffLine>, maxLines: Int = 240) {
    val clipboard = LocalClipboardManager.current
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val clipped = lines.size > maxLines
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = if (clipped && !expanded) lines.take(maxLines) else lines
    val text = lines.joinToString("\n") { line ->
        when (line.type) { DiffLineType.Add -> "+${line.text}"; DiffLineType.Del -> "-${line.text}"; DiffLineType.Meta -> line.text; DiffLineType.Ctx -> " ${line.text}" }
    }
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = bg), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 10.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (clipped) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(13.dp)); Text(if (expanded) "收起" else "展开全部", fontSize = 12.sp) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) { Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp)); Text("复制", fontSize = 12.sp) }
        }
        Column(Modifier.fillMaxWidth().background(bg)) {
            visible.forEach { line -> DiffLineRow(line) }
            if (clipped && !expanded) DiffLineRow(DiffLine(DiffLineType.Meta, "… 已隐藏 ${lines.size - maxLines} 行，共 ${lines.size} 行"))
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val colors = MaterialTheme.colorScheme
    val add = Color(0xFF16A34A)
    val del = colors.error
    val bg = when (line.type) { DiffLineType.Add -> add.copy(alpha = .12f); DiffLineType.Del -> del.copy(alpha = .12f); DiffLineType.Meta -> colors.primaryContainer.copy(alpha = .36f); DiffLineType.Ctx -> Color.Transparent }
    val stripe = when (line.type) { DiffLineType.Add -> add; DiffLineType.Del -> del; else -> Color.Transparent }
    val signColor = when (line.type) { DiffLineType.Add -> add; DiffLineType.Del -> del; DiffLineType.Meta -> colors.primary; DiffLineType.Ctx -> colors.onSurfaceVariant }
    val textColor = when (line.type) { DiffLineType.Meta -> colors.primary; else -> colors.onSurface }
    Row(Modifier.fillMaxWidth().background(bg), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(3.dp).height(20.dp).background(stripe))
        Text(when (line.type) { DiffLineType.Add -> "+"; DiffLineType.Del -> "-"; DiffLineType.Meta -> ""; DiffLineType.Ctx -> " " }, color = signColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp).padding(top = 2.dp), maxLines = 1)
        Text(line.text.ifEmpty { " " }, color = textColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f).padding(vertical = 2.dp, horizontal = 4.dp))
    }
}

private fun buildWriteDiff(content: String, filePath: String?): List<DiffLine> {
    val lines = splitTextLines(content)
    return listOf(DiffLine(DiffLineType.Meta, "+++ ${filePath ?: "file"}"), DiffLine(DiffLineType.Meta, "@@ -0,0 +1,${lines.size} @@")) + lines.map { DiffLine(DiffLineType.Add, it) }
}

private fun buildEditDiffs(args: JsonObject?, filePath: String?): List<DiffLine> {
    val edits = editDiffInputs(args, filePath)
    return edits.flatMapIndexed { index, edit ->
        val lines = if (edit.oldText.isNotBlank()) buildUnifiedDiff(edit.oldText, edit.newText, edit.path ?: filePath) else buildWriteDiff(edit.newText, edit.path ?: filePath)
        if (index == 0) lines else listOf(DiffLine(DiffLineType.Meta, "")) + lines
    }
}

private fun editDiffInputs(args: JsonObject?, fallbackPath: String?): List<EditDiffInput> {
    if (args == null) return emptyList()
    val out = mutableListOf<EditDiffInput>()
    fun push(obj: JsonObject?) {
        if (obj == null) return
        val oldText = obj.firstString("oldText", "old_text", "old", "original", "before").orEmpty()
        val newText = obj.firstString("newText", "new_text", "replacement", "replace", "new", "after", "content", "text", "value").orEmpty()
        val path = obj.toolPath() ?: fallbackPath
        if (oldText.isNotBlank() || newText.isNotBlank()) out += EditDiffInput(oldText, newText, path)
    }
    listOf("edits", "changes", "replacements").forEach { key -> (args[key] as? JsonArray)?.forEach { push(it.toolObjectOrNull()) } }
    push(args)
    return out.distinctBy { Triple(it.oldText, it.newText, it.path) }
}

private fun editChangeStats(edits: List<EditDiffInput>): ChangeStats = edits.fold(ChangeStats(0, 0)) { acc, edit ->
    val changed = changedLineCounts(edit.oldText, edit.newText)
    ChangeStats(acc.added + changed.added, acc.removed + changed.removed)
}

private fun changedLineCounts(oldText: String, newText: String): ChangeStats {
    if (oldText.isBlank() && newText.isBlank()) return ChangeStats(0, 0)
    val oldLines = splitTextLines(oldText)
    val newLines = splitTextLines(newText)
    if (oldLines.isEmpty()) return ChangeStats(newLines.size, 0)
    if (newLines.isEmpty()) return ChangeStats(0, oldLines.size)
    if (oldLines.size * newLines.size > 90_000) return ChangeStats(newLines.size, oldLines.size)
    val dp = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
    for (i in oldLines.size - 1 downTo 0) for (j in newLines.size - 1 downTo 0) dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
    val common = dp[0][0]
    return ChangeStats(newLines.size - common, oldLines.size - common)
}

private fun buildUnifiedDiff(oldText: String, newText: String, filePath: String?): List<DiffLine> {
    val oldLines = splitTextLines(oldText)
    val newLines = splitTextLines(newText)
    val header = listOf(DiffLine(DiffLineType.Meta, "--- ${filePath ?: "file"}"), DiffLine(DiffLineType.Meta, "+++ ${filePath ?: "file"}"), DiffLine(DiffLineType.Meta, "@@ -1,${oldLines.size} +1,${newLines.size} @@"))
    if (oldLines.size * newLines.size > 90_000) return header + oldLines.map { DiffLine(DiffLineType.Del, it) } + newLines.map { DiffLine(DiffLineType.Add, it) }
    val dp = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
    for (i in oldLines.size - 1 downTo 0) for (j in newLines.size - 1 downTo 0) dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
    val body = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < oldLines.size || j < newLines.size) {
        when {
            i < oldLines.size && j < newLines.size && oldLines[i] == newLines[j] -> { body += DiffLine(DiffLineType.Ctx, oldLines[i]); i++; j++ }
            j >= newLines.size || (i < oldLines.size && dp[i + 1][j] >= dp[i][j + 1]) -> { body += DiffLine(DiffLineType.Del, oldLines[i]); i++ }
            else -> { body += DiffLine(DiffLineType.Add, newLines[j]); j++ }
        }
    }
    return header + body
}

private fun splitTextLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.replace("\r\n", "\n").split("\n")
private fun lineCount(text: String): Int = if (text.isBlank()) 0 else splitTextLines(text).size
private fun JsonObject?.toolPath(): String? = this?.firstString("path", "file", "filename", "filePath", "file_path", "directory", "dir", "cwd")
private fun JsonObject?.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { this?.stringValue(it)?.takeIf(String::isNotBlank) }
private fun uniqueStrings(values: List<String?>): List<String> = values.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.distinct()
private fun fileNameFromPath(path: String): String = path.replace('\\', '/').trimEnd('/').split('/').filter { it.isNotBlank() }.lastOrNull() ?: path.ifBlank { "file" }
private fun fileExtension(name: String): String = fileNameFromPath(name).lowercase().substringAfterLast('.', missingDelimiterValue = "").takeIf { it != fileNameFromPath(name).lowercase() }.orEmpty()
private fun languageForPath(path: String?): String {
    val value = path?.substringBefore('?')?.substringBefore('#')?.trim().orEmpty()
    val ext = value.substringAfterLast('/', value).substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        "kt", "kts" -> "kotlin"; "java" -> "java"; "js", "jsx", "mjs", "cjs", "ts", "tsx" -> "javascript"; "json" -> "json"; "py" -> "python"; "html", "htm", "xml", "svg", "vue" -> "markup"; "css", "scss", "sass" -> "css"; "md", "markdown" -> "markdown"; "yaml", "yml" -> "yaml"; "c", "h" -> "c"; "cpp", "cc", "cxx", "hpp", "hh" -> "cpp"; "sh", "bash", "zsh", "fish" -> "bash"; else -> if (value.indexOf('.') < 0 && value.indexOf('/') < 0) value.lowercase() else ""
    }
}
private fun readLineSummary(args: JsonObject?, result: String): String? {
    val start = args.numericValue("offset", "start", "startLine", "start_line", "line", "from")
    val limit = args.numericValue("limit", "lines", "lineCount", "line_count", "count")
    return when {
        start != null && limit != null -> "L$start-${max(start, start + limit - 1)}"
        start != null -> "L$start+"
        limit != null -> "first $limit lines"
        result.isNotBlank() -> lineCount(result).takeIf { it > 0 }?.let { if (it == 1) "L1" else "L1-$it" }
        else -> null
    }
}
private fun JsonObject?.numericValue(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key -> this?.stringValue(key)?.replace(",", "")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()?.coerceAtLeast(1) }
private fun compactText(vararg parts: String?): String = parts.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.joinToString(" · ").ifBlank { "Details" }
private fun previewText(value: String?, max: Int = 90): String? {
    val text = value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (text.isBlank()) return null
    return if (text.length > max) text.take(max - 1) + "…" else text
}
private fun bashCommand(value: JsonElement?): String? {
    val obj = value.toolObjectOrNull()
    return obj?.stringValue("command") ?: obj?.stringValue("cmd") ?: obj?.stringValue("script") ?: obj?.stringValue("value") ?: value.primitiveString()
}
private fun JsonElement?.toolObjectOrNull(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> contentOrNull?.let { runCatching { UiJson.parseToJsonElement(it).jsonObject }.getOrNull() }
    else -> null
}
private fun JsonElement?.primitiveString(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonObject.stringValue(key: String): String? = when (val value = this[key]) { is JsonPrimitive -> value.contentOrNull; null -> null; else -> pretty(value) }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    attachments: List<DraftAttachment>,
    onRemoveAttachment: (DraftAttachment) -> Unit,
    onPickFiles: () -> Unit,
    onSearchClick: () -> Unit,
    canSend: Boolean,
    isWorking: Boolean,
    thinking: String,
    model: String,
    autoCompact: Boolean,
    showThinkingMenu: Boolean,
    onShowThinkingMenu: (Boolean) -> Unit,
    showModelMenu: Boolean,
    onShowModelMenu: (Boolean) -> Unit,
    showCompactMenu: Boolean,
    onShowCompactMenu: (Boolean) -> Unit,
    showSendModeMenu: Boolean,
    onShowSendModeMenu: (Boolean) -> Unit,
    state: AgentUiState,
    manager: PiAgentManager,
    onSend: (String?) -> Unit,
    onAbort: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.activeQueue.steering.isNotEmpty() || state.activeQueue.followUp.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { state.activeQueue.steering.forEach { AssistChip(onClick = {}, label = { Text("Steer · $it") }) }; state.activeQueue.followUp.forEach { AssistChip(onClick = {}, label = { Text("Follow-up · $it") }) } }
            if (attachments.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start), verticalArrangement = Arrangement.spacedBy(6.dp)) { attachments.forEach { a -> InputChip(selected = false, onClick = { onRemoveAttachment(a) }, leadingIcon = { Icon(if (a.isImage) Icons.Rounded.Visibility else Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(17.dp)) }, label = { Text("${a.name} ${formatBytes(a.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) }, colors = InputChipDefaults.inputChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, labelColor = MaterialTheme.colorScheme.onSurface, leadingIconColor = MaterialTheme.colorScheme.primary), border = InputChipDefaults.inputChipBorder(enabled = true, selected = false, borderColor = MaterialTheme.colorScheme.outlineVariant)) } }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 132.dp)) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (text.isBlank()) Text("输入消息", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(value = text, onValueChange = onTextChange, textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                ComposerIconButton(Icons.Rounded.AutoAwesome, "模型", selected = model != "模型", onClick = { onShowModelMenu(true) })
                ComposerIconButton(Icons.Rounded.Search, "搜索", onClick = onSearchClick)
                ComposerIconButton(Icons.Rounded.Lightbulb, "Thinking $thinking", selected = thinking != "off", onClick = { onShowThinkingMenu(true) })
                ComposerIconButton(Icons.Rounded.Tune, "Compact", selected = autoCompact, onClick = { onShowCompactMenu(true) })
                ComposerIconButton(Icons.Rounded.AttachFile, "附件", selected = attachments.isNotEmpty(), onClick = onPickFiles)
                ComposerIconButton(Icons.Rounded.Add, "更多", onClick = { onShowCompactMenu(true) })
                if (isWorking && !canSend) FilledIconButton(onClick = onAbort, modifier = Modifier.size(38.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Rounded.Stop, contentDescription = "中止", modifier = Modifier.size(22.dp)) }
                else FilledIconButton(onClick = { if (isWorking) onShowSendModeMenu(true) else onSend(null) }, enabled = canSend, modifier = Modifier.size(38.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "发送", modifier = Modifier.size(22.dp)) }
            }
        }
    }
    ChatOptionSheets(showThinkingMenu, onShowThinkingMenu, showModelMenu, onShowModelMenu, showCompactMenu, onShowCompactMenu, showSendModeMenu, onShowSendModeMenu, text, onTextChange, autoCompact, state, manager, onSend)
}

@Composable
private fun ComposerIconButton(icon: ImageVector, contentDescription: String, selected: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) { Icon(icon, contentDescription = contentDescription, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatOptionSheets(
    showThinkingMenu: Boolean,
    onShowThinkingMenu: (Boolean) -> Unit,
    showModelMenu: Boolean,
    onShowModelMenu: (Boolean) -> Unit,
    showCompactMenu: Boolean,
    onShowCompactMenu: (Boolean) -> Unit,
    showSendModeMenu: Boolean,
    onShowSendModeMenu: (Boolean) -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    autoCompact: Boolean,
    state: AgentUiState,
    manager: PiAgentManager,
    onSend: (String?) -> Unit
) {
    if (showThinkingMenu) ModalBottomSheet(onDismissRequest = { onShowThinkingMenu(false) }) { SheetHeader(Icons.Rounded.Lightbulb, "思考强度", "选择推理强度"); ThinkingLevels.forEach { level -> ListItem(headlineContent = { Text(level) }, supportingContent = { Text(thinkingDescription(level)) }, leadingContent = { if (state.activeSession?.thinkingLevel == level) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) else Icon(Icons.Rounded.Settings, contentDescription = null) }, modifier = Modifier.clickable { onShowThinkingMenu(false); manager.chooseThinking(level) }) }; Spacer(Modifier.height(18.dp)) }
    if (showModelMenu) {
        var search by remember { mutableStateOf("") }
        var manualProvider by remember { mutableStateOf(state.activeSession?.provider.orEmpty()) }
        var manualModel by remember { mutableStateOf(state.activeSession?.model.orEmpty()) }
        val models = remember(state.sessionModels, search) { state.sessionModels.filter { "${it.providerNameOrNull().orEmpty()} ${it.id} ${it.name.orEmpty()}".contains(search, ignoreCase = true) }.take(160) }
        ModalBottomSheet(onDismissRequest = { onShowModelMenu(false) }) {
            SheetHeader(Icons.Rounded.AutoAwesome, "模型", "切换 provider / model")
            OutlinedTextField(search, { search = it }, leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }, label = { Text("搜索 provider / model") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp))
            if (state.modelLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(18.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(models, key = { "${it.providerNameOrNull()}/${it.id}" }) { model -> val selected = state.activeSession?.model == model.id && state.activeSession?.provider == model.providerNameOrNull(); ModelPickerRow(model = model, selected = selected, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), onClick = { onShowModelMenu(false); manager.setSessionModel(model) }) }
                if (!state.modelLoading && models.isEmpty()) item { Text("没有可显示的模型，可手动填写。", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider()
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("手动设置", fontWeight = FontWeight.Bold); OutlinedTextField(manualProvider, { manualProvider = it }, label = { Text("Provider") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(manualModel, { manualModel = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Button(onClick = { onShowModelMenu(false); manager.setSessionModelManual(manualProvider, manualModel) }, enabled = manualProvider.isNotBlank() && manualModel.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("应用手动模型") } }
        }
    }
    if (showCompactMenu) ModalBottomSheet(onDismissRequest = { onShowCompactMenu(false) }) { SheetHeader(Icons.Rounded.Tune, "Compact / 更多", "上下文与附加操作"); ListItem(headlineContent = { Text("自动 Compact") }, supportingContent = { Text(if (autoCompact) "已开启" else "点击开启") }, leadingContent = { Icon(Icons.Rounded.Settings, contentDescription = null, tint = if (autoCompact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }, modifier = Modifier.clickable { onShowCompactMenu(false); manager.setAutoCompaction(true) }); ListItem(headlineContent = { Text("手动 Compact") }, supportingContent = { Text("仅手动触发") }, leadingContent = { Icon(if (!autoCompact) Icons.Rounded.CheckCircle else Icons.Rounded.Settings, contentDescription = null) }, modifier = Modifier.clickable { onShowCompactMenu(false); manager.setAutoCompaction(false) }); ListItem(headlineContent = { Text("立即执行 Compact") }, supportingContent = { Text(if (text.isBlank()) "压缩当前上下文" else "使用输入内容作为要求") }, leadingContent = { Icon(Icons.Rounded.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { onShowCompactMenu(false); manager.compact(text.trim().ifBlank { null }); onTextChange("") }); Spacer(Modifier.height(18.dp)) }
    if (showSendModeMenu) ModalBottomSheet(onDismissRequest = { onShowSendModeMenu(false) }) { SheetHeader(Icons.Rounded.Send, "发送方式", "选择队列策略"); ListItem(headlineContent = { Text("立即发送") }, supportingContent = { Text("中断当前 turn") }, leadingContent = { Icon(Icons.Rounded.FlashOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { onShowSendModeMenu(false); onSend(null) }); ListItem(headlineContent = { Text("Steer 队列") }, supportingContent = { Text("注入当前 turn") }, leadingContent = { Icon(Icons.Rounded.Send, contentDescription = null) }, modifier = Modifier.clickable { onShowSendModeMenu(false); onSend("steer") }); ListItem(headlineContent = { Text("Follow-up 队列") }, supportingContent = { Text("完成后发送") }, leadingContent = { Icon(Icons.Rounded.Queue, contentDescription = null) }, modifier = Modifier.clickable { onShowSendModeMenu(false); onSend("followUp") }); Spacer(Modifier.height(18.dp)) }
}

@Composable
private fun SheetHeader(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) { Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(24.dp)) }; Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelPickerRow(model: PiModel, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(modifier = modifier.clickable(onClick = onClick), colors = CardDefaults.outlinedCardColors(containerColor = if (selected) colors.primaryContainer.copy(alpha = .58f) else colors.surfaceContainerLow), border = BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = .62f) else colors.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = if (selected) colors.primary else colors.surfaceContainerHighest, contentColor = if (selected) colors.onPrimary else colors.primary) { Icon(if (selected) Icons.Rounded.Check else Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.padding(7.dp).size(18.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(model.name ?: model.id, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(model.id, color = colors.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace); FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { modelMetaParts(model).take(6).forEach { TinyMetaChip(it, selected = selected) } } }
            if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        }
    }
}
private fun modelMetaParts(model: PiModel): List<String> = buildList { model.providerNameOrNull()?.let { add(it) }; model.contextWindow?.let { add("${formatTokens(it)} ctx") }; model.maxTokens?.let { add("${formatTokens(it)} max") }; if (model.reasoning == true) add("reasoning"); model.input?.takeIf { it.isNotEmpty() }?.let { add(it.joinToString("/")) } }
@Composable private fun TinyMetaChip(text: String, selected: Boolean = false) { val colors = MaterialTheme.colorScheme; Surface(shape = RoundedCornerShape(999.dp), color = if (selected) colors.primary.copy(alpha = .12f) else colors.surfaceContainerHighest, contentColor = if (selected) colors.primary else colors.onSurfaceVariant, border = BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = .28f) else colors.outlineVariant)) { Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
private fun thinkingDescription(level: String): String = when (level) { "off" -> "关闭扩展思考"; "minimal" -> "最少推理"; "low" -> "低强度思考"; "medium" -> "默认平衡"; "high" -> "更强推理"; "xhigh" -> "超高推理"; else -> "" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsSheet(state: AgentUiState, manager: PiAgentManager, onDismiss: () -> Unit) {
    var rename by remember { mutableStateOf<AgentSessionRecord?>(null) }
    var delete by remember { mutableStateOf<AgentSessionRecord?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetHeader(Icons.Rounded.Menu, "Sessions", "管理本机 pi RPC sessions")
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { manager.createSession(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("新建 Session") }
            state.sessions.forEach { session -> SessionRow(session, active = session.id == state.activeSessionId, onSelect = { manager.selectSession(session.id); onDismiss() }, onStartStop = { if (session.status == "running" || session.status == "working") manager.stopSession(session.id) else manager.startSession(session.id) }, onRename = { rename = session }, onDelete = { delete = session }) }
            Spacer(Modifier.height(20.dp))
        }
    }
    rename?.let { s -> InputDialogLite("重命名 Session", s.name, onDismiss = { rename = null }, onConfirm = { manager.renameSession(s.id, it); rename = null }) }
    delete?.let { s -> ConfirmDialogLite("删除 Session", "删除 ${s.name}?", onDismiss = { delete = null }, onConfirm = { manager.deleteSession(s.id); delete = null }) }
}

@Composable
private fun SessionRow(session: AgentSessionRecord, active: Boolean, onSelect: () -> Unit, onStartStop: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onSelect), colors = CardDefaults.outlinedCardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f) else MaterialTheme.colorScheme.surfaceContainerLow), border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (active) Icons.Rounded.CheckCircle else Icons.Rounded.SmartToy, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) { Text(session.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(listOf(session.provider, session.model).filterNotNull().joinToString(" · ").ifBlank { "默认助手" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            IconButton(onClick = onRename) { Icon(Icons.Rounded.Edit, contentDescription = "Rename") }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsSheet(state: AgentUiState, guiState: GuiSessionState, manager: PiAgentManager, onDismiss: () -> Unit, onOpenTerminal: () -> Unit, onLaunchGui: () -> Unit, onInsertComposer: (String) -> Unit) {
    var tab by remember { mutableStateOf(AgentToolTab.Files) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetHeader(Icons.Rounded.FormatListBulleted, "Tools", "Terminal、Files、Pi、IDA")
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
            TabRow(selectedTabIndex = tab.ordinal) { AgentToolTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, icon = { Icon(when (t) { AgentToolTab.Terminal -> Icons.Rounded.Terminal; AgentToolTab.Files -> Icons.Rounded.Folder; AgentToolTab.Pi -> Icons.Rounded.Settings; AgentToolTab.Ida -> Icons.Rounded.Code }, contentDescription = null) }, text = { Text(when (t) { AgentToolTab.Terminal -> "Terminal"; AgentToolTab.Files -> "Files"; AgentToolTab.Pi -> "Pi"; AgentToolTab.Ida -> "IDA" }) }) } }
            Box(Modifier.fillMaxWidth().heightIn(min = 520.dp, max = 640.dp)) { when (tab) { AgentToolTab.Terminal -> TerminalToolTab(onOpenTerminal); AgentToolTab.Files -> FileBrowserTab(manager, onInsertComposer); AgentToolTab.Pi -> PiSettingsTab(state, manager); AgentToolTab.Ida -> IdaToolTab(guiState, onLaunchGui, onOpenTerminal, onInsertComposer, onDismiss) } }
        }
    }
}

@Composable
private fun TerminalToolTab(onOpenTerminal: () -> Unit) { Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) { Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.padding(10.dp).size(24.dp)) }; Column(Modifier.weight(1f)) { Text("独立终端", fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Shell 进入 /root/pi_workspace", color = MaterialTheme.colorScheme.onSurfaceVariant) } }; Button(onClick = onOpenTerminal, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Terminal, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("打开 proot 终端") } } }; ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("快捷键", fontWeight = FontWeight.Bold); Text("ESC · TAB · CTRL · ALT · arrows · paste", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) } } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileBrowserTab(manager: PiAgentManager, onInsertComposer: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf(".") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var createKind by remember { mutableStateOf<String?>(null) }
    var createName by remember { mutableStateOf("") }
    var actionEntry by remember { mutableStateOf<FileEntry?>(null) }
    var preview by remember { mutableStateOf<Pair<FileEntry, String>?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun reload() { scope.launch { loading = true; error = null; runCatching { manager.listFiles(path) }.onSuccess { entries = it }.onFailure { error = it.message }; loading = false } }
    LaunchedEffect(path) { reload() }
    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> scope.launch { uris.mapNotNull { runCatching { manager.readDraftAttachment(it) }.getOrNull() }.forEach { manager.uploadFile(path, it) }; reload() } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { FilledTonalIconButton(onClick = { path = parentPath(path) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "上级", modifier = Modifier.size(19.dp)) }; FilledTonalIconButton(onClick = { reload() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(19.dp)) }; FilledTonalButton(onClick = { pickUpload.launch(arrayOf("*/*")) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("上传", fontSize = 13.sp) }; OutlinedButton(onClick = { createKind = "file"; createName = "" }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Icon(Icons.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("文件", fontSize = 13.sp) }; OutlinedButton(onClick = { createKind = "dir"; createName = "" }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("目录", fontSize = 13.sp) } }
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) { Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary); BasicTextField(value = path, onValueChange = { path = it.ifBlank { "." } }, singleLine = true, textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f)) } }
        createKind?.let { kind -> Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (kind == "dir") Icons.Rounded.CreateNewFolder else Icons.Rounded.NoteAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary); OutlinedTextField(createName, { createName = it }, label = { Text(if (kind == "dir") "目录名" else "文件名") }, singleLine = true, modifier = Modifier.weight(1f)); TextButton(onClick = { createKind = null; createName = "" }) { Text("取消") }; Button(onClick = { val name = createName.trim(); if (!isSafeFileName(name)) { error = "名称不能包含 /、\\ 或 .."; return@Button }; scope.launch { if (kind == "dir") manager.mkdir(if (path == ".") name else "$path/$name") else manager.uploadFile(path, DraftAttachment(name, "text/plain", ByteArray(0), false)); createKind = null; createName = ""; reload() } }, enabled = createName.isNotBlank()) { Text("创建") } } } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { LazyColumn(Modifier.fillMaxSize()) { item { FileHeaderRow() }; items(entries.sortedWith(compareBy<FileEntry> { it.type != "directory" }.thenBy { it.name.lowercase() }), key = { it.path }) { entry -> FileRow(entry, onOpen = { if (entry.type == "directory") path = entry.path else scope.launch { runCatching { manager.readFileText(entry.path) }.onSuccess { preview = entry to it }.onFailure { error = it.message } } }, onAttach = { onInsertComposer(manager.fileRef(manager.workspaceAbsPath(entry.path))) }, onMore = { actionEntry = entry }) }; if (!loading && entries.isEmpty()) item { Text("空目录", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
    }
    actionEntry?.let { entry -> ModalBottomSheet(onDismissRequest = { actionEntry = null }) { SheetHeader(if (entry.type == "directory") Icons.Rounded.Folder else Icons.Rounded.Description, entry.name, manager.workspaceAbsPath(entry.path)); if (entry.type == "directory") ListItem(headlineContent = { Text("打开目录") }, leadingContent = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) }, modifier = Modifier.clickable { path = entry.path; actionEntry = null }); if (entry.type == "file") ListItem(headlineContent = { Text("预览文本") }, leadingContent = { Icon(Icons.Rounded.Visibility, contentDescription = null) }, modifier = Modifier.clickable { actionEntry = null; scope.launch { runCatching { manager.readFileText(entry.path) }.onSuccess { preview = entry to it }.onFailure { error = it.message } } }); if (entry.type == "file") ListItem(headlineContent = { Text("附加到消息") }, supportingContent = { Text(manager.fileRef(manager.workspaceAbsPath(entry.path))) }, leadingContent = { Icon(Icons.Rounded.AttachFile, contentDescription = null) }, modifier = Modifier.clickable { onInsertComposer(manager.fileRef(manager.workspaceAbsPath(entry.path))); actionEntry = null }); ListItem(headlineContent = { Text("复制路径") }, supportingContent = { Text(manager.workspaceAbsPath(entry.path)) }, leadingContent = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) }, modifier = Modifier.clickable { clipboard.setText(AnnotatedString(manager.workspaceAbsPath(entry.path))); actionEntry = null }); ListItem(headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) }, leadingContent = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }, modifier = Modifier.clickable { scope.launch { manager.deleteFile(entry.path); actionEntry = null; reload() } }); Spacer(Modifier.height(18.dp)) } }
    preview?.let { (entry, body) -> MessageDialog("${entry.name}\n\n$body", onDismiss = { preview = null }) }
}

@Composable private fun FileHeaderRow() { Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text("NAME", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text("SIZE", Modifier.width(76.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Spacer(Modifier.width(84.dp)) }; HorizontalDivider() }
@Composable private fun FileRow(entry: FileEntry, onOpen: () -> Unit, onAttach: () -> Unit, onMore: () -> Unit) { Column(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).clickable { onOpen() }.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) { if (entry.type == "directory") Icon(Icons.Rounded.Folder, contentDescription = null, tint = Color(0xFFD6A433), modifier = Modifier.size(20.dp)) else Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(entry.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("/root/pi_workspace/${entry.path}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) }; Text(if (entry.type == "file") formatBytes(entry.size) else "dir", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp), maxLines = 1); if (entry.type == "file") IconButton(onClick = onAttach, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.AttachFile, contentDescription = "附加", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) } else Spacer(Modifier.width(34.dp)); IconButton(onClick = onMore, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.MoreVert, contentDescription = "文件操作", modifier = Modifier.size(18.dp)) } }; HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)) } }

@Composable
private fun PiSettingsTab(state: AgentUiState, manager: PiAgentManager) {
    var snapshot by remember { mutableStateOf(manager.getPiConfigSnapshot()) }
    LaunchedEffect(state.piConfig) { snapshot = state.piConfig }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text("Pi config", fontWeight = FontWeight.Bold); Text("PI_CODING_AGENT_DIR: ${snapshot.materializedDir}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        item { state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { SettingsCard("模型与运行参数") { OutlinedTextField(snapshot.defaultProvider, { snapshot = snapshot.copy(defaultProvider = it) }, label = { Text("默认 Provider") }, singleLine = true, modifier = Modifier.fillMaxWidth()); OutlinedTextField(snapshot.defaultModel, { snapshot = snapshot.copy(defaultModel = it) }, label = { Text("默认 Model") }, singleLine = true, modifier = Modifier.fillMaxWidth()); DropdownField("Thinking", snapshot.defaultThinkingLevel, ThinkingLevels) { snapshot = snapshot.copy(defaultThinkingLevel = it) }; OutlinedTextField(snapshot.enabledModels, { snapshot = snapshot.copy(enabledModels = it) }, label = { Text("enabledModels") }, singleLine = true, modifier = Modifier.fillMaxWidth()) } }
        item { SettingsCard("JSON 配置") { CodeTextField("环境变量 JSON", snapshot.envText) { snapshot = snapshot.copy(envText = it) }; CodeTextField("models.json", snapshot.modelsText) { snapshot = snapshot.copy(modelsText = it) }; CodeTextField("settings.json", snapshot.settingsText) { snapshot = snapshot.copy(settingsText = it) } } }
        item { SettingsCard("插件与启动参数") { Text("每行一个传给 pi RPC 进程的额外参数，保存后重启 Session 生效。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); CodeTextField("extraArgs", snapshot.extraArgsText) { snapshot = snapshot.copy(extraArgsText = it) } } }
        item { SettingsCard("Prompt 与项目上下文") { CodeTextField("SYSTEM.md", snapshot.systemPrompt) { snapshot = snapshot.copy(systemPrompt = it) }; CodeTextField("APPEND_SYSTEM.md", snapshot.appendSystem) { snapshot = snapshot.copy(appendSystem = it) }; CodeTextField("AGENTS.md", snapshot.agentsMd) { snapshot = snapshot.copy(agentsMd = it) } } }
        item { Button(onClick = { manager.savePiConfig(snapshot) }, modifier = Modifier.fillMaxWidth()) { Text("保存 Pi 配置") } }
    }
}

@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, fontWeight = FontWeight.Bold); content() } } }
@Composable private fun CodeTextField(label: String, value: String, onValue: (String) -> Unit) { OutlinedTextField(value, onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), maxLines = 12) }
@Composable private fun DropdownField(label: String, value: String, options: List<String>, onChange: (String) -> Unit) { var expanded by remember { mutableStateOf(false) }; Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：$value") }; DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { options.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { expanded = false; onChange(it) }) } } } }

@Composable
private fun IdaToolTab(guiState: GuiSessionState, onLaunchGui: () -> Unit, onOpenTerminal: () -> Unit, onInsertComposer: (String) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("IDA MCP 工作流", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("IDA GUI：${guiState.status.name}  VNC：${guiState.port ?: "-"}", color = MaterialTheme.colorScheme.onSurfaceVariant); Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { Button(onClick = onLaunchGui, modifier = Modifier.weight(1f)) { Text(if (guiState.status == GuiStatus.Running) "打开 Viewer" else "Launch IDA") }; OutlinedButton(onClick = onOpenTerminal, modifier = Modifier.weight(1f)) { Text("Terminal") } } } }
        val prompt = "请先阅读 /root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md，然后连接当前 IDA，运行一次 MCP 诊断并说明结果。"
        Button(onClick = { onInsertComposer(prompt); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.AttachFile, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("插入 MCP 诊断 Prompt") }
        Text("规则：App 不硬编码 mcpc 参数。让 agent 先读使用说明，再按 rootfs 内实际版本调用。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SearchMessagesSheet(messages: List<ChatMessage>, onDismiss: () -> Unit, onJump: (ChatMessage) -> Unit) { var query by remember { mutableStateOf("") }; val results = remember(messages, query) { if (query.isBlank()) messages.takeLast(30).asReversed() else messages.filter { it.preview().contains(query, ignoreCase = true) } }; ModalBottomSheet(onDismissRequest = onDismiss) { SheetHeader(Icons.Rounded.Search, "搜索消息", "搜索当前 Session"); OutlinedTextField(query, { query = it }, label = { Text("输入关键词") }, leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)); LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), contentPadding = PaddingValues(vertical = 8.dp)) { items(results, key = { it.id }) { msg -> ListItem(headlineContent = { Text(msg.preview().ifBlank { msg.role }, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(msg.role, color = MaterialTheme.colorScheme.onSurfaceVariant) }, leadingContent = { Icon(when (msg.role) { "user" -> Icons.Rounded.Person; "assistant" -> Icons.Rounded.AutoAwesome; "tool" -> Icons.Rounded.Build; else -> Icons.Rounded.Settings }, contentDescription = null) }, modifier = Modifier.clickable { onJump(msg) }) }; if (results.isEmpty()) item { Text("没有匹配消息", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
@Composable private fun MessageDialog(text: String, onDismiss: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }, title = { Text("消息内容") }, text = { Box(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { MarkdownishText(text, selectable = true) } }) }
@Composable private fun InputDialogLite(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) { var value by remember { mutableStateOf(initial) }; AlertDialog(onDismissRequest = onDismiss, confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth()) }) }
@Composable private fun ConfirmDialogLite(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, confirmButton = { Button(onClick = onConfirm) { Text("确定") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }, title = { Text(title) }, text = { Text(text) }) }
@Composable private fun ExpandableBlock(title: String, body: String, autoOpen: Boolean, autoCollapse: Boolean, stateKey: String) { var open by rememberSaveable(stateKey) { mutableStateOf(autoOpen) }; var openedByAuto by rememberSaveable(stateKey) { mutableStateOf(autoOpen) }; LaunchedEffect(autoOpen, autoCollapse) { if (autoOpen) { open = true; openedByAuto = true } else if (autoCollapse && openedByAuto) { open = false; openedByAuto = false } }; OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .35f))) { Column { Row(Modifier.fillMaxWidth().clickable { open = !open; openedByAuto = false }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null); Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("${body.length} chars", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }; if (open) Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { MarkdownishText(body, selectable = true) } } } }

private fun ChatMessage.preview(): String = text.ifBlank { toolResult ?: toolName ?: attachmentSummary(attachments) }
private fun appendComposerText(current: String, insert: String): String { val value = insert.trim(); if (value.isBlank()) return current; val prefix = if (current.isBlank() || current.last().isWhitespace()) "" else " "; return "$current$prefix$value " }
private fun removeComposerRef(current: String, path: String): String = current.replace("@$path", "").replace("@\"$path\"", "").replace(Regex("\\s+"), " ").trimStart()
private fun parentPath(path: String): String = if (path == "." || path.isBlank()) "." else path.split('/').dropLast(1).joinToString("/").ifBlank { "." }
private fun isSafeFileName(name: String): Boolean = name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != ".." && !name.contains("..")
private fun formatTokens(value: Long): String = when { value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0); value >= 1_000 -> "${value / 1_000}k"; else -> value.toString() }
private fun formatBytes(value: Long): String = when { value >= 1024L * 1024L * 1024L -> "%.1f GiB".format(value / 1024.0 / 1024.0 / 1024.0); value >= 1024L * 1024L -> "%.1f MiB".format(value / 1024.0 / 1024.0); value >= 1024L -> "${value / 1024} KiB"; else -> "$value B" }
