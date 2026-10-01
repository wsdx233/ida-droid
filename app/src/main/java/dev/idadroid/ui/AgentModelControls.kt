package dev.idadroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.AgentUiState
import dev.idadroid.agent.PiAgentManager
import dev.idadroid.agent.PiConfigSnapshot
import dev.idadroid.agent.PiModel
import dev.idadroid.agent.parseAgentModelCatalog
import dev.idadroid.agent.providerNameOrNull
import kotlin.math.max

internal val ThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatOptionSheets(
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
    onOpenConfig: () -> Unit,
    onSend: (String?) -> Unit
) {
    if (showThinkingMenu) ModalBottomSheet(onDismissRequest = { onShowThinkingMenu(false) }) { SheetHeader(Icons.Rounded.Lightbulb, "思考强度", "选择推理强度"); ThinkingLevels.forEach { level -> ListItem(headlineContent = { Text(level) }, supportingContent = { Text(thinkingDescription(level)) }, leadingContent = { if (state.activeSession?.thinkingLevel == level) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) else Icon(Icons.Rounded.Settings, contentDescription = null) }, modifier = Modifier.clickable { onShowThinkingMenu(false); manager.chooseThinking(level) }) }; Spacer(Modifier.height(18.dp)) }
    if (showModelMenu) {
        var search by remember { mutableStateOf("") }
        var manualProvider by remember { mutableStateOf(state.activeSession?.provider.orEmpty()) }
        var manualModel by remember { mutableStateOf(state.activeSession?.model.orEmpty()) }
        val configuredModels = remember(state.sessionModels, state.piConfig.modelCatalog) { mergeUiModels(state.sessionModels, state.piConfig.modelCatalog.models.map { it.toPiModel() }) }
        val models = remember(configuredModels, search) { configuredModels.filter { "${it.providerNameOrNull().orEmpty()} ${it.id} ${it.name.orEmpty()}".contains(search, ignoreCase = true) }.take(160) }
        ModalBottomSheet(onDismissRequest = { onShowModelMenu(false) }) {
            SheetHeader(Icons.Rounded.AutoAwesome, "模型", "从 models.json 选择/补全 provider 与 model")
            OutlinedTextField(search, { search = it }, leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }, label = { Text("搜索 provider / model") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp))
            if (state.modelLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(18.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(models, key = { "${it.providerNameOrNull()}/${it.id}" }) { model -> val selected = state.activeSession?.model == model.id && state.activeSession?.provider == model.providerNameOrNull(); ModelPickerRow(model = model, selected = selected, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), onClick = { onShowModelMenu(false); manager.setSessionModel(model) }) }
                if (!state.modelLoading && models.isEmpty()) item { Text("没有可显示的模型，请先配置 models.json。", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider()
            ModelManualPickerSection(
                models = configuredModels,
                provider = manualProvider,
                model = manualModel,
                onProviderChange = { manualProvider = it; if (manualProvider.isBlank()) manualModel = "" },
                onModelChange = { manualModel = it },
                onOpenConfig = {
                    onShowModelMenu(false)
                    onOpenConfig()
                },
                onApply = {
                    onShowModelMenu(false)
                    manager.setSessionModelManual(manualProvider, manualModel)
                }
            )
        }
    }
    if (showCompactMenu) ModalBottomSheet(onDismissRequest = { onShowCompactMenu(false) }) { SheetHeader(Icons.Rounded.Tune, "Compact / 更多", "上下文与附加操作"); ListItem(headlineContent = { Text("自动 Compact") }, supportingContent = { Text(if (autoCompact) "已开启" else "点击开启") }, leadingContent = { Icon(Icons.Rounded.Settings, contentDescription = null, tint = if (autoCompact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }, modifier = Modifier.clickable { onShowCompactMenu(false); manager.setAutoCompaction(true) }); ListItem(headlineContent = { Text("手动 Compact") }, supportingContent = { Text("仅手动触发") }, leadingContent = { Icon(if (!autoCompact) Icons.Rounded.CheckCircle else Icons.Rounded.Settings, contentDescription = null) }, modifier = Modifier.clickable { onShowCompactMenu(false); manager.setAutoCompaction(false) }); ListItem(headlineContent = { Text("立即执行 Compact") }, supportingContent = { Text(if (text.isBlank()) "压缩当前上下文" else "使用输入内容作为要求") }, leadingContent = { Icon(Icons.Rounded.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { onShowCompactMenu(false); manager.compact(text.trim().ifBlank { null }); onTextChange("") }); Spacer(Modifier.height(18.dp)) }
    if (showSendModeMenu) ModalBottomSheet(onDismissRequest = { onShowSendModeMenu(false) }) { SheetHeader(Icons.AutoMirrored.Rounded.Send, "发送方式", "选择队列策略"); ListItem(headlineContent = { Text("立即发送") }, supportingContent = { Text("中断当前 turn") }, leadingContent = { Icon(Icons.Rounded.FlashOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { onShowSendModeMenu(false); onSend(null) }); ListItem(headlineContent = { Text("Steer 队列") }, supportingContent = { Text("注入当前 turn") }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null) }, modifier = Modifier.clickable { onShowSendModeMenu(false); onSend("steer") }); ListItem(headlineContent = { Text("Follow-up 队列") }, supportingContent = { Text("完成后发送") }, leadingContent = { Icon(Icons.Rounded.Queue, contentDescription = null) }, modifier = Modifier.clickable { onShowSendModeMenu(false); onSend("followUp") }); Spacer(Modifier.height(18.dp)) }
}

@Composable
internal fun SheetHeader(icon: ImageVector, title: String, subtitle: String) {
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

@Composable
private fun ModelManualPickerSection(
    models: List<PiModel>,
    provider: String,
    model: String,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onOpenConfig: () -> Unit,
    onApply: () -> Unit
) {
    val providers = remember(models) { models.mapNotNull { it.providerNameOrNull() }.distinct().sorted() }
    val providerModels = remember(models, provider) { models.filter { it.providerNameOrNull() == provider }.sortedBy { it.id } }
    val modelSuggestions = remember(models, provider) { if (provider.isNotBlank()) providerModels else models.sortedWith(compareBy<PiModel> { it.providerNameOrNull().orEmpty() }.thenBy { it.id }) }
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("手动设置 / 补全", fontWeight = FontWeight.Bold)
        if (models.isEmpty()) {
            Text("models.json 中还没有可用 Provider/Model。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            OutlinedButton(onClick = onOpenConfig, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("打开 models.json 编辑器")
            }
        }
        SuggestionTextField(
            label = "Provider",
            value = provider,
            suggestions = providers,
            onValueChange = onProviderChange
        )
        SuggestionTextField(
            label = "Model",
            value = model,
            suggestions = modelSuggestions.map { it.id }.distinct(),
            onValueChange = onModelChange,
            suggestionLabel = { id ->
                modelSuggestions.firstOrNull { it.id == id }?.let { candidate ->
                    listOfNotNull(candidate.providerNameOrNull(), candidate.name).joinToString(" · ").ifBlank { id }
                } ?: id
            }
        )
        Button(onClick = onApply, enabled = provider.isNotBlank() && model.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("应用模型") }
    }
}

@Composable
private fun SuggestionTextField(
    label: String,
    value: String,
    suggestions: List<String>,
    onValueChange: (String) -> Unit,
    suggestionLabel: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(value, suggestions) {
        val query = value.trim()
        suggestions.filter { query.isBlank() || it.contains(query, ignoreCase = true) || suggestionLabel(it).contains(query, ignoreCase = true) }.take(24)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { expanded = true }, enabled = suggestions.isNotEmpty()) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "选择 $label")
                }
            }
        )
        if (expanded && filtered.isNotEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    filtered.forEach { suggestion ->
                        ListItem(
                            headlineContent = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { suggestionLabel(suggestion).takeIf { it != suggestion }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                            modifier = Modifier.clickable { onValueChange(suggestion); expanded = false }
                        )
                    }
                }
            }
        }
    }
}

