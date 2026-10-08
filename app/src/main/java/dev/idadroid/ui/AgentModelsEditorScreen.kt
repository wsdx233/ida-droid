package dev.idadroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.idadroid.agent.normalizePiProvider
import dev.idadroid.agent.parsePiModels
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private val ModelsEditorJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

private val ApiTypes = listOf("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai")
private val InputTypes = listOf("text", "image")
private val ModelThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh")
private val ThinkingFormats = listOf("reasoning_effort", "openrouter", "deepseek", "together", "zai", "qwen", "qwen-chat-template")
private val CacheControlFormats = listOf("anthropic")

private data class CompatFlag(val key: String, val desc: String)

private val CompatFlags = listOf(
    CompatFlag("supportsStore", "支持 store 字段（OpenAI 存储功能）"),
    CompatFlag("supportsDeveloperRole", "支持 developer 角色（否则降级为 system）"),
    CompatFlag("supportsReasoningEffort", "支持 reasoning_effort 参数"),
    CompatFlag("supportsUsageInStreaming", "支持流式输出中的 include_usage 参数（默认 true）"),
    CompatFlag("requiresToolResultName", "强制工具返回结果中包含 name 字段"),
    CompatFlag("requiresAssistantAfterToolResult", "工具返回后强制插入 assistant 消息再接 user 消息"),
    CompatFlag("requiresThinkingAsText", "强制将 thinking 块转为纯文本"),
    CompatFlag("requiresReasoningContentOnAssistantMessages", "开启推理时在重放消息包含空 reasoning_content"),
    CompatFlag("supportsStrictMode", "工具定义中支持 strict 字段"),
    CompatFlag("supportsLongCacheRetention", "支持长效缓存时间标识（默认 true）"),
    CompatFlag("supportsEagerToolInputStreaming", "Anthropic 格式：接受工具级 eager_input_streaming（默认 true）")
)

