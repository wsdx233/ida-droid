package dev.idadroid.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.AgentUiState
import dev.idadroid.agent.WorkspaceFileEntry
import dev.idadroid.agent.PiAgentManager
import kotlinx.coroutines.launch

/**
 * 工作区侧边面板。
 *
 * 用户可以：
 * 1. 点击"选择工作区文件夹"通过 SAF 选择一个文件夹作为工作区。
 * 2. 浏览工作区中的文件和子目录。
 * 3. 将工作区文件导入到 pi_workspace 容器内供 Agent 使用。
 * 4. 将工作区文件内容作为文本引用插入到输入框。
 * 5. 将工作区文件作为附件发送给 Agent。
 * 6. 将容器内文件导出到工作区。
 * 7. 清除当前工作区。
 */
@Composable
internal fun WorkspaceSidePane(
    state: AgentUiState,
    manager: PiAgentManager,
    onClose: () -> Unit,
    onInsertComposer: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ws = state.workspace

    // SAF 文件夹选择器
    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            manager.setWorkspace(uri)
        }
    }

    var showExportDialog by remember { mutableStateOf(false) }
    var exportPath by remember { mutableStateOf("") }
    var importFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ws.hasWorkspace, ws.currentPath) {
        if (ws.hasWorkspace && ws.files.isEmpty() && !ws.loading) {
            manager.refreshWorkspaceFiles(ws.currentPath)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = if (ws.hasWorkspace) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (ws.hasWorkspace) ws.workspaceName.ifBlank { "工作区" } else "工作区",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (ws.hasWorkspace) {
                            Text(
                                ws.workspaceUri,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text("未选择工作区文件夹", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (!ws.hasWorkspace) {
                    // 参考 Operit 的工作区引导：两个卡片选项
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        // 创建默认
                        Surface(
                            modifier = Modifier.weight(1f).clickable {
                                // 在应用私有目录创建默认工作区
                                val defaultDir = java.io.File(context.filesDir, "workspace")
                                if (!defaultDir.exists()) defaultDir.mkdirs()
                                // 转 URI 给 SAF
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, "${context.packageName}.rootfs-file-provider", defaultDir
                                )
                                runCatching {
                                    context.contentResolver.takePersistableUriPermission(
                                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    )
                                }
                                manager.setWorkspace(uri)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                Text("创建默认", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("应用内创建", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // 选择现有
                        Surface(
                            modifier = Modifier.weight(1f).clickable { pickFolder.launch(null) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                                Text("选择现有", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("从设备中选择", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { pickFolder.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("更换工作区")
                        }
                        OutlinedButton(onClick = { manager.clearWorkspace() }) {
                            Text("清除")
                        }
                    }
                }

                if (ws.hasWorkspace) {
                    OutlinedButton(
                        onClick = { showExportDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("从容器导出文件到工作区")
                    }
                }
            }
        }

        ws.error?.let { err ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(err, Modifier.padding(12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        importFeedback?.let { feedback ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(feedback, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                    TextButton(onClick = { importFeedback = null }) { Text("关闭") }
                }
            }
        }

        if (ws.hasWorkspace) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("路径：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ws.currentPath.isBlank()) {
                    Text("根目录", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                } else {
                    TextButton(
                        onClick = { manager.refreshWorkspaceFiles("") },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) { Text("根目录", fontSize = 12.sp) }
                    val parts = ws.currentPath.split('/').filter { it.isNotBlank() }
                    parts.forEachIndexed { index, part ->
                        Text("/", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (index == parts.lastIndex) {
                            Text(part, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        } else {
                            val target = parts.take(index + 1).joinToString("/")
                            TextButton(
                                onClick = { manager.refreshWorkspaceFiles(target) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) { Text(part, fontSize = 12.sp) }
                        }
                    }
                }
            }

            if (ws.loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (ws.files.isEmpty()) {
                EmptySideCard("工作区为空", "此文件夹中没有文件。点击下方刷新按钮重新加载。")
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (ws.currentPath.isNotBlank()) {
                        item(key = "__parent__") {
                            Surface(
                                onClick = { manager.navigateWorkspace("..") },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                    Text("..", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    items(ws.files, key = { it.documentId }) { entry ->
                        WorkspaceFileRow(
                            entry = entry,
                            onOpen = {
                                if (entry.isDirectory) {
                                    manager.navigateWorkspace(entry.name)
                                }
                            },
                            onImport = {
                                manager.importWorkspaceFileToContainer(entry) { ok, msg ->
                                    importFeedback = if (ok) "已导入 ${entry.name} 到容器：$msg" else "导入失败：$msg"
                                }
                            },
                            onInsertText = {
                                scope.launch {
                                    val content = manager.readWorkspaceFileAsText(entry)
                                    if (content != null) {
                                        val snippet = if (content.length > 2000) content.take(2000) + "\n... (已截断，共 ${content.length} 字符)" else content
                                        onInsertComposer("\n\n```${entry.name.substringAfterLast('.', "")}\n$snippet\n```\n")
                                        importFeedback = "已将 ${entry.name} 内容插入输入框"
                                    }
                                }
                            },
                            onSendAsAttachment = {
                                scope.launch {
                                    val attachment = manager.readWorkspaceFileAsAttachment(entry)
                                    if (attachment != null) {
                                        importFeedback = "已读取 ${entry.name}（${attachment.bytes.size} 字节），请在主界面发送"
                                    }
                                }
                            }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { manager.refreshWorkspaceFiles(ws.currentPath) }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出容器文件到工作区") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("输入容器内文件的相对路径（相对于工作区）：", fontSize = 13.sp)
                    OutlinedTextField(
                        value = exportPath,
                        onValueChange = { exportPath = it },
                        placeholder = { Text("例如：output.py") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (exportPath.isNotBlank()) {
                        manager.exportFileToWorkspace(exportPath.trim()) { ok, msg ->
                            importFeedback = if (ok) "已导出到工作区：$msg" else "导出失败：$msg"
                        }
                        showExportDialog = false
                        exportPath = ""
                    }
                }) { Text("导出") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false; exportPath = "" }) { Text("取消") }
            }
        )
    }
}

/** 工作区文件行。 */
@Composable
private fun WorkspaceFileRow(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onImport: () -> Unit,
    onInsertText: () -> Unit,
    onSendAsAttachment: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(8.dp),
        color = colors.surface
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                contentDescription = null,
                tint = if (entry.isDirectory) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (entry.isDirectory) "文件夹" else formatFileSize(entry.size),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant
                )
            }
            if (!entry.isDirectory) {
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "操作", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("导入到容器") },
                            onClick = { showMenu = false; onImport() },
                            leadingIcon = { Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("插入到输入框") },
                            onClick = { showMenu = false; onInsertText() },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("作为附件发送") },
                            onClick = { showMenu = false; onSendAsAttachment() },
                            leadingIcon = { Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        }
    }
}

/** 格式化文件大小。 */
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
    if (bytes < 1024 * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
    return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

