package dev.idadroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.idadroid.agent.ChatMessage
import dev.idadroid.agent.attachmentSummary
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SearchMessagesSheet(messages: List<ChatMessage>, onDismiss: () -> Unit, onJump: (ChatMessage) -> Unit) { var query by remember { mutableStateOf("") }; val results = remember(messages, query) { if (query.isBlank()) messages.takeLast(30).asReversed() else messages.filter { it.preview().contains(query, ignoreCase = true) } }; ModalBottomSheet(onDismissRequest = onDismiss) { SheetHeader(Icons.Rounded.Search, "搜索消息", "搜索当前 Session"); OutlinedTextField(query, { query = it }, label = { Text("输入关键词") }, leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)); LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), contentPadding = PaddingValues(vertical = 8.dp)) { items(results, key = { it.id }) { msg -> ListItem(headlineContent = { Text(msg.preview().ifBlank { msg.role }, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(msg.role, color = MaterialTheme.colorScheme.onSurfaceVariant) }, leadingContent = { Icon(when (msg.role) { "user" -> Icons.Rounded.Person; "assistant" -> Icons.Rounded.AutoAwesome; "tool" -> Icons.Rounded.Build; else -> Icons.Rounded.Settings }, contentDescription = null) }, modifier = Modifier.clickable { onJump(msg) }) }; if (results.isEmpty()) item { Text("没有匹配消息", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }
@Composable internal fun MessageDialog(text: String, onDismiss: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }, title = { Text("消息内容") }, text = { Box(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { MarkdownishText(text, selectable = true) } }) }
@Composable internal fun InputDialogLite(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) { var value by remember { mutableStateOf(initial) }; AlertDialog(onDismissRequest = onDismiss, confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("确定") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, modifier = Modifier.fillMaxWidth()) }) }
@Composable internal fun ConfirmDialogLite(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) { AlertDialog(onDismissRequest = onDismiss, confirmButton = { Button(onClick = onConfirm) { Text("确定") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }, title = { Text(title) }, text = { Text(text) }) }
private fun ChatMessage.preview(): String = text.ifBlank { toolResult ?: toolName ?: attachmentSummary(attachments) }
internal fun appendComposerText(current: String, insert: String): String { val value = insert.trim(); if (value.isBlank()) return current; val prefix = if (current.isBlank() || current.last().isWhitespace()) "" else " "; return "$current$prefix$value " }
internal fun removeComposerRef(current: String, path: String): String = current.replace("@$path", "").replace("@\"$path\"", "").replace(Regex("\\s+"), " ").trimStart()
internal fun formatTokens(value: Long): String = when { value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0); value >= 1_000 -> "${value / 1_000}k"; else -> value.toString() }
internal fun formatBytes(value: Long): String = when { value >= 1024L * 1024L * 1024L -> "%.1f GiB".format(value / 1024.0 / 1024.0 / 1024.0); value >= 1024L * 1024L -> "%.1f MiB".format(value / 1024.0 / 1024.0); value >= 1024L -> "${value / 1024} KiB"; else -> "$value B" }
