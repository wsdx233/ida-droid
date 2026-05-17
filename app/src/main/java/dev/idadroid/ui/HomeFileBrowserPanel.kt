package dev.idadroid.ui

import android.content.pm.ApplicationInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
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
import dev.idadroid.files.ContainerFileEntry
import dev.idadroid.files.ContainerFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeFileBrowserPanel(fileManager: ContainerFileManager) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("idadroid_file_browser", android.content.Context.MODE_PRIVATE) }
    var path by remember { mutableStateOf(prefs.getString("path", "/root/pi_workspace") ?: "/root/pi_workspace") }
    var bookmarks by remember {
        mutableStateOf(
            prefs.getStringSet("bookmarks", emptySet())
                .orEmpty()
                .map { normalizeContainerFileBrowserPath(it) }
                .distinct()
                .sorted()
        )
    }
    var showBookmarksPanel by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<ContainerFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var createKind by remember { mutableStateOf<String?>(null) }
    var createName by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<ContainerFileEntry, String>?>(null) }
    var actionEntry by remember { mutableStateOf<ContainerFileEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<ContainerFileEntry?>(null) }
    var showApps by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(false) }

    val normalizedPath = normalizeContainerFileBrowserPath(path)
    val currentBookmarked = bookmarks.contains(normalizedPath)

    fun saveBookmarkList(next: List<String>) {
        val cleaned = next.map { normalizeContainerFileBrowserPath(it) }.distinct().sorted()
        bookmarks = cleaned
        prefs.edit().putStringSet("bookmarks", cleaned.toSet()).apply()
    }
    fun toggleBookmark(targetPath: String = path) {
        val normalized = normalizeContainerFileBrowserPath(targetPath)
        if (normalized in bookmarks) saveBookmarkList(bookmarks.filterNot { it == normalized }) else saveBookmarkList(bookmarks + normalized)
    }
    fun createTargetPath(name: String): String {
        val base = normalizeContainerFileBrowserPath(path).trimEnd('/')
        return if (base.isBlank()) "/$name" else "$base/$name"
    }
    fun reload() {
        scope.launch {
            loading = true
            error = null
            runCatching { fileManager.listFiles(path) }
                .onSuccess { entries = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(path) {
        prefs.edit().putString("path", path).apply()
        reload()
    }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            error = null
            runCatching { uris.forEach { uri -> fileManager.uploadFile(path, uri) } }
                .onFailure { error = "上传失败：${it.message}" }
            loading = false
            reload()
        }
    }

    if (showApps) {
        AlertDialog(
            onDismissRequest = { showApps = false },
            confirmButton = { TextButton(onClick = { showApps = false }) { Text("关闭") } },
            title = { Text("导入本机应用") },
            text = {
                Column(Modifier.height(460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (appsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.Android, contentDescription = null, tint = Color(0xFF2E7D32))
                                    Column(Modifier.weight(1f)) {
                                        Text(app.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(app.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    TextButton(onClick = {
                                        scope.launch {
                                            runCatching { fileManager.importInstalledApk(app.packageName, app.label, app.sourceDir, path) }
                                                .onSuccess { reload(); showApps = false }
                                                .onFailure { error = "导入 APK 失败：${it.message}" }
                                        }
                                    }) { Text("导入") }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { path = fileManager.parentPath(path) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "上级", modifier = Modifier.size(19.dp))
            }
            FilledTonalIconButton(onClick = { reload() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", modifier = Modifier.size(19.dp))
            }
            FilledTonalIconButton(onClick = { showBookmarksPanel = !showBookmarksPanel }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (currentBookmarked) Icons.Rounded.BookmarkAdded else Icons.Rounded.Bookmarks,
                    contentDescription = "书签",
                    modifier = Modifier.size(19.dp),
                    tint = if (currentBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            FilledTonalButton(onClick = { pickUpload.launch(arrayOf("*/*")) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("上传", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = {
                    showApps = true
                    scope.launch {
                        appsLoading = true
                        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
                        appsLoading = false
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Rounded.Android, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("应用", fontSize = 13.sp)
            }
            OutlinedButton(onClick = { createKind = "file"; createName = "" }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Icon(Icons.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("文件", fontSize = 13.sp)
            }
            OutlinedButton(onClick = { createKind = "dir"; createName = "" }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("目录", fontSize = 13.sp)
            }
        }
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                BasicTextField(
                    value = path,
                    onValueChange = { path = it.ifBlank { "/root/pi_workspace" } },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        AnimatedVisibility(visible = showBookmarksPanel) {
            HomeFileBookmarksPanel(
                currentPath = normalizedPath,
                bookmarks = bookmarks,
                onJump = { path = it; showBookmarksPanel = false },
                onToggleCurrent = { toggleBookmark(path) },
                onRemove = { target -> saveBookmarkList(bookmarks.filterNot { it == normalizeContainerFileBrowserPath(target) }) }
            )
        }
        createKind?.let { kind ->
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == "dir") Icons.Rounded.CreateNewFolder else Icons.Rounded.NoteAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(createName, { createName = it }, label = { Text(if (kind == "dir") "目录名" else "文件名") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { createKind = null; createName = "" }) { Text("取消") }
                    Button(onClick = {
                        val name = createName.trim()
                        if (!isSafeContainerFileName(name)) { error = "名称不能包含 /、\\ 或 .."; return@Button }
                        scope.launch {
                            error = null
                            runCatching {
                                val target = createTargetPath(name)
                                if (kind == "dir") {
                                    fileManager.createDirectory(target)
                                    path = target
                                } else {
                                    fileManager.createEmptyFile(path, name)
                                    reload()
                                }
                                createKind = null
                                createName = ""
                            }.onFailure { error = it.message }
                        }
                    }, enabled = createName.isNotBlank()) { Text("创建") }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                item { HomeFileHeaderRow() }
                items(entries.sortedWith(compareBy<ContainerFileEntry> { it.type != "directory" }.thenBy { it.name.lowercase() }), key = { it.path }) { entry ->
                    HomeFileRow(
                        entry = entry,
                        onOpen = {
                            if (entry.type == "directory") path = entry.path
                            else scope.launch { runCatching { fileManager.readFileText(entry.path) }.onSuccess { preview = entry to it }.onFailure { error = it.message } }
                        },
                        onMore = { actionEntry = entry }
                    )
                }
                if (!loading && entries.isEmpty()) item { Text("空目录", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    actionEntry?.let { entry ->
        ModalBottomSheet(onDismissRequest = { actionEntry = null }) {
            HomeSectionTitle(if (entry.type == "directory") "目录操作" else "文件操作", if (entry.type == "directory") Icons.Rounded.Folder else Icons.Rounded.Description)
            Text(entry.path, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            if (entry.type == "directory") {
                ListItem(
                    headlineContent = { Text("打开目录") },
                    leadingContent = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                    modifier = Modifier.clickable { path = entry.path; actionEntry = null }
                )
                val bookmarked = bookmarks.contains(normalizeContainerFileBrowserPath(entry.path))
                ListItem(
                    headlineContent = { Text(if (bookmarked) "移除书签" else "添加到书签") },
                    leadingContent = { Icon(if (bookmarked) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd, contentDescription = null) },
                    modifier = Modifier.clickable { toggleBookmark(entry.path); actionEntry = null }
                )
            } else {
                ListItem(
                    headlineContent = { Text("预览文本") },
                    leadingContent = { Icon(Icons.Rounded.Visibility, contentDescription = null) },
                    modifier = Modifier.clickable {
                        actionEntry = null
                        scope.launch { runCatching { fileManager.readFileText(entry.path) }.onSuccess { preview = entry to it }.onFailure { error = it.message } }
                    }
                )
            }
            ListItem(
                headlineContent = { Text("复制路径") },
                supportingContent = { Text(entry.path) },
                leadingContent = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(entry.path)); actionEntry = null }
            )
            ListItem(
                headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { deleteEntry = entry; actionEntry = null }
            )
            Spacer(Modifier.height(18.dp))
        }
    }
    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            confirmButton = { Button(onClick = { scope.launch { fileManager.deleteFile(entry.path); deleteEntry = null; reload() } }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text("取消") } },
            title = { Text("删除 ${entry.name}") },
            text = { Text("确定删除 ${entry.path}？") }
        )
    }
    preview?.let { (entry, body) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } },
            title = { Text(entry.name) },
            text = {
                Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                    Text(body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    }
}

@Composable
private fun HomeFileBookmarksPanel(
    currentPath: String,
    bookmarks: List<String>,
    onJump: (String) -> Unit,
    onToggleCurrent: () -> Unit,
    onRemove: (String) -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Bookmarks, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("书签", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("快速跳转目录", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                TextButton(onClick = onToggleCurrent, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(if (bookmarks.contains(currentPath)) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                    Text(if (bookmarks.contains(currentPath)) "移除当前" else "添加当前", fontSize = 12.sp)
                }
            }
            if (bookmarks.isEmpty()) {
                Text("还没有书签。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    bookmarks.forEach { bookmark ->
                        val normalized = normalizeContainerFileBrowserPath(bookmark)
                        val active = normalized == currentPath
                        Surface(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onJump(normalized) },
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        ) {
                            Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Bookmark, contentDescription = null, modifier = Modifier.size(17.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(containerBookmarkLabel(normalized), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(normalized, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onRemove(normalized) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, contentDescription = "移除书签", modifier = Modifier.size(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeFileHeaderRow() {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("NAME", Modifier.weight(1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Text("SIZE", Modifier.width(76.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(42.dp))
    }
    HorizontalDivider()
}

@Composable
private fun HomeFileRow(entry: ContainerFileEntry, onOpen: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).clickable { onOpen() }.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (entry.type == "directory") Icons.Rounded.Folder else Icons.Rounded.Description, contentDescription = null, tint = if (entry.type == "directory") Color(0xFFD6A433) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.path, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
            }
            Text(if (entry.type == "file") formatBytes(entry.size) else "dir", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp), maxLines = 1)
            IconButton(onClick = onMore, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.MoreVert, contentDescription = "文件操作", modifier = Modifier.size(18.dp)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
    }
}

private fun normalizeContainerFileBrowserPath(value: String): String {
    val raw = value.trim().ifBlank { "/root/pi_workspace" }.replace('\\', '/')
    val absolute = if (raw.startsWith('/')) raw else "/root/pi_workspace/$raw"
    val parts = mutableListOf<String>()
    absolute.split('/').forEach { part ->
        when {
            part.isBlank() || part == "." -> Unit
            part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return "/${parts.joinToString("/")}".trimEnd('/').ifBlank { "/" }
}

private fun containerBookmarkLabel(path: String): String = normalizeContainerFileBrowserPath(path)
    .let { normalized -> if (normalized == "/") "/" else normalized.substringAfterLast('/').ifBlank { normalized } }

private fun isSafeContainerFileName(name: String): Boolean = name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != ".." && !name.contains("..")

private data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val sourceDir: String
)

private fun loadInstalledApps(context: android.content.Context): List<InstalledAppInfo> {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    return pm.getInstalledApplications(0)
        .asSequence()
        .filter { it.sourceDir?.endsWith(".apk", ignoreCase = true) == true }
        .sortedWith(compareBy<ApplicationInfo> { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }.thenBy { it.loadLabel(pm).toString().lowercase() })
        .map {
            InstalledAppInfo(
                label = it.loadLabel(pm).toString().ifBlank { it.packageName },
                packageName = it.packageName,
                sourceDir = it.sourceDir
            )
        }
        .toList()
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L * 1024L -> "%.1f GiB".format(value / 1024.0 / 1024.0 / 1024.0)
    value >= 1024L * 1024L -> "%.1f MiB".format(value / 1024.0 / 1024.0)
    value >= 1024L -> "${value / 1024} KiB"
    else -> "$value B"
}

