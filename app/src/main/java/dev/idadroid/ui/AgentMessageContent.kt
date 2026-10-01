package dev.idadroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.ChatAttachment
import dev.idadroid.agent.ChatMessage
import kotlin.math.max

@Composable
internal fun MessageBubble(message: ChatMessage, autoOpenProgress: Boolean, isLatestMessage: Boolean, streaming: Boolean, onShowDialog: () -> Unit, onSaveToWorkspace: () -> Unit = {}) {
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
            if (message.text.isNotBlank()) AssistantActions(message.text, onShowDialog, onSaveToWorkspace)
        }
        "tool" -> ToolMessageCard(message, autoOpen = autoOpenProgress, autoCollapse = !isLatestMessage)
        "system" -> SystemMessageCard(message.text)
        else -> SelectionContainer { AssistChip(onClick = {}, leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) }, label = { Text(message.text) }) }
    }
}

private fun runtimeErrorText(error: String, stderrTail: String): String {
    val primary = if (error.startsWith("Agent 报错：")) error else "Agent 报错：$error"
    val stderr = stderrTail.trim().takeIf { it.isNotBlank() && !primary.contains(it) }
    return if (stderr == null) primary else "$primary\n\nstderr:\n$stderr"
}

@Composable
internal fun SystemMessageCard(text: String) {
    val isError = text.contains("报错") || text.contains("失败") || text.contains("error", ignoreCase = true)
    val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        Modifier.fillMaxWidth(),
        color = container.copy(alpha = .82f),
        contentColor = content,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, content.copy(alpha = .24f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
        }
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
private fun AssistantActions(text: String, onShowDialog: () -> Unit, onSaveToWorkspace: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.ContentCopy, contentDescription = "复制", tint = color) }
        IconButton(onClick = onSaveToWorkspace, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.SaveAlt, contentDescription = "保存到工作区", tint = color) }
        IconButton(onClick = onShowDialog, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.OpenInFull, contentDescription = "查看完整内容", tint = color) }
    }
}
@Composable private fun ExpandableBlock(title: String, body: String, autoOpen: Boolean, autoCollapse: Boolean, stateKey: String) { var open by rememberSaveable(stateKey) { mutableStateOf(autoOpen) }; var openedByAuto by rememberSaveable(stateKey) { mutableStateOf(autoOpen) }; LaunchedEffect(autoOpen, autoCollapse) { if (autoOpen) { open = true; openedByAuto = true } else if (autoCollapse && openedByAuto) { open = false; openedByAuto = false } }; OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .35f))) { Column { Row(Modifier.fillMaxWidth().clickable { open = !open; openedByAuto = false }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null); Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("${body.length} chars", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }; if (open) Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { MarkdownishText(body, selectable = true) } } } }