private val ProviderKnownKeys = setOf("baseUrl", "api", "apiKey", "authHeader", "compat", "models")
private val CompatKnownKeys = CompatFlags.map { it.key }.toSet() + setOf("maxTokensField", "thinkingFormat", "cacheControlFormat")
private val ModelKnownKeys = setOf("id", "name", "input", "reasoning", "contextWindow", "maxTokens", "thinkingLevelMap", "cost")
private val CostKnownKeys = setOf("input", "output", "cacheRead", "cacheWrite")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentModelsEditorScreen(
    initialJson: String,
    onBack: () -> Unit,
    onApply: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
    saveError: String? = null
) {
    var editor by remember(initialJson) { mutableStateOf(parseModelsEditorState(initialJson)) }
    var codeDialog by remember { mutableStateOf(false) }
    var codeText by remember { mutableStateOf(initialJson.ifBlank { "{\n  \"providers\": {}\n}" }) }
    var pendingDeleteProvider by remember { mutableStateOf<ProviderDraft?>(null) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    fun serializeOrShowError(): String? {
        if (editor.parseFailed) {
            editor.error = "当前 models.json 解析失败。请先打开“代码”修复 JSON；不会覆盖原始内容。"
            return null
        }
        return runCatching { editor.toJsonString() }
            .onFailure { editor.error = it.message ?: "models.json 序列化失败" }
            .getOrNull()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pi Models Editor", fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("可视化编辑 Agent models.json", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, contentDescription = "关闭") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            codeText = if (editor.parseFailed) editor.rawText else (serializeOrShowError() ?: editor.rawText)
                            codeDialog = true
                        }
                    ) {
                        Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("代码")
                    }
                    Button(
                        onClick = {
                            serializeOrShowError()?.let { json ->
                                saving = true
                                scope.launch { try { if (!onApply(json)) editor.error = "配置未保存；请查看保存错误并重试。" } finally { saving = false } }
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (saving) "保存中…" else "保存")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            (saveError ?: editor.error)?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(message, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = { editor.error = null }) { Text("关闭") }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 760.dp
                if (wide) {
                    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProviderPane(
                            editor = editor,
                            wide = true,
                            modifier = Modifier.width(236.dp).fillMaxHeight()
                        )
                        ProviderWorkspace(
                            editor = editor,
                            onDeleteProvider = { pendingDeleteProvider = it },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        ProviderPane(editor = editor, wide = false, modifier = Modifier.fillMaxWidth())
                        ProviderWorkspace(
                            editor = editor,
                            onDeleteProvider = { pendingDeleteProvider = it },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (codeDialog) {
        CodeDialog(
            codeText = codeText,
            onCodeChange = { codeText = it },
            onDismiss = { codeDialog = false },
            onApplyCode = {
                runCatching { parseModelsEditorStateOrThrow(codeText) }
                    .onSuccess { next ->
                        editor = next
                        codeDialog = false
                    }
                    .onFailure { editor.error = "JSON 配置无效；内容未应用（解析详情隐藏以保护凭据）" }
            }
        )
    }

    pendingDeleteProvider?.let { provider ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProvider = null },
            title = { Text("删除 Provider？") },
            text = { Text("确定要删除 [${provider.displayId()}] 以及其中的 ${provider.models.size} 个模型吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        editor.deleteProvider(provider)
                        pendingDeleteProvider = null
                    }
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteProvider = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ProviderPane(editor: ModelsEditorState, wide: Boolean, modifier: Modifier = Modifier) {
    if (wide) {
        ElevatedCard(modifier) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PROVIDERS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                if (editor.providers.isEmpty()) {
                    EmptyHint("暂无 Provider", "点击下方按钮添加。", Modifier.weight(1f).fillMaxWidth())
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(editor.providers.size) { index ->
                            ProviderNavItem(
                                provider = editor.providers[index],
                                selected = index == editor.selectedProviderIndex,
                                onClick = { editor.selectedProviderIndex = index }
                            )
                        }
                    }
                }
                Button(onClick = { editor.addProvider() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("添加 Provider")
                }
            }
        }
    } else {
        Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("PROVIDERS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { editor.addProvider() }) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editor.providers.isEmpty()) Text("暂无 Provider", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    editor.providers.forEachIndexed { index, provider ->
                        ProviderNavItem(
                            provider = provider,
                            selected = index == editor.selectedProviderIndex,
                            compact = true,
                            onClick = { editor.selectedProviderIndex = index }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderNavItem(provider: ProviderDraft, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = Modifier
            .then(if (compact) Modifier.widthIn(min = 132.dp, max = 220.dp) else Modifier.fillMaxWidth())
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .55f) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Storage, contentDescription = null, modifier = Modifier.size(19.dp))
            Column(Modifier.weight(1f, fill = !compact)) {
                Text(provider.displayId(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${provider.models.size} models", maxLines = 1, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProviderWorkspace(editor: ModelsEditorState, onDeleteProvider: (ProviderDraft) -> Unit, modifier: Modifier = Modifier) {
    val provider = editor.selectedProvider
    if (provider == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyHint("请先选择或添加 Provider", "Provider 中包含 API 地址、兼容性开关以及模型列表。", Modifier.padding(22.dp).fillMaxWidth())
        }
        return
    }

    var editingModel by remember(provider) { mutableStateOf<ModelEditTarget?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { ProviderDetailsCard(provider = provider, onDelete = { onDeleteProvider(provider) }) }
        item { ProviderCompatCard(provider.compat) }
        item {
            ModelsSection(
                provider = provider,
                onAdd = { editingModel = ModelEditTarget(null, ModelDraft.empty()) },
                onEdit = { index -> editingModel = ModelEditTarget(index, provider.models[index].deepCopy()) },
                onDelete = { index -> provider.models.removeAt(index) }
            )
        }
        item {
            Text(
                "提示：保存后会写回 .idadroid/pi-agent/models.json。已运行的 Session 需要重启后读取新模型配置。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )
        }
    }

    editingModel?.let { target ->
        ModelEditorDialog(
            draft = target.model,
            onDismiss = { editingModel = null },
            onSave = { saved ->
                if (saved.id.trim().isBlank()) {
                    editor.error = "Model ID 是必填项"
                    return@ModelEditorDialog
                }
                if (target.index == null) provider.models.add(saved) else provider.models[target.index] = saved
                editingModel = null
            }
        )
    }
}

@Composable
private fun ProviderDetailsCard(provider: ProviderDraft, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Provider: ${provider.displayId()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("基础连接设置", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "删除 Provider", tint = MaterialTheme.colorScheme.error) }
            }
            OutlinedTextField(
                value = provider.id,
                onValueChange = { provider.id = it },
                label = { Text("Provider ID") },
                supportingText = { Text("保存时会作为 providers 的键名；不能为空且不能重复。") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = provider.baseUrl,
                onValueChange = { provider.baseUrl = it },
                label = { Text("Base URL") },
                placeholder = { Text("http:// 或 https://...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            MenuSelectField(
                label = "API Type",
                value = provider.api,
                options = ApiTypes,
                clearable = true,
                onChange = { provider.api = it }
            )
            OutlinedTextField(
                value = provider.apiKey,
                onValueChange = { provider.apiKey = it },
                label = { Text("API Key（支持环境变量名 / !command）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SwitchRow(
                title = "自动添加 Authorization: Bearer <apiKey>",
                subtitle = "开启后请求会使用 apiKey 生成 Bearer 认证头。",
                checked = provider.authHeader == true,
                onCheckedChange = { provider.authHeader = it }
            )
        }
    }
}

@Composable
private fun ProviderCompatCard(compat: CompatDraft) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(
        Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Provider Compat（兼容性设置）", fontWeight = FontWeight.Bold)
                    Text("布尔开关、thinking/cache-control 参数格式", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompatFlags.forEach { flag ->
                        SwitchRow(
                            title = flag.key,
                            subtitle = flag.desc,
                            checked = compat.flags[flag.key] == true,
                            onCheckedChange = { compat.flags[flag.key] = it }
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = compat.maxTokensField,
                            onValueChange = { compat.maxTokensField = it },
                            label = { Text("maxTokensField") },
                            supportingText = { Text("使用 max_completion_tokens 或 max_tokens") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        MenuSelectField(
                            label = "thinkingFormat",
                            value = compat.thinkingFormat,
                            options = ThinkingFormats,
                            clearable = true,
                            helper = "各种第三方 API 的专属思考参数格式",
                            onChange = { compat.thinkingFormat = it }
                        )
                        MenuSelectField(
                            label = "cacheControlFormat",
                            value = compat.cacheControlFormat,
                            options = CacheControlFormats,
                            clearable = true,
                            helper = "Anthropic 风格的 cache_control 缓存标记格式",
                            onChange = { compat.cacheControlFormat = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelsSection(
    provider: ProviderDraft,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Models", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("${provider.models.size} 个模型", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Button(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("添加 Model")
                }
            }
            if (provider.models.isEmpty()) {
                EmptyHint("还没有 Model", "点击“添加 Model”配置该 Provider 的模型。", Modifier.fillMaxWidth())
            } else {
                provider.models.forEachIndexed { index, model ->
                    ModelSummaryCard(
                        model = model,
                        onEdit = { onEdit(index) },
                        onDelete = { onDelete(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelSummaryCard(model: ModelDraft, onEdit: () -> Unit, onDelete: () -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .22f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .24f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.name.ifBlank { model.id.ifBlank { "未命名 Model" } }, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(model.id.ifBlank { "Model ID 未填写" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.reasoning == true) SmallPill("Reasoning", MaterialTheme.colorScheme.primary)
                model.contextWindow.takeIf { it.isNotBlank() }?.let { SmallPill("$it ctx", Color(0xFF2563EB)) }
                model.maxTokens.takeIf { it.isNotBlank() }?.let { SmallPill("$it max", Color(0xFF7C3AED)) }
                if (model.input.isNotEmpty()) SmallPill(model.input.joinToString("+"), Color(0xFF059669))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SmallPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = .12f), contentColor = color, border = BorderStroke(1.dp, color.copy(alpha = .25f))) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ModelEditorDialog(draft: ModelDraft, onDismiss: () -> Unit, onSave: (ModelDraft) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(if (draft.id.isBlank()) "添加 Model" else "编辑 Model", fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "关闭") }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = draft.id,
                        onValueChange = { draft.id = it },
                        label = { Text("Model ID（必填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft.name = it },
                        label = { Text("Name（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Input Types", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InputTypes.forEach { input ->
                            FilterChip(
                                selected = input in draft.input,
                                onClick = { draft.toggleInput(input) },
                                label = { Text(input) }
                            )
                        }
                    }
                    SwitchRow(
                        title = "Supports Extended Thinking",
                        subtitle = "开启后可配置 Thinking Level Map。",
                        checked = draft.reasoning == true,
                        onCheckedChange = { draft.reasoning = it }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = draft.contextWindow,
                            onValueChange = { draft.contextWindow = it },
                            label = { Text("Context Window") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.maxTokens,
                            onValueChange = { draft.maxTokens = it },
                            label = { Text("Max Tokens") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    AnimatedVisibility(draft.reasoning == true) {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("Thinking Level Map", fontWeight = FontWeight.Bold)
                                }
                                Text("留空为默认；填 null 为不支持并隐藏；填字符串为提供商对应值。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                ModelThinkingLevels.forEach { level ->
                                    OutlinedTextField(
                                        value = draft.thinkingLevelMap[level].orEmpty(),
                                        onValueChange = { draft.thinkingLevelMap[level] = it },
                                        label = { Text(level) },
                                        placeholder = { Text("字符串或 null") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Cost（per million tokens）", fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CostField("Input", draft.cost.input, { draft.cost.input = it }, Modifier.weight(1f))
                                CostField("Output", draft.cost.output, { draft.cost.output = it }, Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CostField("Cache Read", draft.cost.cacheRead, { draft.cost.cacheRead = it }, Modifier.weight(1f))
                                CostField("Cache Write", draft.cost.cacheWrite, { draft.cost.cacheWrite = it }, Modifier.weight(1f))
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(draft) }) { Text("保存 Model") }
                }
            }
        }
    }
}

@Composable
private fun CostField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun MenuSelectField(
    label: String,
    value: String,
    options: List<String>,
    clearable: Boolean,
    helper: String? = null,
    onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(value.ifBlank { "未设置" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ExpandMore, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (clearable) {
                    DropdownMenuItem(text = { Text("清空") }, onClick = { expanded = false; onChange("") })
                    HorizontalDivider()
                }
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onChange(option) })
                }
            }
        }
        helper?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    )
}

@Composable
private fun EmptyHint(title: String, subtitle: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier, colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CodeDialog(
    codeText: String,
    onCodeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onApplyCode: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp, modifier = Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("~/.pi/agent/models.json 代码", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onPrimary) }
                }
                OutlinedTextField(
                    value = codeText,
                    onValueChange = onCodeChange,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().height(430.dp).padding(12.dp),
                    maxLines = 24
                )
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onApplyCode) { Text("保存并应用配置") }
                }
            }
        }
    }
}

private data class ModelEditTarget(val index: Int?, val model: ModelDraft)

private class ModelsEditorState(
    providers: List<ProviderDraft> = emptyList(),
    val rootExtra: Map<String, JsonElement> = emptyMap(),
    val rawText: String = "{}",
    parseError: String? = null,
    val parseFailed: Boolean = parseError != null
) {
    val providers = mutableStateListOf<ProviderDraft>().apply { addAll(providers) }
    var selectedProviderIndex by mutableStateOf(if (providers.isNotEmpty()) 0 else -1)
    var error by mutableStateOf(parseError)

    val selectedProvider: ProviderDraft?
        get() = providers.getOrNull(selectedProviderIndex)

    fun addProvider() {
        var newId = "new-provider"
        var count = 1
        val existing = providers.map { it.id }.toSet()
        while (newId in existing) newId = "new-provider-${count++}"
        providers.add(ProviderDraft(id = newId, api = "openai-completions"))
        selectedProviderIndex = providers.lastIndex
        error = null
    }

    fun deleteProvider(provider: ProviderDraft) {
        val index = providers.indexOf(provider)
        if (index < 0) return
        providers.removeAt(index)
        selectedProviderIndex = when {
            providers.isEmpty() -> -1
            index <= providers.lastIndex -> index
            else -> providers.lastIndex
        }
        error = null
    }

    fun toJsonString(): String {
        val providerMap = linkedMapOf<String, JsonElement>()
        providers.forEach { provider ->
            val id = provider.id.trim()
            require(id.isNotBlank()) { "Provider ID 不能为空" }
            require(id !in providerMap) { "Provider ID 重复" }
            provider.models.forEach { model -> require(model.id.trim().isNotBlank()) { "Model ID 不能为空" } }
            providerMap[id] = provider.toJsonObject()
        }
        val root = JsonObject(rootExtra + ("providers" to JsonObject(providerMap)))
        parsePiModels(root.toString())
        return ModelsEditorJson.encodeToString(root)
    }
}

private class ProviderDraft(
    id: String,
    baseUrl: String = "",
    api: String = "",
    apiKey: String = "",
    authHeader: Boolean? = null,
    compat: CompatDraft = CompatDraft(),
    models: List<ModelDraft> = emptyList(),
    val extra: Map<String, JsonElement> = emptyMap()
) {
    var id by mutableStateOf(id)
    var baseUrl by mutableStateOf(baseUrl)
    var api by mutableStateOf(api)
    var apiKey by mutableStateOf(apiKey)
    var authHeader by mutableStateOf(authHeader)
    var compat by mutableStateOf(compat)
    val models = mutableStateListOf<ModelDraft>().apply { addAll(models) }

    fun displayId(): String = id.ifBlank { "未命名 Provider" }

    fun toJsonObject(): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        result.putAll(extra)
        putStringIfNotBlank(result, "baseUrl", baseUrl)
        putStringIfNotBlank(result, "api", api)
        putStringIfNotBlank(result, "apiKey", apiKey)
        authHeader?.let { result["authHeader"] = JsonPrimitive(it) } ?: result.remove("authHeader")
        val compatObj = compat.toJsonObject()
        if (compatObj.isNotEmpty()) result["compat"] = compatObj else result.remove("compat")
        if (models.isNotEmpty()) result["models"] = JsonArray(models.map { it.toJsonObject() }) else result.remove("models")
        return JsonObject(result)
    }
}

private class CompatDraft(
    flags: Map<String, Boolean?> = emptyMap(),
    maxTokensField: String = "",
    thinkingFormat: String = "",
    cacheControlFormat: String = "",
    val extra: Map<String, JsonElement> = emptyMap()
) {
    val flags = mutableStateMapOf<String, Boolean?>().apply { putAll(flags) }
    var maxTokensField by mutableStateOf(maxTokensField)
    var thinkingFormat by mutableStateOf(thinkingFormat)
    var cacheControlFormat by mutableStateOf(cacheControlFormat)

    fun toJsonObject(): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        result.putAll(extra)
        CompatFlags.forEach { flag -> flags[flag.key]?.let { result[flag.key] = JsonPrimitive(it) } }
        putStringIfNotBlank(result, "maxTokensField", maxTokensField)
        putStringIfNotBlank(result, "thinkingFormat", thinkingFormat)
        putStringIfNotBlank(result, "cacheControlFormat", cacheControlFormat)
        return JsonObject(result)
    }
}

private class ModelDraft(
    id: String = "",
    name: String = "",
    input: List<String> = emptyList(),
    reasoning: Boolean? = null,
    contextWindow: String = "",
    maxTokens: String = "",
    thinkingLevelMap: Map<String, String> = emptyMap(),
    cost: CostDraft = CostDraft(),
    val extra: Map<String, JsonElement> = emptyMap()
) {
    var id by mutableStateOf(id)
    var name by mutableStateOf(name)
    val input = mutableStateListOf<String>().apply { addAll(input) }
    var reasoning by mutableStateOf(reasoning)
    var contextWindow by mutableStateOf(contextWindow)
    var maxTokens by mutableStateOf(maxTokens)
    val thinkingLevelMap = mutableStateMapOf<String, String>().apply { putAll(thinkingLevelMap) }
    var cost by mutableStateOf(cost)

    fun toggleInput(type: String) {
        if (type in input) input.remove(type) else input.add(type)
    }

    fun deepCopy(): ModelDraft = ModelDraft(
        id = id,
        name = name,
        input = input.toList(),
        reasoning = reasoning,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        thinkingLevelMap = thinkingLevelMap.toMap(),
        cost = cost.deepCopy(),
        extra = extra
    )

    fun toJsonObject(): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        result.putAll(extra)
        putStringIfNotBlank(result, "id", id.trim())
        putStringIfNotBlank(result, "name", name)
        if (input.isNotEmpty()) result["input"] = JsonArray(input.distinct().map { JsonPrimitive(it) }) else result.remove("input")
        reasoning?.let { result["reasoning"] = JsonPrimitive(it) } ?: result.remove("reasoning")
        parseIntegerPrimitive(contextWindow, "contextWindow")?.let { result["contextWindow"] = it } ?: result.remove("contextWindow")
        parseIntegerPrimitive(maxTokens, "maxTokens")?.let { result["maxTokens"] = it } ?: result.remove("maxTokens")
        val thinking = linkedMapOf<String, JsonElement>()
        thinkingLevelMap.forEach { (key, value) ->
            val trimmed = value.trim()
            if (trimmed.isNotBlank()) thinking[key] = if (trimmed == "null") JsonNull else JsonPrimitive(trimmed)
        }
        if (thinking.isNotEmpty()) result["thinkingLevelMap"] = JsonObject(thinking) else result.remove("thinkingLevelMap")
        val costObj = cost.toJsonObject()
        if (costObj.isNotEmpty()) result["cost"] = costObj else result.remove("cost")
        return JsonObject(result)
    }

    companion object {
        fun empty(): ModelDraft = ModelDraft(
            input = listOf("text"),
            reasoning = false,
            contextWindow = "128000",
            maxTokens = "16384",
            cost = CostDraft(input = "0", output = "0", cacheRead = "0", cacheWrite = "0")
        )
    }
}

private class CostDraft(
    input: String = "",
    output: String = "",
    cacheRead: String = "",
    cacheWrite: String = "",
    val extra: Map<String, JsonElement> = emptyMap()
) {
    var input by mutableStateOf(input)
    var output by mutableStateOf(output)
    var cacheRead by mutableStateOf(cacheRead)
    var cacheWrite by mutableStateOf(cacheWrite)

    fun deepCopy(): CostDraft = CostDraft(input, output, cacheRead, cacheWrite, extra)

    fun toJsonObject(): JsonObject {
        val result = linkedMapOf<String, JsonElement>()
        result.putAll(extra)
        parseNumberPrimitive(input, "cost.input")?.let { result["input"] = it }
        parseNumberPrimitive(output, "cost.output")?.let { result["output"] = it }
        parseNumberPrimitive(cacheRead, "cost.cacheRead")?.let { result["cacheRead"] = it }
        parseNumberPrimitive(cacheWrite, "cost.cacheWrite")?.let { result["cacheWrite"] = it }
        return JsonObject(result)
    }
}

private fun parseModelsEditorState(text: String): ModelsEditorState = runCatching { parseModelsEditorStateOrThrow(text) }
    .getOrElse { ModelsEditorState(rawText = text, parseError = "JSON 配置无效；原始内容保留（解析详情隐藏以保护凭据）") }

private fun parseModelsEditorStateOrThrow(text: String): ModelsEditorState {
    val root = parsePiModels(text)
    val providersObj = root["providers"] as? JsonObject ?: JsonObject(emptyMap())
    val providers = providersObj.entries.map { (id, element) -> parseProvider(id, normalizePiProvider(id, element as JsonObject)) }
    return ModelsEditorState(
        providers = providers,
        rootExtra = root.extraWithout(setOf("providers")),
        rawText = text,
        parseError = null
    )
}

private fun parseProvider(id: String, obj: JsonObject): ProviderDraft = ProviderDraft(
    id = id,
    baseUrl = obj.stringValue("baseUrl"),
    api = obj.stringValue("api"),
    apiKey = obj.stringValue("apiKey"),
    authHeader = obj.booleanValue("authHeader"),
    compat = parseCompat(obj["compat"] as? JsonObject),
    models = (obj["models"] as? JsonArray).orEmpty().mapNotNull { element ->
        (element as? JsonObject)?.let(::parseModel)
    },
    extra = obj.extraWithout(ProviderKnownKeys)
)

private fun parseCompat(obj: JsonObject?): CompatDraft {
    if (obj == null) return CompatDraft()
    CompatFlags.forEach { flag -> obj[flag.key]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "compat.${flag.key} 必须是布尔值" } } }
    listOf("maxTokensField", "thinkingFormat", "cacheControlFormat").forEach { field -> obj[field]?.let { require(it is JsonPrimitive && it.isString) { "compat.$field 必须是字符串" } } }
    val flags = CompatFlags.associate { flag -> flag.key to obj.booleanValue(flag.key) }
    return CompatDraft(
        flags = flags,
        maxTokensField = obj.stringValue("maxTokensField"),
        thinkingFormat = obj.stringValue("thinkingFormat"),
        cacheControlFormat = obj.stringValue("cacheControlFormat"),
        extra = obj.extraWithout(CompatKnownKeys)
    )
}

private fun parseModel(obj: JsonObject): ModelDraft = ModelDraft(
    id = obj.stringValue("id"),
    name = obj.stringValue("name"),
    input = parseInput(obj["input"]),
    reasoning = obj.booleanValue("reasoning"),
    contextWindow = obj.numberText("contextWindow"),
    maxTokens = obj.numberText("maxTokens"),
    thinkingLevelMap = parseThinkingLevelMap(obj["thinkingLevelMap"] as? JsonObject),
    cost = parseCost(obj["cost"] as? JsonObject),
    extra = obj.extraWithout(ModelKnownKeys)
)

private fun parseCost(obj: JsonObject?): CostDraft {
    if (obj == null) return CostDraft()
    return CostDraft(
        input = obj.numberText("input"),
        output = obj.numberText("output"),
        cacheRead = obj.numberText("cacheRead"),
        cacheWrite = obj.numberText("cacheWrite"),
        extra = obj.extraWithout(CostKnownKeys)
    )
}

private fun parseInput(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it.isNotBlank() }
    is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
    else -> emptyList()
}

private fun parseThinkingLevelMap(obj: JsonObject?): Map<String, String> {
    if (obj == null) return emptyMap()
    return obj.mapValues { (_, value) ->
        if (value == JsonNull) "null" else (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
    }
}

private fun JsonObject.stringValue(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.numberText(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.extraWithout(keys: Set<String>): Map<String, JsonElement> {
    val out = linkedMapOf<String, JsonElement>()
    entries.forEach { (key, value) -> if (key !in keys) out[key] = value }
    return out
}

private fun putStringIfNotBlank(map: MutableMap<String, JsonElement>, key: String, value: String) {
    if (value.isNotBlank()) map[key] = JsonPrimitive(value) else map.remove(key)
}

private fun parseIntegerPrimitive(raw: String, field: String): JsonPrimitive? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val value = trimmed.toLongOrNull() ?: throw IllegalArgumentException("$field 必须是整数")
    return JsonPrimitive(value)
}

private fun parseNumberPrimitive(raw: String, field: String): JsonPrimitive? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    trimmed.toLongOrNull()?.let { return JsonPrimitive(it) }
    val value = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("$field 必须是有限数字")
    return JsonPrimitive(value)
}
