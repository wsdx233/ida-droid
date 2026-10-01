package dev.idadroid.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.AgentUiState
import dev.idadroid.agent.DraftAttachment
import dev.idadroid.agent.FileEntry
import dev.idadroid.agent.PiAgentManager
import dev.idadroid.files.RootfsFileSharing
import dev.idadroid.vnc.GuiSessionState
import dev.idadroid.vnc.GuiStatus
import kotlinx.coroutines.launch

private enum class AgentToolTab { Terminal, Files, Pi, Ida }
@Composable
internal fun ToolsSidePane(
    state: AgentUiState,
    guiState: GuiSessionState,
    manager: PiAgentManager,
    onOpenTerminal: () -> Unit,
    onLaunchGui: () -> Unit,
    onClose: () -> Unit,
    onOpenModelsEditor: () -> Unit,
    onInsertComposer: (String) -> Unit
) {
    var tab by remember { mutableStateOf(AgentToolTab.Files) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
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
                AgentToolTab.Pi -> PiSettingsTab(state, manager, initialShowModelsEditor = false, onOpenFullScreenEditor = onOpenModelsEditor)
                AgentToolTab.Ida -> IdaToolTab(guiState, onLaunchGui, onOpenTerminal, onInsertComposer, onClose)
            }
        }
    }
}

@Composable
internal fun EmptySideCard(title: String, subtitle: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var saveAsEntry by remember { mutableStateOf<FileEntry?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun reload() { scope.launch { loading = true; error = null; dev.idadroid.util.runCatchingSuspending { manager.listFiles(path) }.onSuccess { entries = it }.onFailure { error = it.message }; loading = false } }
    LaunchedEffect(path) { reload() }
    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> scope.launch { uris.mapNotNull { dev.idadroid.util.runCatchingSuspending { manager.readDraftAttachment(it) }.getOrNull() }.forEach { manager.uploadFile(path, it) }; reload() } }
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> val entry = saveAsEntry; saveAsEntry = null; if (uri != null && entry != null) scope.launch { loading = true; error = null; dev.idadroid.util.runCatchingSuspending { manager.saveFileAs(entry.path, uri) }.onFailure { error = "另存为失败：${it.message}" }; loading = false } }
    fun openFile(entry: FileEntry) { if (entry.type != "file") return; scope.launch { error = null; dev.idadroid.util.runCatchingSuspending { manager.fileForSharing(entry.path) }.onSuccess { file -> runCatching { RootfsFileSharing.openFile(context, file) }.onFailure { error = "打开失败：${it.message}" } }.onFailure { error = "打开失败：${it.message}" } } }
    fun saveAs(entry: FileEntry) { if (entry.type != "file") return; saveAsEntry = entry; saveAsLauncher.launch(entry.name) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { FilledTonalIconButton(onClick = { path = parentPath(path) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "上级", modifier = Modifier.size(19.dp)) }; FilledTonalIconButton(onClick = { reload() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(19.dp)) }; FilledTonalButton(onClick = { pickUpload.launch(arrayOf("*/*")) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("上传", fontSize = 13.sp) }; OutlinedButton(onClick = { createKind = "file"; createName = "" }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("文件", fontSize = 13.sp) }; OutlinedButton(onClick = { createKind = "dir"; createName = "" }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("目录", fontSize = 13.sp) } }
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) { Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary); BasicTextField(value = path, onValueChange = { path = it.ifBlank { "." } }, singleLine = true, textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f)) } }
        createKind?.let { kind -> Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (kind == "dir") Icons.Rounded.CreateNewFolder else Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary); OutlinedTextField(createName, { createName = it }, label = { Text(if (kind == "dir") "目录名" else "文件名") }, singleLine = true, modifier = Modifier.weight(1f)); TextButton(onClick = { createKind = null; createName = "" }) { Text("取消") }; Button(onClick = { val name = createName.trim(); if (!isSafeFileName(name)) { error = "名称不能包含 /、\\ 或 .."; return@Button }; scope.launch { if (kind == "dir") manager.mkdir(if (path == ".") name else "$path/$name") else manager.uploadFile(path, DraftAttachment(name, "text/plain", ByteArray(0), false)); createKind = null; createName = ""; reload() } }, enabled = createName.isNotBlank()) { Text("创建") } } } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { val sortedEntries = remember(entries) { entries.sortedWith(compareBy<FileEntry> { it.type != "directory" }.thenBy { it.name.lowercase() }) }; LazyColumn(Modifier.fillMaxSize()) { item { FileHeaderRow() }; items(sortedEntries, key = { it.path }) { entry -> FileRow(entry, onOpen = { if (entry.type == "directory") path = entry.path else openFile(entry) }, onAttach = { onInsertComposer(manager.fileRef(manager.workspaceAbsPath(entry.path))) }, onMore = { actionEntry = entry }) }; if (!loading && entries.isEmpty()) item { Text("空目录", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
    }
    actionEntry?.let { entry -> ModalBottomSheet(onDismissRequest = { actionEntry = null }) { SheetHeader(if (entry.type == "directory") Icons.Rounded.Folder else Icons.Rounded.Description, entry.name, manager.workspaceAbsPath(entry.path)); if (entry.type == "directory") ListItem(headlineContent = { Text("打开目录") }, leadingContent = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) }, modifier = Modifier.clickable { path = entry.path; actionEntry = null }); if (entry.type == "file") ListItem(headlineContent = { Text("打开/编辑") }, supportingContent = { Text("通过 Android 内容提供器交给外部应用") }, leadingContent = { Icon(Icons.Rounded.Visibility, contentDescription = null) }, modifier = Modifier.clickable { actionEntry = null; openFile(entry) }); if (entry.type == "file") ListItem(headlineContent = { Text("另存为") }, supportingContent = { Text("使用系统文件选择器保存到本地") }, leadingContent = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) }, modifier = Modifier.clickable { actionEntry = null; saveAs(entry) }); if (entry.type == "file") ListItem(headlineContent = { Text("附加到消息") }, supportingContent = { Text(manager.fileRef(manager.workspaceAbsPath(entry.path))) }, leadingContent = { Icon(Icons.Rounded.AttachFile, contentDescription = null) }, modifier = Modifier.clickable { onInsertComposer(manager.fileRef(manager.workspaceAbsPath(entry.path))); actionEntry = null }); ListItem(headlineContent = { Text("复制路径") }, supportingContent = { Text(manager.workspaceAbsPath(entry.path)) }, leadingContent = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) }, modifier = Modifier.clickable { clipboard.setText(AnnotatedString(manager.workspaceAbsPath(entry.path))); actionEntry = null }); ListItem(headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) }, leadingContent = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }, modifier = Modifier.clickable { scope.launch { dev.idadroid.util.runCatchingSuspending { manager.deleteFile(entry.path) }.onSuccess { actionEntry = null; reload() }.onFailure { error = "删除失败：${it.message}" } } }); Spacer(Modifier.height(18.dp)) } }
}

