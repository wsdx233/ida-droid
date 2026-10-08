package dev.idadroid.agent

import kotlinx.serialization.json.*

/** Validate before changing originals; normalize only the legacy provider spellings. */
internal fun parsePiModels(text: String, validateValues: Boolean = true): JsonObject {
    val root = try { Json.parseToJsonElement(text.ifBlank { "{}" }) as? JsonObject }
        catch (_: Exception) { error("Invalid models.json JSON; content hidden for privacy") }
        ?: error("models.json must be a JSON object")
    val providers = root["providers"]?.let { it as? JsonObject ?: error("providers must be an object") }
        ?: return root
    providers.forEach { (id, element) ->
        require(id.isNotBlank() && id == id.trim()) { "Provider ID must not be blank or padded" }
        val provider = element as? JsonObject ?: error("Provider must be an object")
        listOf("baseUrl", "baseURL", "api", "apiKey", "envKey").forEach { field ->
            provider[field]?.let { require(it is JsonPrimitive && it.isString) { "$field must be a string" } }
        }
        val base = (provider["baseUrl"] ?: provider["baseURL"])?.jsonPrimitive?.content.orEmpty()
        if (validateValues && base.isNotBlank()) {
            val uri = runCatching { java.net.URI(base) }.getOrNull()
            require(uri != null && uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null) { "Base URL must be an HTTP(S) base URL without credentials, query or fragment" }
        }
        provider["compat"]?.let { require(it is JsonObject) { "compat must be an object" } }
        provider["headers"]?.let { headers ->
            require(headers is JsonObject && headers.values.all { it is JsonPrimitive && it.isString }) { "headers must contain strings" }
        }
        provider["authHeader"]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "authHeader must be boolean" } }
        provider["models"]?.let { element ->
            val models = element as? JsonArray ?: error("models must be an array")
            val ids = mutableSetOf<String>()
            models.forEach { item ->
                val model = item as? JsonObject ?: error("Model must be an object")
                val modelId = (model["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                require(modelId.isNotBlank() && modelId == modelId.trim() && ids.add(modelId)) { "Model IDs must be nonblank and unique within a provider" }
                model["name"]?.let { require(it is JsonPrimitive && it.isString) { "Model name must be a string" } }
                model["reasoning"]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "reasoning must be boolean" } }
                model["input"]?.let { require(it is JsonArray && it.all { entry -> entry is JsonPrimitive && entry.isString }) { "input must be an array of strings" } }
                model["thinkingLevelMap"]?.let { thinking ->
                    require(thinking is JsonObject && thinking.values.all { it == JsonNull || (it is JsonPrimitive && it.isString) }) { "thinkingLevelMap must contain string or null values" }
                }
                listOf("contextWindow", "maxTokens").forEach { field -> model[field]?.let {
                    require(it is JsonPrimitive && !it.isString && (it.longOrNull ?: 0) > 0) { "$field must be a positive integer" }
                } }
                model["cost"]?.let { cost ->
                    require(cost is JsonObject) { "cost must be an object" }
                    listOf("input", "output", "cacheRead", "cacheWrite").forEach { field -> cost[field]?.let {
                        val value = (it as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                        require(value != null && value.isFinite() && value >= 0) { "cost.$field must be a nonnegative finite number" }
                    } }
                }
            }
        }
    }
    return root
}

internal fun defaultProviderApi(id: String): String = when (id) {
    "anthropic" -> "anthropic-messages"
    "google" -> "google-generative-ai"
    else -> "openai-completions"
}

internal fun normalizePiProvider(id: String, source: JsonObject): JsonObject = JsonObject(source.toMutableMap().apply {
    val legacy = containsKey("baseURL") || containsKey("envKey")
    remove("baseURL")?.let { if (!containsKey("baseUrl")) put("baseUrl", it) }
    remove("envKey")?.let { if (!containsKey("apiKey")) put("apiKey", it) }
    if (legacy && !containsKey("api")) put("api", JsonPrimitive(defaultProviderApi(id)))
})

internal fun customPiModel(id: String): JsonObject = buildJsonObject {
    put("id", id); put("name", id); put("reasoning", false)
    put("input", buildJsonArray { add("text") })
    put("contextWindow", 128000); put("maxTokens", 16384)
    put("cost", buildJsonObject { put("input", 0); put("output", 0); put("cacheRead", 0); put("cacheWrite", 0) })
}

data class TestResult(
    val success: Boolean,
    val httpCode: Int,
    val message: String,
    val availableModels: List<String> = emptyList()
)
