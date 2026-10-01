package dev.idadroid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.idadroid.agent.AgentUiState
import dev.idadroid.agent.PiAgentManager

import kotlinx.coroutines.launch

@Composable
internal fun AgentComposer(
    draft: AgentDraft,
    state: AgentUiState,
    manager: PiAgentManager,
    preparing: Boolean,
    onText: (String) -> Unit,
    onAttach: () -> Unit,
    onRemove: (PendingAgentAttachment) -> Unit,
    onSend: suspend (String?) -> Unit,
    onConfigure: () -> Unit
) {
    val sendScope = rememberCoroutineScope()
    var thinkingMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var compactMenu by remember { mutableStateOf(false) }
    var sendModeMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val hasContent = draft.text.isNotBlank() || draft.attachments.isNotEmpty()
    val busy = state.isWorking || state.status == "starting"
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.activeQueue.steering.isNotEmpty() || state.activeQueue.followUp.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("队列 · ${state.activeQueue.steering.size} steer / ${state.activeQueue.followUp.size} follow-up", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { manager.clearQueue() }) { Text("清空队列") }
                }
            }
            if (draft.attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                draft.attachments.forEach { item ->
                    InputChip(selected = false, onClick = { onRemove(item) }, label = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
                        trailingIcon = { Icon(Icons.Rounded.Close, "移除附件 ${item.name}") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            OutlinedTextField(
                value = draft.text, onValueChange = onText,
                placeholder = { Text(if (busy) "补充指令或下一步任务…" else "描述任务，或附加文件…") },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "消息草稿" },
                minLines = 1, maxLines = 5, shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerLow)
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Add, "添加附件") }
                TextButton(onClick = { modelMenu = true; manager.loadSessionModels() }, modifier = Modifier.weight(1f)) {
                    Text(state.activeSession?.model ?: "选择模型", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(16.dp))
                }
                Box {
                    IconButton(onClick = { more = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Tune, "思考与上下文选项") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("思考 · ${state.activeSession?.thinkingLevel ?: "medium"}") }, onClick = { more = false; thinkingMenu = true })
                        DropdownMenuItem(text = { Text("上下文压缩") }, onClick = { more = false; compactMenu = true })
                        DropdownMenuItem(text = { Text("启用深度索引工具链") }, onClick = { more = false; manager.enableDeepIndexMode() })
                        DropdownMenuItem(text = { Text("关闭深度索引工具链") }, onClick = { more = false; manager.disableDeepIndexMode() })
                        DropdownMenuItem(text = { Text("Provider / Model 配置") }, onClick = { more = false; onConfigure() })
                    }
                }
                if (busy) {
                    if (hasContent && state.turnActive && !preparing) IconButton(onClick = { sendModeMenu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Queue, "发送补充指令") }
                    FilledIconButton(onClick = { manager.abort() }, modifier = Modifier.size(48.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Icon(Icons.Rounded.Stop, "停止当前任务") }
                } else {
                    FilledIconButton(onClick = { if (!state.canUseActiveSession) onConfigure() else sendScope.launch { onSend(null) } }, enabled = hasContent && !preparing, modifier = Modifier.size(48.dp)) {
                        if (preparing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.ArrowUpward, "发送消息")
                    }
                }
            }
        }
    }
    ChatOptionSheets(thinkingMenu, { thinkingMenu = it }, modelMenu, { modelMenu = it }, compactMenu, { compactMenu = it }, sendModeMenu, { sendModeMenu = it }, draft.text, onText, state.activeSession?.autoCompactionEnabled != false, state, manager, onConfigure) { mode -> sendScope.launch { onSend(mode) } }
}