@Composable private fun FileHeaderRow() { Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text("NAME", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Text("SIZE", Modifier.width(76.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold); Spacer(Modifier.width(84.dp)) }; HorizontalDivider() }
@Composable private fun FileRow(entry: FileEntry, onOpen: () -> Unit, onAttach: () -> Unit, onMore: () -> Unit) { Column(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen() }.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) { if (entry.type == "directory") Icon(Icons.Rounded.Folder, contentDescription = null, tint = Color(0xFFD6A433), modifier = Modifier.size(20.dp)) else Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(entry.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("/root/pi_workspace/${entry.path}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) }; Text(if (entry.type == "file") formatBytes(entry.size) else "dir", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp), maxLines = 1); if (entry.type == "file") IconButton(onClick = onAttach, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.AttachFile, contentDescription = "附加 ${entry.name}", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) } else Spacer(Modifier.width(48.dp)); IconButton(onClick = onMore, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.MoreVert, contentDescription = "文件操作", modifier = Modifier.size(18.dp)) } }; HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)) } }

@Composable
private fun IdaToolTab(guiState: GuiSessionState, onLaunchGui: () -> Unit, onOpenTerminal: () -> Unit, onInsertComposer: (String) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("IDA MCP 工作流", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("IDA GUI：${guiState.status.name}  VNC：${guiState.port ?: "-"}", color = MaterialTheme.colorScheme.onSurfaceVariant); Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { Button(onClick = onLaunchGui, modifier = Modifier.weight(1f)) { Text(if (guiState.status == GuiStatus.Running) "打开 Viewer" else "Launch IDA") }; OutlinedButton(onClick = onOpenTerminal, modifier = Modifier.weight(1f)) { Text("Terminal") } } } }
        val prompt = "请先阅读 /root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md，然后连接当前 IDA，运行一次 MCP 诊断并说明结果。"
        Button(onClick = { onInsertComposer(prompt); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.AttachFile, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("插入 MCP 诊断 Prompt") }
        Text("规则：App 不硬编码 mcpc 参数。让 agent 先读使用说明，再按 rootfs 内实际版本调用。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}
private fun parentPath(path: String): String = if (path == "." || path.isBlank()) "." else path.split('/').dropLast(1).joinToString("/").ifBlank { "." }
private fun isSafeFileName(name: String): Boolean = name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != ".." && !name.contains("..")
