package dev.idadroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.idadroid.agent.ConfigExport
import dev.idadroid.agent.customPiModel
import dev.idadroid.agent.defaultProviderApi
import dev.idadroid.agent.normalizePiProvider
import dev.idadroid.agent.parsePiModels
import dev.idadroid.agent.PiConfigSnapshot
import dev.idadroid.agent.TestResult
import dev.idadroid.agent.parseAgentModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI

// ─── Provider catalog ──────────────────────────────────────────────────────────

private data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val envKey: String,
    val color: Color,
    val description: String
)

private val PROVIDER_PRESETS = listOf(
    ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "OPENAI_API_KEY", Color(0xFF10A37F), "GPT-4o / o1 / o3 系列"),
    ProviderPreset("anthropic", "Anthropic Claude", "https://api.anthropic.com", "ANTHROPIC_API_KEY", Color(0xFFD97757), "Claude 3.5 / 3.7 / 4 系列"),
    ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com", "DEEPSEEK_API_KEY", Color(0xFF4D6BFE), "DeepSeek-V3 / R1 系列"),
    ProviderPreset("google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "GOOGLE_API_KEY", Color(0xFF4285F4), "Gemini 2.0 / 2.5 系列"),
    ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", Color(0xFF8B5CF6), "聚合 300+ 模型"),
    ProviderPreset("moonshot", "Moonshot KIMI", "https://api.moonshot.cn/v1", "MOONSHOT_API_KEY", Color(0xFF1A1A2E), "Kimi K1.5 / K2 系列"),
    ProviderPreset("dashscope", "阿里通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY", Color(0xFF615CED), "Qwen 系列"),
    ProviderPreset("volcengine", "火山引擎豆包", "https://ark.cn-beijing.volces.com/api/v3", "ARK_API_KEY", Color(0xFF1664FF), "Doubao 系列"),
    ProviderPreset("baidu", "百度千帆", "https://qianfan.baidubce.com/v2", "BAIDU_API_KEY", Color(0xFF2932E1), "ERNIE 系列"),
    ProviderPreset("hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1", "HUNYUAN_API_KEY", Color(0xFF0053E0), "Hunyuan 系列"),
    ProviderPreset("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1", "SILICONFLOW_API_KEY", Color(0xFF00B4A6), "硅基流动聚合平台"),
    ProviderPreset("mistral", "Mistral AI", "https://api.mistral.ai/v1", "MISTRAL_API_KEY", Color(0xFFFF5200), "Mistral / Codestral 系列"),
    ProviderPreset("groq", "Groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY", Color(0xFFF55036), "超低延迟推理"),
    ProviderPreset("xai", "xAI Grok", "https://api.x.ai/v1", "XAI_API_KEY", Color(0xFF000000), "Grok 系列"),
    ProviderPreset("together", "Together AI", "https://api.together.xyz/v1", "TOGETHER_API_KEY", Color(0xFF0F6FFF), "开源模型聚合"),
    ProviderPreset("openai-generic", "自定义 (OpenAI 兼容)", "", "OPENAI_API_KEY", Color(0xFF6B7280), "手动填写 Base URL，使用 OpenAI 兼容协议")
)