private fun mergeUiModels(primary: List<PiModel>, fallback: List<PiModel>): List<PiModel> {
    val seen = linkedSetOf<String>()
    return (primary + fallback).filter { model ->
        val key = "${model.providerNameOrNull().orEmpty()}/${model.id}"
        seen.add(key)
    }
}

@Composable private fun TinyMetaChip(text: String, selected: Boolean = false) { val colors = MaterialTheme.colorScheme; Surface(shape = RoundedCornerShape(999.dp), color = if (selected) colors.primary.copy(alpha = .12f) else colors.surfaceContainerHighest, contentColor = if (selected) colors.primary else colors.onSurfaceVariant, border = BorderStroke(1.dp, if (selected) colors.primary.copy(alpha = .28f) else colors.outlineVariant)) { Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
private fun thinkingDescription(level: String): String = when (level) {
    "off" -> "关闭扩展思考 — 直接回答，速度最快"
    "minimal" -> "极少推理 — 仅快速判断，适合简单任务"
    "low" -> "低强度思考 — 基本分析，适合常规逆向任务"
    "medium" -> "默认平衡 — 推理深度与速度兼顾，推荐大多数场景"
    "high" -> "高强度思考 — 深入分析，适合复杂逻辑和加壳混淆"
    "xhigh" -> "极致推理 — 最大深度推理，适合最难的问题和大型二进制"
    else -> ""
}
@Composable
internal fun PiSettingsTab(
    state: AgentUiState,
    manager: PiAgentManager,
    initialShowModelsEditor: Boolean = false,
    onOpenFullScreenEditor: (() -> Unit)? = null,
    onCloseEditor: (() -> Unit)? = null
) {
    var snapshot by remember { mutableStateOf(manager.getPiConfigSnapshot()) }
    var showModelsEditor by remember(initialShowModelsEditor) { mutableStateOf(initialShowModelsEditor) }
    LaunchedEffect(state.piConfig) { if (!showModelsEditor) snapshot = state.piConfig }

    if (showModelsEditor) {
        AgentModelsEditorScreen(
            initialJson = snapshot.modelsText,
            onBack = {
                showModelsEditor = false
                onCloseEditor?.invoke()
            },
            onApply = { modelsJson ->
                val next = snapshot.copy(modelsText = modelsJson, modelCatalog = parseAgentModelCatalog(modelsJson))
                snapshot = next
                manager.savePiConfig(next)
                showModelsEditor = false
                onCloseEditor?.invoke()
            }
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text("Pi config", fontWeight = FontWeight.Bold); Text("PI_CODING_AGENT_DIR: ${snapshot.materializedDir}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        item { state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
        // ── 可视化 AI 配置编辑器（取代旧的手动 JSON 编辑） ──
        item { AiConfigEditor(snapshot = snapshot, onSnapshotChange = { snapshot = it }) }
        item {
            SettingsCard("模型与运行参数") {
                DefaultModelPicker(
                    snapshot = snapshot,
                    onSnapshotChange = { snapshot = it }
                )
                DropdownField("Thinking", snapshot.defaultThinkingLevel, ThinkingLevels) { snapshot = snapshot.copy(defaultThinkingLevel = it) }
                OutlinedTextField(snapshot.enabledModels, { snapshot = snapshot.copy(enabledModels = it) }, label = { Text("enabledModels") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        // 旧的 settings.json / extraArgs / APPEND_SYSTEM.md 原始 JSON 编辑器已移除
        // 这些功能已被 AiConfigEditor 可视化编辑器覆盖
        item { SettingsCard("系统提示词追加") { CodeTextField("APPEND_SYSTEM.md", snapshot.appendSystem) { snapshot = snapshot.copy(appendSystem = it) } } }
        item { OutlinedButton(onClick = { onOpenFullScreenEditor?.invoke() ?: run { showModelsEditor = true } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Settings, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("编辑 models.json") } }
        item { Button(onClick = { manager.savePiConfig(snapshot) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Save, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("保存 Pi 配置") } }
    }
}
@Composable
private fun DefaultModelPicker(snapshot: PiConfigSnapshot, onSnapshotChange: (PiConfigSnapshot) -> Unit) {
    val models = snapshot.modelCatalog.models
    val providers = remember(models) { models.map { it.provider }.distinct().sorted() }
    val providerModels = remember(models, snapshot.defaultProvider) { models.filter { it.provider == snapshot.defaultProvider } }
    SuggestionTextField(
        label = "默认 Provider",
        value = snapshot.defaultProvider,
        suggestions = providers,
        onValueChange = { provider ->
            val firstModel = models.firstOrNull { it.provider == provider }?.id.orEmpty()
            onSnapshotChange(snapshot.copy(defaultProvider = provider, defaultModel = if (provider == snapshot.defaultProvider) snapshot.defaultModel else firstModel))
        }
    )
    SuggestionTextField(
        label = "默认 Model",
        value = snapshot.defaultModel,
        suggestions = (if (snapshot.defaultProvider.isNotBlank()) providerModels else models).map { it.id }.distinct(),
        onValueChange = { modelId ->
            val provider = snapshot.defaultProvider.ifBlank { models.firstOrNull { it.id == modelId }?.provider.orEmpty() }
            onSnapshotChange(snapshot.copy(defaultProvider = provider, defaultModel = modelId))
        },
        suggestionLabel = { modelId ->
            val candidate = (if (snapshot.defaultProvider.isNotBlank()) providerModels else models).firstOrNull { it.id == modelId }
            candidate?.let { listOfNotNull(it.provider, it.name).joinToString(" · ").ifBlank { modelId } } ?: modelId
        }
    )
}

@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, fontWeight = FontWeight.Bold); content() } } }
@Composable private fun CodeTextField(label: String, value: String, onValue: (String) -> Unit) { OutlinedTextField(value, onValue, label = { Text(label) }, modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), maxLines = 12) }
@Composable private fun DropdownField(label: String, value: String, options: List<String>, onChange: (String) -> Unit) { var expanded by remember { mutableStateOf(false) }; Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：$value") }; DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { options.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { expanded = false; onChange(it) }) } } } }
