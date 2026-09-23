package dev.idadroid.agent

import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.util.JsonFormats
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 配置导入导出 + API 连接测试 + 模型列表拉取。
 * 借鉴自 Operit 的 ModelConfigConnectionTester / ModelListFetcher / exportAllConfigs。
 */
class AiConfigTools(
    private val paths: EnvironmentPaths,
    private val configManager: PiConfigManager
) {
    private val agentDir get() = File(paths.rootfsDir, "root/pi_workspace/.idadroid/pi-agent")

    /** 导出完整 AI 配置为 JSON 字符串 */
    fun exportConfig(): String {
        val snapshot = configManager.readSnapshot()
        val export = ConfigExport(
            defaultProvider = snapshot.defaultProvider,
            defaultModel = snapshot.defaultModel,
            defaultThinkingLevel = snapshot.defaultThinkingLevel,
            enabledModels = snapshot.enabledModels,
            settingsText = snapshot.settingsText,
            modelsText = snapshot.modelsText,
            envText = snapshot.envText,
            appendSystem = snapshot.appendSystem,
            extraArgsText = snapshot.extraArgsText,
            version = 1,
            exportedAt = System.currentTimeMillis()
        )
        return JsonFormats.pretty.encodeToString(ConfigExport.serializer(), export)
    }

    /** 从 JSON 字符串导入配置，返回是否成功 */
    fun importConfig(json: String): Result<Unit> = runCatching {
        val imported = JsonFormats.pretty.decodeFromString(ConfigExport.serializer(), json)
        val snapshot = PiConfigSnapshot(
            defaultProvider = imported.defaultProvider,
            defaultModel = imported.defaultModel,
            defaultThinkingLevel = imported.defaultThinkingLevel.ifBlank { "medium" },
            enabledModels = imported.enabledModels,
            settingsText = imported.settingsText.ifBlank { "{}" },
            modelsText = imported.modelsText.ifBlank { "{}" },
            envText = imported.envText.ifBlank { "{}" },
            appendSystem = imported.appendSystem,
            extraArgsText = imported.extraArgsText,
            modelCatalog = parseAgentModelCatalog(imported.modelsText.ifBlank { "{}" })
        )
        configManager.saveSnapshot(snapshot)
    }

    /**
     * 测试 API 连接。
     * 返回 Result.success(TestResult) 或 Result.failure。
     */
    suspend fun testConnection(
        providerId: String,
        baseUrl: String,
        apiKey: String,
        modelId: String? = null
    ): Result<TestResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (apiKey.isBlank()) error("API Key 为空")
            if (baseUrl.isBlank()) error("Base URL 为空")

            // Gemini: GET /v1beta/models?key=KEY — 不需要 POST
            if (providerId == "google") {
                return@runCatching testGeminiConnectionInternal(baseUrl, apiKey)
            }

            // Anthropic / OpenAI 风格: POST 最小请求
            val completedUrl = EndpointCompleter.complete(baseUrl, providerId)
            val conn = (URL(completedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                when (providerId) {
                    "anthropic" -> {
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    else -> setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }

            try {
                val testModel = modelId ?: defaultModelForProvider(providerId)
                val requestBody = if (providerId == "anthropic") {
                    """{"model":"$testModel","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                } else {
                    """{"model":"$testModel","max_tokens":1,"messages":[{"role":"user","content":"hi"}],"stream":false}"""
                }

                conn.outputStream.use { it.write(requestBody.toByteArray()) }

                val code = conn.responseCode
                val responseBody = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                val success = code in 200..299
                val message = if (success) "连接成功"
                    else extractErrorMessage(responseBody) ?: "HTTP $code"

                // Anthropic 400 可能是模型名过期但 Key 有效
                val effectiveSuccess = success || (providerId == "anthropic" && code == 400)
                val effectiveMessage = if (providerId == "anthropic" && code == 400 && !success)
                    "连接成功（Key 有效，模型可能需要更新）" else message

                TestResult(
                    success = effectiveSuccess,
                    httpCode = code,
                    message = effectiveMessage
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun testGeminiConnectionInternal(baseUrl: String, apiKey: String): TestResult {
        val base = baseUrl.trimEnd('/')
        val geminiUrl = if (base.contains("/v1beta")) {
            "$base/models?key=$apiKey"
        } else {
            "$base/v1beta/models?key=$apiKey"
        }
        val conn = (URL(geminiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            val models = if (code in 200..299) parseModelList(body, "google") else emptyList()
            return TestResult(
                success = code in 200..299,
                httpCode = code,
                message = if (code in 200..299) "连接成功" else extractErrorMessage(body) ?: "HTTP $code",
                availableModels = models
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 从 API 拉取可用模型列表。
     * 支持 OpenAI 风格 /v1/models、Anthropic /v1/models、Gemini /v1beta/models。
     */
    suspend fun fetchModelList(
        providerId: String,
        baseUrl: String,
        apiKey: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (apiKey.isBlank()) error("API Key 为空")
            if (baseUrl.isBlank()) error("Base URL 为空")

            val modelsUrl = when (providerId) {
                "google" -> {
                    val base = baseUrl.trimEnd('/')
                    if (base.contains("/v1beta")) "$base/models?key=$apiKey"
                    else "$base/v1beta/models?key=$apiKey"
                }
                "anthropic" -> {
                    val base = baseUrl.trimEnd('/').removeSuffix("/v1")
                    "$base/v1/models"
                }
                else -> {
                    val base = baseUrl.trimEnd('/')
                    val v1Base = if (base.endsWith("/v1")) base else "$base/v1"
                    "$v1Base/models"
                }
            }

            val url = URL(modelsUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                when (providerId) {
                    "anthropic" -> {
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    "google" -> { /* key is in URL */ }
                    else -> setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }

            try {
                val code = conn.responseCode
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    error("HTTP $code: ${conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""}")
                }

                parseModelList(body, providerId)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parseModelList(jsonBody: String, providerId: String): List<String> {
        return try {
            val parsed = JsonFormats.pretty.parseToJsonElement(jsonBody).jsonObject
            when (providerId) {
                "google" -> {
                    // Gemini: { "models": [{ "name": "models/gemini-2.0-flash", ... }] }
                    (parsed["models"] as? JsonArray)?.mapNotNull { model ->
                        val name = (model as? JsonObject)?.get("name") as? JsonPrimitive
                        name?.contentOrNull?.removePrefix("models/")
                    } ?: emptyList()
                }
                else -> {
                    // OpenAI compatible: { "data": [{ "id": "gpt-4o", ... }] }
                    (parsed["data"] as? JsonArray)?.mapNotNull { model ->
                        ((model as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                    } ?: emptyList()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AiConfigTools", "fetchModelList 失败: ${e.message}")
            emptyList()
        }
    }

    private fun defaultModelForProvider(providerId: String): String = when (providerId) {
        "openai" -> "gpt-4o-mini"
        "anthropic" -> "claude-3-5-haiku-20241022"
        "deepseek" -> "deepseek-chat"
        "google" -> "gemini-2.0-flash-lite"
        "mistral" -> "mistral-small-latest"
        "groq" -> "llama-3.1-8b-instant"
        else -> "test"
    }

    private fun extractErrorMessage(body: String): String? = try {
        val parsed = JsonFormats.pretty.parseToJsonElement(body).jsonObject
        (((parsed["error"] as? JsonObject)?.get("message")) as? JsonPrimitive)?.contentOrNull
            ?: (parsed["message"] as? JsonPrimitive)?.contentOrNull
    } catch (e: Exception) {
        android.util.Log.w("AiConfigTools", "错误消息解析失败: ${e.message}")
        null
    }
}

data class TestResult(
    val success: Boolean,
    val httpCode: Int,
    val message: String,
    val availableModels: List<String> = emptyList()
)