private val MODEL_SUGGESTIONS: Map<String, List<String>> = mapOf(
    "openai" to listOf("gpt-4o", "gpt-4o-mini", "o1", "o1-mini", "o3", "o3-mini", "gpt-4.1", "gpt-4.1-mini"),
    "anthropic" to listOf("claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-3-7-sonnet-20250219", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022"),
    "deepseek" to listOf("deepseek-chat", "deepseek-reasoner"),
    "google" to listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash", "gemini-2.0-flash-lite"),
    "openrouter" to listOf("anthropic/claude-sonnet-4", "openai/gpt-4o", "google/gemini-2.5-flash", "deepseek/deepseek-chat", "x-ai/grok-3"),
    "moonshot" to listOf("moonshot-v1-128k", "moonshot-v1-32k", "moonshot-v1-8k", "kimi-thinking-preview"),
    "dashscope" to listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen-long", "qwen3-235b-a22b"),
    "volcengine" to listOf("doubao-1-5-pro-256k", "doubao-1-5-pro-32k", "doubao-1-5-lite-32k", "deepseek-r1-250120"),
    "baidu" to listOf("ernie-4.0-8k-latest", "ernie-4.0-turbo-8k", "ernie-speed-128k", "deepseek-r1"),
    "hunyuan" to listOf("hunyuan-turbos", "hunyuan-standard", "hunyuan-lite", "hunyuan-large"),
    "siliconflow" to listOf("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "Qwen/Qwen2.5-72B-Instruct", "meta-llama/Llama-3.3-70B-Instruct"),
    "mistral" to listOf("mistral-large-latest", "mistral-small-latest", "codestral-latest", "open-mixtral-8x22b"),
    "groq" to listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "deepseek-r1-distill-llama-70b", "mixtral-8x7b-32768"),
    "xai" to listOf("grok-3", "grok-3-mini", "grok-2-vision"),
    "together" to listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Qwen/Qwen2.5-72B-Instruct-Turbo", "deepseek-ai/DeepSeek-V3")
)

private fun presetById(id: String): ProviderPreset? = PROVIDER_PRESETS.firstOrNull { it.id == id }

// ─── Data classes for JSON parsing ─────────────────────────────────────────────

private data class ModelConfig(val id: String, val provider: String, val name: String, val raw: JsonObject = customPiModel(id))
private data class ProviderConfig(
    val id: String, val displayName: String, val baseUrl: String,
    val envKey: String, val color: Color, val apiKey: String, val models: List<ModelConfig>,
    val api: String = defaultProviderApi(id), val raw: JsonObject = JsonObject(emptyMap())
)
private data class ParsedConfig(
    val providers: List<ProviderConfig>, val models: List<ModelConfig>,
    val env: Map<String, String>, val parseError: String?, val root: JsonObject = JsonObject(emptyMap())
)

private fun parseConfig(modelsText: String, envText: String): ParsedConfig = try {
    val root = parsePiModels(modelsText, validateValues = false)
    val env = Json.parseToJsonElement(envText.ifBlank { "{}" }) as? JsonObject ?: error("环境配置必须是 JSON object")
    val values = env.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("环境变量值必须是字符串")
    }
    val providers = (root["providers"] as? JsonObject).orEmpty().map { (id, element) ->
        val raw = normalizePiProvider(id, element as JsonObject)
        val key = (raw["apiKey"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val models = (raw["models"] as? JsonArray).orEmpty().map { item ->
            val obj = item as JsonObject
            val mid = obj["id"]!!.jsonPrimitive.content
            ModelConfig(mid, id, (obj["name"] as? JsonPrimitive)?.contentOrNull ?: mid, obj)
        }
        val preset = presetById(id)
        ProviderConfig(id, (raw["displayName"] as? JsonPrimitive)?.contentOrNull ?: preset?.displayName ?: id,
            (raw["baseUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            key.takeIf { it in values }.orEmpty(), preset?.color ?: Color(0xFF6B7280), values[key] ?: key,
            models, (raw["api"] as? JsonPrimitive)?.contentOrNull ?: defaultProviderApi(id), raw)
    }
    ParsedConfig(providers, providers.flatMap { it.models }, values, null, root)
} catch (_: Exception) {
    ParsedConfig(emptyList(), emptyList(), emptyMap(), "无效配置；原始内容保留。请修复原始 JSON。")
}

private fun buildModelsJson(providers: List<ProviderConfig>, root: JsonObject): String {
    val providerMap = providers.associate { p ->
        p.id to JsonObject(p.raw.toMutableMap().apply {
            remove("baseURL"); remove("envKey")
            put("displayName", JsonPrimitive(p.displayName))
            if (p.baseUrl.isNotBlank()) put("baseUrl", JsonPrimitive(p.baseUrl)) else remove("baseUrl")
            put("api", JsonPrimitive(p.api))
            if (p.envKey.isNotBlank()) put("apiKey", JsonPrimitive(p.envKey))
            else if (p.apiKey.isNotBlank()) put("apiKey", JsonPrimitive(p.apiKey)) else remove("apiKey")
            if (p.models.isNotEmpty()) put("models", JsonArray(p.models.map { it.raw }))
        })
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(root + ("providers" to JsonObject(providerMap))))
}

private fun rebuildEnv(env: Map<String, String>, providers: List<ProviderConfig>): String {
    val merged = env.toMutableMap()
    providers.forEach { p -> if (p.envKey.isNotBlank()) merged[p.envKey] = p.apiKey }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(merged.mapValues { JsonPrimitive(it.value) }))
}

// ─── Main UI: Operit-style simple form ────────────────────────────────────────
// Provider 下拉选择 → Base URL (自动填充) → API Key → Model Name
// 四行搞定，不做一堆卡片

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfigEditor(
    snapshot: PiConfigSnapshot,
    onSnapshotChange: (PiConfigSnapshot) -> Unit
) {
    val parsed = remember(snapshot.modelsText, snapshot.envText) { parseConfig(snapshot.modelsText, snapshot.envText) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var showExportDialog by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var testingProvider by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showProviderPicker by remember { mutableStateOf(false) }
    var showCreateProvider by remember { mutableStateOf(false) }

    // 当前选中的 provider — 始终是 ProviderConfig 类型
    val currentProvider = remember(parsed.providers, snapshot.defaultProvider) {
        parsed.providers.firstOrNull { it.id == snapshot.defaultProvider }
            ?: parsed.providers.firstOrNull()
            ?: run {
                val p = PROVIDER_PRESETS.first()
                ProviderConfig(id = p.id, displayName = p.displayName, baseUrl = p.baseUrl, envKey = "", color = p.color, apiKey = "", models = emptyList())
            }
    }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestOnChange by rememberUpdatedState(onSnapshotChange)
    val baseUrlInput = currentProvider.baseUrl
    val apiKeyInput = currentProvider.apiKey
    val modelInput = snapshot.defaultModel

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── 状态提示 ──
        parsed.parseError?.let { err ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text("配置解析失败：$err", color = MaterialTheme.colorScheme.onError, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }

        // ── Provider 选择 ──
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AI 配置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                // Provider 选择 (点击打开搜索弹窗)
                OutlinedButton(onClick = { showProviderPicker = true }, enabled = parsed.parseError == null, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(currentProvider.color), contentAlignment = Alignment.Center) { Text(currentProvider.displayName.take(1), color = Color.White, fontSize = 12.sp) }
                    Spacer(Modifier.width(8.dp))
                    Text("${currentProvider.displayName} · ${currentProvider.id}", modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ExpandMore, contentDescription = "选择 Provider")
                }

                OutlinedTextField(
                    value = baseUrlInput,
                    onValueChange = { updateProvider(snapshot, parsed, currentProvider.copy(baseUrl = it), onSnapshotChange) },
                    enabled = parsed.parseError == null,
                    label = { Text("Base URL") },
                    placeholder = { Text("http:// 或 https://...") },
                    leadingIcon = { Icon(Icons.Rounded.Public, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )

                // Every keystroke is part of the snapshot, including direct Save/export.
                var showKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { updateProvider(snapshot, parsed, currentProvider.copy(apiKey = it), onSnapshotChange) },
                    enabled = parsed.parseError == null,
                    label = { Text("API Key") },
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (showKey) "隐藏" else "显示") } },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = currentProvider.api,
                    onValueChange = { updateProvider(snapshot, parsed, currentProvider.copy(api = it), onSnapshotChange) },
                    enabled = parsed.parseError == null,
                    label = { Text("API Type") },
                    supportingText = { Text("openai-completions / openai-responses / anthropic-messages / google-generative-ai") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                // The default model is materialized on Save; picker selections add definitions.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { onSnapshotChange(snapshot.copy(defaultModel = it, defaultProvider = currentProvider.id)) },
                        enabled = parsed.parseError == null,
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                    IconButton(onClick = { showModelPicker = true }) {
                        Icon(Icons.Rounded.ExpandMore, contentDescription = "选择模型")
                    }
                }
                ModelReasoningControl(
                    checked = (currentProvider.models.firstOrNull { it.id == modelInput.trim() }?.raw?.get("reasoning") as? JsonPrimitive)?.booleanOrNull == true,
                    enabled = parsed.parseError == null && modelInput.isNotBlank(),
                    onCheckedChange = { reasoning ->
                        val id = modelInput.trim()
                        val model = currentProvider.models.firstOrNull { it.id == id }
                            ?: ModelConfig(id, currentProvider.id, id)
                        val updated = model.copy(raw = JsonObject(model.raw + ("reasoning" to JsonPrimitive(reasoning))))
                        val models = if (currentProvider.models.any { it.id == id }) {
                            currentProvider.models.map { if (it.id == id) updated else it }
                        } else currentProvider.models + updated
                        updateProvider(snapshot.copy(defaultModel = id), parsed, currentProvider.copy(models = models), onSnapshotChange)
                    }
                )

                // 推荐模型 chips
                MODEL_SUGGESTIONS[currentProvider.id]?.let { suggestions ->
                    val unadded = suggestions
                    if (unadded.isNotEmpty()) {
                        androidx.compose.foundation.layout.FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            unadded.take(5).forEach { modelId ->
                                AssistChip(
                                    onClick = { selectModel(snapshot, parsed, currentProvider, modelId, onSnapshotChange) },
                                    label = { Text(modelId, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                // 操作按钮行
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val provider = currentProvider
                            testingProvider = currentProvider.id
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { testProviderConnection(provider) }
                                testingProvider = null
                                snackbarHost.showSnackbar(if (result.success) "✅ ${provider.displayName} 连接成功" else "❌ ${result.message}")
                            }
                        },
                        enabled = testingProvider == null && parsed.parseError == null && baseUrlInput.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testingProvider == currentProvider.id) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                        else { Icon(Icons.Rounded.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(6.dp)); Text("测试", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val provider = currentProvider
                            testingProvider = "${currentProvider.id}_fetch"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { testProviderConnection(provider) }
                                testingProvider = null
                                val now = latestSnapshot
                                if (now.materializedDir != snapshot.materializedDir || now.modelsText != snapshot.modelsText || now.envText != snapshot.envText || now.defaultProvider != snapshot.defaultProvider || now.defaultModel != snapshot.defaultModel) {
                                    snackbarHost.showSnackbar("配置已更改；未应用过期的模型列表")
                                } else if (result.success && result.availableModels.isNotEmpty()) {
                                    val additions = result.availableModels.filter { id -> provider.models.none { it.id == id } }.map { ModelConfig(it, provider.id, it) }
                                    updateProvider(now, parseConfig(now.modelsText, now.envText), provider.copy(models = provider.models + additions), latestOnChange)
                                    snackbarHost.showSnackbar("已拉取 ${result.availableModels.size} 个模型")
                                } else { snackbarHost.showSnackbar(if (result.success) "服务未返回模型" else "❌ ${result.message}") }
                            }
                        },
                        enabled = testingProvider == null && parsed.parseError == null && baseUrlInput.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (testingProvider == "${currentProvider.id}_fetch") { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                        else { Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(6.dp)); Text("拉取模型", fontSize = 12.sp)
                    }
                }

                // 导入/导出
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showExportDialog = exportConfig(snapshot) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("导出", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { showImportDialog = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("导入", fontSize = 12.sp)
                    }
                }
            }
        }

        // ── 高级设置 (可折叠) ──
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("高级设置", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
        }
        AnimatedVisibility(showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Thinking Level
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Thinking", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("off", "minimal", "low", "medium", "high", "xhigh").forEach { level ->
                                val selected = snapshot.defaultThinkingLevel == level
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { onSnapshotChange(snapshot.copy(defaultThinkingLevel = level)) }
                                ) {
                                    Text(level, fontSize = 11.sp, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                            }
                        }
                    }
                }
                // APPEND_SYSTEM.md
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("APPEND_SYSTEM.md", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = snapshot.appendSystem,
                            onValueChange = { onSnapshotChange(snapshot.copy(appendSystem = it)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        )
                    }
                }
                // Extra args
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("额外启动参数 (每行一个)", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = snapshot.extraArgsText,
                            onValueChange = { onSnapshotChange(snapshot.copy(extraArgsText = it)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        )
                    }
                }
                // Raw JSON
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("models.json (原始 JSON)", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = snapshot.modelsText,
                            onValueChange = { onSnapshotChange(snapshot.copy(modelsText = it, modelCatalog = parseAgentModelCatalog(it))) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        )
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbarHost) { Snackbar(it) }
    }

    // ── Model picker bottom sheet (搜索 + 完整列表) ──
    if (showModelPicker) {
        val sheetState = rememberModalBottomSheetState()
        var modelSearch by remember { mutableStateOf("") }
        // 合并已配置模型 + 推荐模型 + 已输入的模型
        val allModels = remember(currentProvider, modelSearch) {
            val configured = currentProvider.models.map { it.id }
            val suggested = MODEL_SUGGESTIONS[currentProvider.id] ?: emptyList()
            val combined = (configured + suggested).distinct()
            if (modelSearch.isBlank()) combined else combined.filter { it.contains(modelSearch, ignoreCase = true) }
        }
        ModalBottomSheet(onDismissRequest = { showModelPicker = false }, sheetState = sheetState) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择模型", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = modelSearch, onValueChange = { modelSearch = it },
                    placeholder = { Text("搜索或输入模型名...", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { if (modelSearch.isNotEmpty()) IconButton(onClick = { modelSearch = "" }) { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp)) } },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                // 如果搜索的内容不在列表里，可以直接用它作为模型名
                if (modelSearch.isNotBlank() && allModels.none { it.equals(modelSearch, ignoreCase = true) }) {
                    Surface(modifier = Modifier.fillMaxWidth().clickable {
                        selectModel(snapshot, parsed, currentProvider, modelSearch, onSnapshotChange); showModelPicker = false
                    }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("使用 \"$modelSearch\"", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allModels.size) { idx ->
                        val modelId = allModels[idx]
                        val isConfigured = currentProvider.models.any { it.id == modelId }
                        val isSelected = modelId == snapshot.defaultModel
                        Surface(modifier = Modifier.fillMaxWidth().clickable {
                            selectModel(snapshot, parsed, currentProvider, modelId, onSnapshotChange); showModelPicker = false
                        }, shape = RoundedCornerShape(8.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(modelId, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (!isConfigured) Text("推荐", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // ── Provider picker dialog (搜索 + 列表) ──
    if (showProviderPicker) {
        var providerSearch by remember { mutableStateOf("") }
        val filteredPresets = remember(providerSearch, parsed.providers) {
            val configured = parsed.providers.map { p -> ProviderPreset(p.id, p.displayName, p.baseUrl, p.envKey, p.color, "已配置 · ${p.api}") }
            (configured + PROVIDER_PRESETS.filter { preset -> configured.none { it.id == preset.id } }).filter { it.displayName.contains(providerSearch, ignoreCase = true) || it.id.contains(providerSearch, ignoreCase = true) || it.description.contains(providerSearch, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { showProviderPicker = false },
            title = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("选择 Provider", modifier = Modifier.weight(1f))
                    IconButton(onClick = { showProviderPicker = false; showCreateProvider = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "新建自定义 Provider")
                    }
                }
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = providerSearch, onValueChange = { providerSearch = it },
                        placeholder = { Text("搜索 Provider...", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = { if (providerSearch.isNotEmpty()) IconButton(onClick = { providerSearch = "" }) { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp)) } },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filteredPresets.size) { idx ->
                            val p = filteredPresets[idx]
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { switchProvider(snapshot, parsed, p, onSnapshotChange); showProviderPicker = false },
                                shape = RoundedCornerShape(8.dp),
                                color = if (p.id == currentProvider.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(28.dp).clip(CircleShape).background(p.color), contentAlignment = Alignment.Center) { Text(p.displayName.take(1), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.displayName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                        Text(p.id, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(p.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (p.id == currentProvider.id) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProviderPicker = false }) { Text("取消") } }
        )
    }

    if (showCreateProvider) {
        CustomProviderDialog(
            existingIds = (parsed.providers.map { it.id } + PROVIDER_PRESETS.map { it.id }).toSet(),
            onDismiss = { showCreateProvider = false; showProviderPicker = true },
            onCreate = { provider ->
                selectProvider(snapshot, parsed, provider, onSnapshotChange)
                showCreateProvider = false
            }
        )
    }

    // ── Export dialog ──
    showExportDialog?.let { json ->
        AlertDialog(
            onDismissRequest = { showExportDialog = null },
            title = { Text("导出配置") },
            text = {
                Column {
                    Text("复制以下 JSON 保存备份:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        Text(json, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExportDialog = null }) { Text("关闭") } }
        )
    }

    // ── Import dialog ──
    if (showImportDialog) {
        var importText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入配置") },
            text = {
                Column {
                    Text("粘贴之前导出的 JSON:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText, onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = { TextButton(onClick = {
                parseImportedConfig(importText.trim())?.let { onSnapshotChange(it.copy(materializedDir = snapshot.materializedDir)); scope.launch { snackbarHost.showSnackbar("配置已导入；点击保存写入当前工作区") }; showImportDialog = false }
                ?: scope.launch { snackbarHost.showSnackbar("解析失败") }
            }) { Text("导入") } },
            dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ModelReasoningControl(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("支持思考/推理", style = MaterialTheme.typography.bodyMedium)
            Text("仅在模型实际支持时开启。保存配置后重新启动 PI 生效；不会为模型添加推理能力。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun CustomProviderDialog(
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (ProviderConfig) -> Unit
) {
    var providerId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var api by remember { mutableStateOf("openai-completions") }
    var apiKey by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var modelReasoning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建自定义 Provider") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = providerId, onValueChange = { providerId = it; error = null },
                    label = { Text("Provider ID") },
                    supportingText = { Text("唯一标识，不可与已有 Provider 或内置预设重复") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = displayName, onValueChange = { displayName = it; error = null },
                    label = { Text("显示名称（可选，默认为 ID）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it; error = null },
                    label = { Text("Base URL") },
                    leadingIcon = { Icon(Icons.Rounded.Public, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = api, onValueChange = { api = it; error = null },
                    label = { Text("API Type") },
                    supportingText = { Text("openai-completions / openai-responses / anthropic-messages / google-generative-ai") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it; error = null },
                    label = { Text("API Key") },
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = modelId, onValueChange = { modelId = it; error = null },
                    label = { Text("Model ID") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                ModelReasoningControl(checked = modelReasoning, onCheckedChange = { modelReasoning = it })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = providerId.trim()
                val url = baseUrl.trim()
                error = when {
                    id.isBlank() -> "Provider ID 不能为空"
                    id in existingIds -> "Provider ID 已存在，请使用其他 ID"
                    !isValidProviderBaseUrl(url) -> "Base URL 必须是有效的 HTTP(S) 地址，且不含凭据、查询参数或片段"
                    api.isBlank() -> "API Type 不能为空"
                    apiKey.isBlank() -> "API Key 不能为空"
                    modelId.isBlank() -> "Model ID 不能为空"
                    else -> null
                }
                if (error == null) {
                    val name = displayName.takeIf { it.isNotBlank() } ?: id
                    val model = modelId.trim()
                    onCreate(ProviderConfig(
                        id = id, displayName = name, baseUrl = url,
                        envKey = "", color = Color(0xFF6B7280), apiKey = apiKey,
                        models = listOf(ModelConfig(model, id, model, JsonObject(customPiModel(model) + ("reasoning" to JsonPrimitive(modelReasoning))))),
                        api = api.trim(), raw = JsonObject(mapOf("displayName" to JsonPrimitive(name)))
                    ))
                }
            }) { Text("创建并选择") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun isValidProviderBaseUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535)
}.getOrDefault(false)

// ─── Helper: switch/update provider in config ──────────────────────────────────

private fun switchProvider(snapshot: PiConfigSnapshot, parsed: ParsedConfig, preset: ProviderPreset, onSnapshotChange: (PiConfigSnapshot) -> Unit) {
    val selected = parsed.providers.firstOrNull { it.id == preset.id }
        ?: ProviderConfig(preset.id, preset.displayName, preset.baseUrl, "", preset.color, "", emptyList())
    selectProvider(snapshot, parsed, selected, onSnapshotChange)
}

private fun selectProvider(snapshot: PiConfigSnapshot, parsed: ParsedConfig, provider: ProviderConfig, onSnapshotChange: (PiConfigSnapshot) -> Unit) {
    if (parsed.parseError != null) return
    val previousModel = snapshot.defaultModel.trim()
    // Keep the last selected model first without replacing its existing metadata.
    var providers = parsed.providers.map { previous ->
        if (previous.id == snapshot.defaultProvider && previousModel.isNotBlank()) {
            val model = previous.models.firstOrNull { it.id == previousModel }
                ?: ModelConfig(previousModel, previous.id, previousModel)
            previous.copy(models = listOf(model) + previous.models.filterNot { it.id == previousModel })
        } else previous
    }
    val selected = providers.firstOrNull { it.id == provider.id } ?: provider.also { providers = providers + it }
    val json = buildModelsJson(providers, parsed.root)
    onSnapshotChange(snapshot.copy(modelsText = json, envText = rebuildEnv(parsed.env, providers), defaultProvider = selected.id,
        defaultModel = if (snapshot.defaultProvider == selected.id) snapshot.defaultModel else selected.models.firstOrNull()?.id.orEmpty(), modelCatalog = parseAgentModelCatalog(json)))
}

private fun updateProvider(snapshot: PiConfigSnapshot, parsed: ParsedConfig, updated: ProviderConfig, onSnapshotChange: (PiConfigSnapshot) -> Unit) {
    if (parsed.parseError != null) return
    val providers = if (parsed.providers.any { it.id == updated.id }) parsed.providers.map { if (it.id == updated.id) updated else it } else parsed.providers + updated
    val json = buildModelsJson(providers, parsed.root)
    onSnapshotChange(snapshot.copy(modelsText = json, envText = rebuildEnv(parsed.env, providers), defaultProvider = updated.id, modelCatalog = parseAgentModelCatalog(json)))
}

private fun selectModel(snapshot: PiConfigSnapshot, parsed: ParsedConfig, provider: ProviderConfig, model: String, onChange: (PiConfigSnapshot) -> Unit) {
    val id = model.trim()
    val updated = if (provider.models.any { it.id == id }) provider else provider.copy(models = provider.models + ModelConfig(id, provider.id, id))
    updateProvider(snapshot.copy(defaultModel = id), parsed, updated, onChange)
}

// ─── Connection testing ────────────────────────────────────────────────────────

private fun testProviderConnection(provider: ProviderConfig): TestResult {
    val secrets = buildList {
        if (provider.apiKey.isNotBlank()) add(provider.apiKey)
        (provider.raw["headers"] as? JsonObject)?.values?.forEach { (it as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let(::add) }
    }
    fun safe(message: String): String = secrets.fold(message) { text, secret -> text.replace(secret, "[redacted]") }
        .replace(Regex("(?i)(bearer\\s+)[^\\s,;]+"), "$1[redacted]")
    return try {
        require(!provider.apiKey.startsWith("!")) { "命令式凭据请在 PI 中验证；手机测试不会执行命令" }
        parsePiModels(buildModelsJson(listOf(provider), JsonObject(emptyMap())))
        require(provider.baseUrl.isNotBlank()) { "Base URL 为空" }
        val base = provider.baseUrl.trim().trimEnd('/')
        val url = when (provider.api) {
            "openai-completions", "openai-responses" -> "$base/models"
            "anthropic-messages" -> if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
            "google-generative-ai" -> "$base/models"
            else -> error("手机测试不支持此 API Type；配置仍可保存供 PI 使用")
        }
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000
            instanceFollowRedirects = false
            if (provider.apiKey.isNotBlank()) when (provider.api) {
                "anthropic-messages" -> setRequestProperty("x-api-key", provider.apiKey)
                "google-generative-ai" -> setRequestProperty("x-goog-api-key", provider.apiKey)
                else -> setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
            }
            if (provider.api == "anthropic-messages") setRequestProperty("anthropic-version", "2023-06-01")
            if ((provider.raw["authHeader"] as? JsonPrimitive)?.contentOrNull == "true" && provider.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
            (provider.raw["headers"] as? JsonObject)?.forEach { (name, value) -> setRequestProperty(name, value.jsonPrimitive.content) }
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = (obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive
                ?: obj?.get("message") as? JsonPrimitive
            if (code !in 200..299) TestResult(false, code, safe("HTTP $code: ${error?.contentOrNull ?: conn.responseMessage.orEmpty()}"))
            else {
                val list = obj?.get(if (provider.api == "google-generative-ai") "models" else "data") as? JsonArray
                if (list == null || obj?.get("error") != null) TestResult(false, code, "响应不是有效的模型列表")
                else {
                    val field = if (provider.api == "google-generative-ai") "name" else "id"
                    val models = list.mapNotNull { (it as? JsonObject)?.get(field)?.let { it as? JsonPrimitive }?.contentOrNull?.removePrefix("models/")?.takeIf { it.isNotBlank() } }.distinct()
                    if (models.size != list.size) TestResult(false, code, "模型列表含无效或重复的模型 ID")
                    else TestResult(true, code, "模型端点连接成功", models)
                }
            }
        } finally { conn.disconnect() }
    } catch (_: java.net.UnknownHostException) { TestResult(false, -1, "无法解析主机名") }
    catch (_: java.net.SocketTimeoutException) { TestResult(false, -1, "连接超时") }
    catch (e: Exception) { TestResult(false, -1, safe(e.message ?: "未知连接错误")) }
}

// ─── Config export / import ────────────────────────────────────────────────────

private fun exportConfig(snapshot: PiConfigSnapshot): String {
    val export = ConfigExport(
        defaultProvider = snapshot.defaultProvider, defaultModel = snapshot.defaultModel,
        defaultThinkingLevel = snapshot.defaultThinkingLevel, enabledModels = snapshot.enabledModels,
        settingsText = snapshot.settingsText, modelsText = snapshot.modelsText, envText = snapshot.envText,
        appendSystem = snapshot.appendSystem, extraArgsText = snapshot.extraArgsText,
        exportedAt = System.currentTimeMillis()
    )
    return Json { prettyPrint = true; encodeDefaults = true }.encodeToString(ConfigExport.serializer(), export)
}

private fun parseImportedConfig(json: String): PiConfigSnapshot? = try {
    val export = Json { ignoreUnknownKeys = true }.decodeFromString(ConfigExport.serializer(), json)
    parsePiModels(export.modelsText)
    val parsed = parseConfig(export.modelsText, export.envText)
    require(parsed.parseError == null && parsed.env.keys.all { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) })
    require(export.defaultProvider.isBlank() == export.defaultModel.isBlank())
    require(Json.parseToJsonElement(export.settingsText.ifBlank { "{}" }) is JsonObject)
    PiConfigSnapshot(
        defaultProvider = export.defaultProvider, defaultModel = export.defaultModel,
        defaultThinkingLevel = export.defaultThinkingLevel.ifBlank { "medium" },
        enabledModels = export.enabledModels, settingsText = export.settingsText.ifBlank { "{}" },
        modelsText = export.modelsText.ifBlank { "{}" }, envText = export.envText.ifBlank { "{}" },
        appendSystem = export.appendSystem, extraArgsText = export.extraArgsText,
        modelCatalog = parseAgentModelCatalog(export.modelsText.ifBlank { "{}" })
    )
} catch (e: Exception) {
    // Never log imported JSON or parser exceptions, which may contain credentials.
    null
}
