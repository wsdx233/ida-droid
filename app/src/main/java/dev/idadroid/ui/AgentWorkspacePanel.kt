package dev.idadroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.idadroid.agent.AgentUiState
import dev.idadroid.agent.DraftAttachment
import dev.idadroid.agent.PiAgentManager
import dev.idadroid.agent.WorkspaceFileEntry
import dev.idadroid.agent.WorkspaceManager
import dev.idadroid.files.ContainerFileEntry
import dev.idadroid.files.ContainerFileManager
import dev.idadroid.settings.IdaDroidSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The active container directory, not a separate Android export destination. */
@Composable
internal fun WorkspaceSidePane(
    state: AgentUiState,
    manager: PiAgentManager,
    onClose: () -> Unit,
    onInsertComposer: (String) -> Unit,
    onAttach: suspend (DraftAttachment) -> Unit
) {
    val scope = rememberCoroutineScope()
    val ws = state.workspace
    var showPicker by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }

    LaunchedEffect(ws.workspacePath) {
        manager.refreshWorkspaceFiles("")
    }

    fun readFile(action: suspend () -> Unit) {
        if (reading) return
        scope.launch {
            reading = true
            actionError = null
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = "读取文件失败：${error.message.orEmpty()}"
            } finally {
                reading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(14.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("容器工作区", style = MaterialTheme.typography.titleMedium)
                Text(ws.workspacePath.ifBlank { IdaDroidSettings.DEFAULT_WORKSPACE_PATH }, style = MaterialTheme.typography.bodyMedium)
                Text("文件、模型/供应商配置和会话均保存在此目录内。切换工作区不会迁移或删除原目录的文件。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showPicker = true }, enabled = !state.isWorking) { Text("更换工作区") }
                    OutlinedButton(onClick = { manager.resetWorkspace() }, enabled = !state.isWorking) { Text("恢复默认") }
                }
                Text("默认 ${IdaDroidSettings.DEFAULT_WORKSPACE_PATH}；恢复默认不会删除文件。", style = MaterialTheme.typography.bodySmall)
            }
        }
        (actionError ?: ws.error)?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error)
        }
        feedback?.let {
            Text(it, Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { manager.navigateWorkspace("..") }, enabled = ws.currentPath.isNotEmpty() && !ws.loading) {
                Icon(Icons.Rounded.ArrowUpward, "返回上级目录")
            }
            Text(ws.currentPath.ifBlank { "工作区根目录" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { manager.refreshWorkspaceFiles(ws.currentPath) }, enabled = !ws.loading) {
                Icon(Icons.Rounded.Refresh, "刷新工作区")
            }
        }
        if (ws.loading || reading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!ws.loading && ws.files.isEmpty() && ws.error == null) {
                item { Text("此目录为空", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(ws.files, key = { it.containerPath }) { entry ->
                WorkspaceFileRow(
                    entry,
                    enabled = !reading && !ws.loading,
                    onOpen = { if (entry.isDirectory) manager.navigateWorkspace(entry.name) },
                    onInsertPath = { onInsertComposer(entry.containerPath) },
                    onInsertText = {
                        readFile {
                            val content = manager.readWorkspaceFileAsText(entry)
                            val snippet = if (content.length > 2000) content.take(2000) + "\n…（共 ${content.length} 字符，已截断）" else content
                            onInsertComposer("\n\n```${entry.name.substringAfterLast('.', "")}\n$snippet\n```\n")
                        }
                    },
                    onSendAsAttachment = {
                        readFile {
                            val attachment = manager.readWorkspaceFileAsAttachment(entry)
                            onAttach(attachment)
                            feedback = "已添加 ${entry.name} 到会话草稿"
                            onClose()
                        }
                    }
                )
            }
        }
    }
    if (showPicker) ContainerWorkspacePicker(
        initialPath = ws.workspacePath.ifBlank { IdaDroidSettings.DEFAULT_WORKSPACE_PATH },
        onDismiss = { showPicker = false },
        onSelected = { path -> selectContainerWorkspace(manager, path); showPicker = false }
    )
}

internal suspend fun selectContainerWorkspace(manager: PiAgentManager, path: String) {
    suspendCancellableCoroutine<Unit> { continuation ->
        manager.setWorkspace(path) { ok, message ->
            if (continuation.isActive) {
                if (ok) continuation.resume(Unit)
                else continuation.resumeWithException(IllegalStateException(message))
            }
        }
    }
}

/** Shared by Agent and environment settings; all filesystem operations are suspend IO calls. */
@Composable
internal fun ContainerWorkspacePicker(
    initialPath: String,
    onDismiss: () -> Unit,
    onSelected: suspend (String) -> Unit
) {
    val context = LocalContext.current
    val files = remember { ContainerFileManager(context) }
    val workspace = remember { WorkspaceManager(context) }
    val scope = rememberCoroutineScope()
    var browsingPath by remember { mutableStateOf(initialPath) }
    var pathInput by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf(emptyList<ContainerFileEntry>()) }
    var loading by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    fun browse(path: String) {
        browsingPath = path
        pathInput = path
        refresh++
    }
    fun select(create: Boolean) {
        if (selecting) return
        scope.launch {
            selecting = true
            error = null
            try {
                val path = workspace.validateWorkspace(pathInput, create)
                onSelected(path)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "无法选择工作区"
            } finally {
                selecting = false
            }
        }
    }
    LaunchedEffect(browsingPath, refresh) {
        loading = true
        entries = emptyList()
        loadedPath = null
        error = null
        try {
            entries = files.listRootfsDirectories(browsingPath)
            loadedPath = browsingPath
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.message ?: "无法读取目录"
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!selecting) onDismiss() },
        title = { Text("选择容器工作区") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("仅显示 rootfs 目录；不支持外部绑定目录或符号链接。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    pathInput, { pathInput = it },
                    label = { Text("容器绝对路径") }, singleLine = true,
                    enabled = !selecting, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { browse("/") }, enabled = !selecting) { Text("根目录") }
                    TextButton(onClick = { browse(files.parentPath(browsingPath)) }, enabled = !selecting && browsingPath != "/") { Text("上级") }
                    TextButton(onClick = { browse(pathInput) }, enabled = !selecting) { Text("浏览") }
                }
                TextButton(onClick = { browse(IdaDroidSettings.DEFAULT_WORKSPACE_PATH) }, enabled = !selecting) {
                    Text("默认：${IdaDroidSettings.DEFAULT_WORKSPACE_PATH}")
                }
                if (loading || selecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 240.dp)) {
                    if (!loading && loadedPath != null && entries.isEmpty()) {
                        item { Text("此目录没有子目录", Modifier.padding(12.dp)) }
                    }
                    items(entries, key = { it.path }) { entry ->
                        TextButton(onClick = { browse(entry.path) }, enabled = !selecting, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                TextButton(onClick = { select(true) }, enabled = !selecting && !loading) { Text("创建并选择此路径") }
            }
        },
        confirmButton = {
            Button(onClick = { select(false) }, enabled = !selecting && !loading) { Text("选择此目录") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !selecting) { Text("取消") } }
    )
}

@Composable
private fun WorkspaceFileRow(
    entry: WorkspaceFileEntry,
    enabled: Boolean,
    onOpen: () -> Unit,
    onInsertPath: () -> Unit,
    onInsertText: () -> Unit,
    onSendAsAttachment: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Surface(onClick = onOpen, enabled = enabled, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (entry.isDirectory) "文件夹" else formatFileSize(entry.size), style = MaterialTheme.typography.bodySmall)
            }
            if (!entry.isDirectory) Box {
                IconButton(onClick = { showMenu = true }, enabled = enabled) { Icon(Icons.Rounded.MoreVert, "文件操作") }
                DropdownMenu(showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("插入容器路径") }, onClick = { showMenu = false; onInsertPath() })
                    DropdownMenuItem(
                        text = { Text("插入文本到输入框") }, onClick = { showMenu = false; onInsertText() },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("作为附件发送") }, onClick = { showMenu = false; onSendAsAttachment() },
                        leadingIcon = { Icon(Icons.Rounded.AttachFile, null) }
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
    else -> "${bytes / (1024 * 1024)} MiB"
}
