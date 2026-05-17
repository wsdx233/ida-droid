package dev.idadroid.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.idadroid.env.ImportProgress
import dev.idadroid.env.ImportStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportProgressAnalysisScreen(progress: ImportProgress) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Column {
                            Text(importStageTitle(progress.stage))
                            val p = progress.progress
                            if (p == null) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .size(100.dp, 2.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                                )
                            } else {
                                LinearProgressIndicator(
                                    progress = { p.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .size(100.dp, 2.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            ImportLogList(
                logs = progress.logs,
                modifier = Modifier.weight(1f)
            )
            ImportStatusPanel(progress)
        }
    }
}

@Composable
fun ImportLogList(
    logs: List<String>,
    modifier: Modifier = Modifier,
    allowClear: Boolean = false,
    onClearLogs: () -> Unit = {}
) {
    val entries = remember(logs) { logs.toSmartLogEntries() }
    SmartLogList(
        logs = entries,
        modifier = modifier,
        allowClear = allowClear,
        onClearLogs = onClearLogs
    )
}

@Composable
private fun ImportStatusPanel(progress: ImportProgress) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "当前任务",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = progress.message.ifBlank { importStageTitle(progress.stage) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (progress.currentFile.isNotBlank()) {
                Text(
                    text = progress.currentFile,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {}, enabled = false) {
                    Text(progress.progress?.let { "${(it * 100).toInt()}%" } ?: "处理中")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmartLogList(
    logs: List<SmartLogEntry>,
    modifier: Modifier = Modifier,
    allowClear: Boolean = false,
    onClearLogs: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var rangeSelectionArmed by remember { mutableStateOf(false) }
    var rangeAnchorId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(logs.size, selectionMode) {
        if (!selectionMode && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    LaunchedEffect(logs) {
        val currentIds = logs.map { it.id }.toSet()
        val filteredSelection = selectedIds.intersect(currentIds)
        if (filteredSelection != selectedIds) selectedIds = filteredSelection
        if (selectionMode && filteredSelection.isEmpty()) {
            selectionMode = false
            rangeSelectionArmed = false
            rangeAnchorId = null
        } else if (rangeAnchorId != null && rangeAnchorId !in currentIds) {
            rangeAnchorId = logs.lastOrNull { it.id in filteredSelection }?.id
        }
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds = emptySet()
        rangeSelectionArmed = false
        rangeAnchorId = null
    }

    fun toggleItemSelection(entry: SmartLogEntry) {
        val next = if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id
        selectedIds = next
        if (next.isEmpty()) {
            exitSelectionMode()
        } else {
            selectionMode = true
            rangeAnchorId = entry.id
        }
    }

    fun selectRangeTo(targetIndex: Int) {
        val anchorIndex = logs.indexOfFirst { it.id == rangeAnchorId }
            .takeIf { it >= 0 }
            ?: targetIndex
        val start = minOf(anchorIndex, targetIndex)
        val end = maxOf(anchorIndex, targetIndex)
        selectedIds = selectedIds + logs.subList(start, end + 1).map { it.id }
        selectionMode = true
        rangeSelectionArmed = false
        rangeAnchorId = logs.getOrNull(targetIndex)?.id
    }

    fun copySelection() {
        val selectedText = logs
            .filter { it.id in selectedIds }
            .joinToString("\n") { it.copyText }
        if (selectedText.isNotEmpty()) {
            clipboardManager.setText(AnnotatedString(selectedText))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
    }

    val contentPadding = PaddingValues(
        start = 16.dp,
        top = if (selectionMode) 76.dp else 16.dp,
        end = 16.dp,
        bottom = 16.dp
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF1E1E1E)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (logs.isEmpty()) {
                Text(
                    text = "暂无日志",
                    color = Color.Gray,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(logs, key = { _, entry -> entry.id }) { index, entry ->
                        SmartLogItem(
                            entry = entry,
                            selected = entry.id in selectedIds,
                            selectionMode = selectionMode,
                            onClick = {
                                when {
                                    rangeSelectionArmed -> selectRangeTo(index)
                                    selectionMode -> toggleItemSelection(entry)
                                }
                            },
                            onLongClick = {
                                selectionMode = true
                                selectedIds = selectedIds + entry.id
                                rangeSelectionArmed = false
                                rangeAnchorId = entry.id
                            }
                        )
                    }
                }
            }

            if (selectionMode) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (rangeSelectionArmed) "点击结束行" else "已选择 ${selectedIds.size} 行",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )

                        FilledTonalIconButton(
                            onClick = {
                                rangeSelectionArmed = !rangeSelectionArmed
                                if (rangeAnchorId == null) {
                                    rangeAnchorId = logs.lastOrNull { it.id in selectedIds }?.id
                                }
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (rangeSelectionArmed) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                contentColor = if (rangeSelectionArmed) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "范围选择")
                        }

                        FilledTonalIconButton(
                            onClick = {
                                val allIds = logs.map { it.id }.toSet()
                                selectedIds = allIds
                                selectionMode = allIds.isNotEmpty()
                                rangeSelectionArmed = false
                                rangeAnchorId = logs.lastOrNull()?.id
                            }
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选")
                        }

                        FilledTonalIconButton(
                            onClick = { copySelection() },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                        }

                        FilledTonalIconButton(onClick = { exitSelectionMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "取消")
                        }
                    }
                }
            } else if (allowClear) {
                FilledTonalIconButton(
                    onClick = onClearLogs,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "清空日志",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmartLogItem(
    entry: SmartLogEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val color = when (entry.type) {
        SmartLogType.COMMAND -> MaterialTheme.colorScheme.primary
        SmartLogType.OUTPUT -> Color(0xFFE0E0E0)
        SmartLogType.INFO -> Color.Gray
        SmartLogType.WARNING -> Color(0xFFFFA000)
        SmartLogType.ERROR -> MaterialTheme.colorScheme.error
    }
    val backgroundColor = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        selectionMode -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
        else -> Color.Transparent
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor
    ) {
        Text(
            text = entry.displayText,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
    }
}

private enum class SmartLogType { COMMAND, OUTPUT, INFO, WARNING, ERROR }

private data class SmartLogEntry(
    val id: Long,
    val type: SmartLogType,
    val displayText: String,
    val copyText: String
)

private fun List<String>.toSmartLogEntries(): List<SmartLogEntry> = mapIndexed { index, raw ->
    val stage = raw.substringAfter(" [", "").substringBefore("] ", "")
    val message = raw.substringAfter("] ", raw)
    val display = buildString {
        if (stage.isNotBlank()) append("[").append(stage).append("] ")
        append(message.smartTruncate())
    }
    SmartLogEntry(
        id = index.toLong(),
        type = classifyLog(stage, raw),
        displayText = display,
        copyText = raw
    )
}

private fun classifyLog(stage: String, raw: String): SmartLogType {
    val lower = raw.lowercase()
    return when {
        stage.equals("Error", ignoreCase = true) ||
            lower.contains("error") || lower.contains("exception") || lower.contains("fatal") ||
            raw.contains("失败") || raw.contains("拒绝") -> SmartLogType.ERROR
        lower.contains("warning") || raw.contains("警告") || raw.contains("不足") ||
            raw.contains("缺少") || lower.contains("missing") || lower.contains("not found") -> SmartLogType.WARNING
        raw.startsWith("$") || raw.startsWith("#") || lower.contains(" command ") -> SmartLogType.COMMAND
        stage.equals("Extracting", ignoreCase = true) -> SmartLogType.OUTPUT
        else -> SmartLogType.INFO
    }
}

private fun String.smartTruncate(limit: Int = 2000): String =
    if (length > limit) take(limit) + "... (truncated ${length - limit} chars)" else this

private fun importStageTitle(stage: ImportStage): String = when (stage) {
    ImportStage.Idle -> "等待导入"
    ImportStage.Preflight -> "准备导入"
    ImportStage.Extracting -> "解包 rootfs"
    ImportStage.Configuring -> "配置 rootfs"
    ImportStage.InstallingProot -> "安装 proot"
    ImportStage.Validating -> "验证环境"
    ImportStage.MaterializingWorkspace -> "创建工作区"
    ImportStage.Activating -> "激活环境"
    ImportStage.Done -> "导入完成"
    ImportStage.Error -> "导入失败"
}
