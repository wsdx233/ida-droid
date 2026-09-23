package dev.idadroid.agent

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Layer 1: 直接 HTTPS 对话客户端
 *
 * 直接调用 OpenAI 兼容的 /v1/chat/completions 接口，支持：
 * - SSE 流式输出
 * - Function calling (tools)
 * - 思考/推理 (thinking/reasoning)
 * - 指数退避重试（429/5xx/网络错误）
 * - API 错误解析（OpenAI/Anthropic/Google 格式）
 * - Token 使用量统计
 *
 * 不依赖 pi agent，不依赖 proot 管道，不依赖环境变量传递。
 * 直接用已配好的 baseUrl + apiKey + model。
 */
class ChatHttpClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val providerId: String = ""
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

    @kotlinx.serialization.Serializable
    data class ChatMessageDto(
        val role: String,        // system / user / assistant / tool
        val content: String? = null,
        val images: List<String> = emptyList(),  // base64 data URIs for vision
        val toolCalls: List<ToolCallDto> = emptyList(),
        val toolCallId: String? = null,
        val name: String? = null
    )

    @kotlinx.serialization.Serializable
    data class ToolCallDto(
        val id: String,
        val name: String,
        val arguments: String   // JSON string
    )

    data class ToolDefinition(
        val name: String,
        val description: String,
        val parameters: JsonObject  // JSON Schema
    )

    /** Token 使用量统计 */
    data class TokenUsage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0
    )

    sealed interface StreamEvent {
        /** 文本增量 */
        data class TextDelta(val text: String) : StreamEvent
        /** 思考/推理增量 */
        data class ThinkingDelta(val text: String) : StreamEvent
        /** 工具调用增量 */
        data class ToolCallDelta(
            val index: Int,
            val id: String?,
            val name: String?,
            val argumentsDelta: String
        ) : StreamEvent
        /** 流结束 */
        data class Finish(
            val reason: String,           // stop / tool_calls / length
            val toolCalls: List<ToolCallDto>,
            val usage: TokenUsage = TokenUsage()
        ) : StreamEvent
        /** 错误 */
        data class Error(val message: String, val httpCode: Int? = null, val retryable: Boolean = false) : StreamEvent
        /** 重试中（用于 UI 提示） */
        data class Retrying(val attempt: Int, val reason: String, val delayMs: Long) : StreamEvent
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 10_000L

        /** 判断 HTTP 状态码是否可重试 */
        private fun isRetryableHttp(code: Int): Boolean = code == 429 || code in 500..599

        /** 判断异常是否可重试 */
        private fun isRetryableException(e: Exception): Boolean = when (e) {
            is java.io.IOException -> true  // SocketTimeoutException 是 IOException 的子类
            else -> false
        }
    }

    /**
     * 发送对话请求，返回 SSE 流事件。
     *
     * @param messages 对话历史
     * @param tools 可用工具定义
     * @param systemPrompt 系统提示词（会作为第一条 system 消息插入）
     * @param thinkingLevel 思考级别 (low/medium/high，部分模型支持)
     * @param temperature 温度参数
     * @param maxTokens 最大生成 token 数（Anthropic 必填）
     * @param topP 核采样参数
     */
    fun chat(
        messages: List<ChatMessageDto>,
        tools: List<ToolDefinition> = emptyList(),
        systemPrompt: String? = null,
        thinkingLevel: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        topP: Double? = null
    ): Flow<StreamEvent> = flow {
        val allMessages = buildList {
            if (!systemPrompt.isNullOrBlank()) {
                add(ChatMessageDto(role = "system", content = systemPrompt))
            }
            addAll(normalizeToolPairing(messages))
        }

        val requestBody = buildRequestBody(
            allMessages, tools, thinkingLevel, temperature, maxTokens, topP
        )
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        var retryDelay = INITIAL_RETRY_DELAY_MS
        var lastError: StreamEvent.Error? = null
        // 工具调用增量状态 — 在重试间共享，重试前清理
        val pendingToolCalls = mutableMapOf<Int, MutableMap<String, String>>()

        for (attempt in 1..MAX_RETRIES) {
            var shouldRetry = false
            var retryReason = ""
            var connection: HttpURLConnection? = null

            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30_000
                    readTimeout = 300_000  // 5 分钟读超时（流式模式不会真的等这么久）
                    doInput = true
                    instanceFollowRedirects = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "text/event-stream")
                }
                // 先赋值再写入，避免 write 抛异常时 connection 仍为 null 导致连接泄漏
                connection = conn
                conn.outputStream.use { os ->
                    os.write(json.encodeToString(JsonObject.serializer(), requestBody).toByteArray())
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }
                        ?: "HTTP $responseCode"
                    val parsedError = parseApiError(errorBody, responseCode)
                    lastError = StreamEvent.Error(parsedError, responseCode, isRetryableHttp(responseCode))

                    if (isRetryableHttp(responseCode) && attempt < MAX_RETRIES) {
                        shouldRetry = true
                        retryReason = "HTTP $responseCode"
                        // 429 Too Many Requests: 尝试读取 Retry-After header
                        val retryAfter = conn.getHeaderField("Retry-After")?.toIntOrNull()
                        if (retryAfter != null) {
                            retryDelay = (retryAfter * 1000L).coerceAtMost(MAX_RETRY_DELAY_MS)
                        }
                    }
                } else {
                    // SSE 流式读取
                    val streamResult = readSSEStream(conn, pendingToolCalls)
                    var hasEmitted = false

                    streamResult.events.forEach { event ->
                        emit(event)
                        hasEmitted = true
                    }

                    // 如果流正常结束（收到 Finish 或 Error），不重试
                    if (hasEmitted) return@flow

                    // 流为空 — 可能是 keep-alive 连接提前关闭
                    if (attempt < MAX_RETRIES) {
                        shouldRetry = true
                        retryReason = "空响应流"
                    } else {
                        lastError = StreamEvent.Error("服务器返回空响应", responseCode, false)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val retryable = isRetryableException(e)
                lastError = StreamEvent.Error(e.message ?: e::class.simpleName ?: "网络错误", null, retryable)

                if (retryable && attempt < MAX_RETRIES) {
                    shouldRetry = true
                    retryReason = e.javaClass.simpleName
                }
            } finally {
                connection?.disconnect()
            }

            if (shouldRetry) {
                // 清理上次失败请求的残留状态，防止部分工具调用混入下次重试
                pendingToolCalls.clear()
                emit(StreamEvent.Retrying(attempt + 1, retryReason, retryDelay))
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            } else {
                lastError?.let { emit(it) }
                return@flow
            }
        }

        // 所有重试用尽
        lastError?.let { emit(it) }
    }.flowOn(Dispatchers.IO)

    /** 构建请求体 */
    private fun buildRequestBody(
        messages: List<ChatMessageDto>,
        tools: List<ToolDefinition>,
        thinkingLevel: String?,
        temperature: Double?,
        maxTokens: Int?,
        topP: Double?
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            messages.forEach { msg ->
                add(buildJsonObject {
                    put("role", msg.role)
                    // content: 支持纯文本和多模态
                    if (msg.images.isNotEmpty()) {
                        put("content", buildJsonArray {
                            if (!msg.content.isNullOrBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                })
                            }
                            msg.images.forEach { img ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", img)
                                    })
                                })
                            }
                        })
                    } else {
                        put("content", msg.content)
                    }
                    // tool_calls (assistant 消息)
                    if (msg.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            msg.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    })
                                })
                            }
                        })
                    }
                    // tool 角色消息
                    if (msg.role == "tool") {
                        put("tool_call_id", msg.toolCallId)
                        msg.name?.let { put("name", it) }
                    }
                })
            }
        })
        put("stream", true)
        // stream_options: 请求 usage 统计（部分 API 不支持，仅对已知兼容的 provider 添加）
        if (providerId !in listOf("anthropic", "moonshot")) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        })
                    })
                }
            })
        }
        // reasoning_effort 仅对支持思考的模型添加（OpenAI o系列、DeepSeek R1等）
        // 大多数 OpenAI 兼容 API 不认识此字段会返回 400
        thinkingLevel?.takeIf { it.isNotBlank() }?.let {
            if (providerId in listOf("openai-generic", "deepseek")) {
                put("reasoning_effort", it)
            }
        }
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
        topP?.let { put("top_p", it) }
    }

    /** SSE 流读取 + 解析 — pendingToolCalls 由调用方传入，在重试间共享 */
    private fun readSSEStream(connection: HttpURLConnection, pendingToolCalls: MutableMap<Int, MutableMap<String, String>>): StreamReadResult {
        val events = mutableListOf<StreamEvent>()
        var lastUsage: TokenUsage? = null

        try {
            connection.inputStream.bufferedReader().use { reader ->
                val sseBuffer = StringBuilder()

                while (true) {
                    val line = reader.readLine() ?: break

                    // SSE 协议：空行表示一个 event 结束
                    if (line.isEmpty()) {
                        if (sseBuffer.isNotEmpty()) {
                            val parsed = parseSSEEvent(sseBuffer.toString(), pendingToolCalls)
                            // 拦截 usage 信息，确保流结束时仍可携带
                            if (parsed is StreamEvent.Finish) {
                                lastUsage = parsed.usage.takeIf { it.totalTokens > 0 } ?: lastUsage
                            }
                            parsed?.let { events.add(it) }
                            // 收到 Finish 事件，结束读取
                            if (parsed is StreamEvent.Finish) return StreamReadResult(events)
                            if (parsed is StreamEvent.Error) return StreamReadResult(events)
                            sseBuffer.clear()
                        }
                        continue
                    }

                    // 跳过注释行（以 : 开头的 keep-alive）
                    if (line.startsWith(":")) continue

                    sseBuffer.appendLine(line)
                }

                // 流结束时处理剩余 buffer
                if (sseBuffer.isNotEmpty()) {
                    val parsed = parseSSEEvent(sseBuffer.toString(), pendingToolCalls)
                    if (parsed is StreamEvent.Finish) {
                        lastUsage = parsed.usage.takeIf { it.totalTokens > 0 } ?: lastUsage
                    }
                    parsed?.let { events.add(it) }
                }

                // 流自然结束但没收到 [DONE] 或 finish_reason
                if (events.none { it is StreamEvent.Finish || it is StreamEvent.Error }) {
                    val finalToolCalls = pendingToolCalls.toSortedMap().values.mapNotNull { m ->
                        val id = m["id"] ?: return@mapNotNull null
                        val name = m["name"] ?: return@mapNotNull null
                        ToolCallDto(id, name, m["arguments"] ?: "{}")
                    }
                    events.add(StreamEvent.Finish(
                        if (finalToolCalls.isNotEmpty()) "tool_calls" else "stop",
                        finalToolCalls,
                        lastUsage ?: TokenUsage()
                    ))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            events.add(StreamEvent.Error(e.message ?: e::class.simpleName ?: "网络错误", null, isRetryableException(e)))
        }

        return StreamReadResult(events)
    }

    /** 解析单个 SSE event */
    private fun parseSSEEvent(
        rawData: String,
        pendingToolCalls: MutableMap<Int, MutableMap<String, String>>
    ): StreamEvent? {
        // 提取 data: 字段（可能有多行 data:，需拼接）
        val dataLines = rawData.lines().filter { it.startsWith("data:") }
        if (dataLines.isEmpty()) return null

        // SSE 规范：多个 data: 行应用 \n 拼接（不是空字符串），否则多行 JSON 会被错误合并。
        val data = dataLines.joinToString("\n") { it.removePrefix("data:").trim() }
        if (data == "[DONE]") {
            val finalToolCalls = pendingToolCalls.toSortedMap().values.mapNotNull { m ->
                val id = m["id"] ?: return@mapNotNull null
                val name = m["name"] ?: return@mapNotNull null
                ToolCallDto(id, name, m["arguments"] ?: "{}")
            }
            return StreamEvent.Finish(
                if (finalToolCalls.isNotEmpty()) "tool_calls" else "stop",
                finalToolCalls
            )
        }

        val chunk = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) { return null }

        // 提取 usage（stream_options.include_usage 返回）。
        // 注意：JSON null 在 kotlinx.serialization 中是 JsonNull 实例而非 Kotlin null，
        // 不能写 x?["usage"]?.jsonObject —— 值为 null 时不会短路、会抛
        // "JsonNull is not a JsonObject" 中断整个 SSE 流。这里统一用
        // as? JsonXxx 安全转换处理。
        val usage = (chunk["usage"] as? JsonObject)?.let { u ->
            TokenUsage(
                promptTokens = (u["prompt_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                completionTokens = (u["completion_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                totalTokens = (u["total_tokens"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            )
        }

        val choices = chunk["choices"] as? JsonArray
        // 空 choices（keep-alive usage 帧）— 返回 usage 作为 Finish 事件
        if (choices.isNullOrEmpty()) {
            if (usage != null && pendingToolCalls.isEmpty()) {
                // usage 帧通常是流中最后一个 chunk，标记为 stop
                return StreamEvent.Finish("stop", emptyList(), usage)
            }
            return null
        }

        val choice = choices.firstOrNull() as? JsonObject ?: return null
        val delta = choice["delta"] as? JsonObject ?: return null
        val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull

        // 文本增量
        ((delta["content"] as? JsonPrimitive)?.contentOrNull)?.let { text ->
            if (text.isNotEmpty()) return StreamEvent.TextDelta(text)
        }

        // 思考/推理增量 — 兼容多种字段名
        ((delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull)?.let { text ->
            if (text.isNotEmpty()) return StreamEvent.ThinkingDelta(text)
        }
        ((delta["thinking"] as? JsonPrimitive)?.contentOrNull)?.let { text ->
            if (text.isNotEmpty()) return StreamEvent.ThinkingDelta(text)
        }
        // DeepSeek R1 风格: reasoning_content 在 message 级别
        ((chunk["reasoning_content"] as? JsonPrimitive)?.contentOrNull)?.let { text ->
            if (text.isNotEmpty()) return StreamEvent.ThinkingDelta(text)
        }

        // 工具调用增量（DeepSeek 的 thinking 帧可能携带空的 tool_calls 数组，
        // 元素本身也可能为 null，需逐个空安全处理）
        (delta["tool_calls"] as? JsonArray)?.forEach { tc ->
            val tcObj = tc as? JsonObject ?: return@forEach
            val index = (tcObj["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val entry = pendingToolCalls.getOrPut(index) { mutableMapOf() }
            ((tcObj["id"] as? JsonPrimitive)?.contentOrNull)?.let { entry["id"] = it }
            (tcObj["function"] as? JsonObject)?.let { fn ->
                ((fn["name"] as? JsonPrimitive)?.contentOrNull)?.let { entry["name"] = it }
                ((fn["arguments"] as? JsonPrimitive)?.contentOrNull)?.let { entry["arguments"] = (entry["arguments"] ?: "") + it }
            }
        }

        // finish_reason
        if (finishReason != null) {
            val finalToolCalls = pendingToolCalls.toSortedMap().values.mapNotNull { m ->
                val id = m["id"] ?: return@mapNotNull null
                val name = m["name"] ?: return@mapNotNull null
                ToolCallDto(id, name, m["arguments"] ?: "{}")
            }
            return StreamEvent.Finish(finishReason, finalToolCalls, usage ?: TokenUsage())
        }

        return null
    }

    /**
     * 解析 API 错误响应，提取人类可读的错误消息。
     * 兼容 OpenAI / Anthropic / Google / 通用格式。
     */
    private fun parseApiError(body: String, httpCode: Int): String = try {
        val parsed = json.parseToJsonElement(body).jsonObject

        // OpenAI / Anthropic / Google 统一格式: { "error": { "message": "..." } }
        ((parsed["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull?.let { return it }

        // OpenAI 变体: { "error": { "code": "...", "detail": "..." } }
        ((parsed["error"] as? JsonObject)?.get("detail") as? JsonPrimitive)?.contentOrNull?.let { return it }

        // Generic: { "message": "..." }
        (parsed["message"] as? JsonPrimitive)?.contentOrNull?.let { return it }

        // Generic: { "detail": "..." }
        (parsed["detail"] as? JsonPrimitive)?.contentOrNull?.let { return it }

        "HTTP $httpCode: $body"
    } catch (_: Exception) {
        "HTTP $httpCode: ${body.take(500)}"
    }

    /** SSE 流读取结果 */
    private data class StreamReadResult(val events: List<StreamEvent>)
}

/**
 * 规整消息历史中的 tool_calls ↔ tool 配对，保证发送给 API 的历史合法。
 *
 * OpenAI 兼容接口要求：assistant 消息若带 tool_calls，其后必须紧跟覆盖
 * 每个 tool_call_id 的 tool 消息，否则报
 * "An assistant message with 'tool_calls' must be followed by tool messages..."。
 *
 * 悬挂轮次可能来自：工具执行中途被中止/异常、会话历史截断/摘要压缩、
 * 或崩溃时持久化的不完整历史。此处按“工具轮”分组清洗：
 * - assistant(toolCalls) 与其后连续的 tool 消息组成一轮；
 * - 完整轮（每个 id 恰好被响应一次）原样保留；
 * - 不完整轮：assistant 若带正文则降级为纯文本消息，其 tool 消息全部丢弃；
 * - 孤立的 tool 消息（前面没有声明它的 assistant）直接丢弃。
 */
internal fun normalizeToolPairing(
    messages: List<ChatHttpClient.ChatMessageDto>
): List<ChatHttpClient.ChatMessageDto> {
    val out = mutableListOf<ChatHttpClient.ChatMessageDto>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        if (msg.role == "assistant" && msg.toolCalls.isNotEmpty()) {
            // 收集紧随其后的连续 tool 消息
            var j = i + 1
            val responded = LinkedHashSet<String>()
            val keptTools = mutableListOf<ChatHttpClient.ChatMessageDto>()
            while (j < messages.size && messages[j].role == "tool") {
                val id = messages[j].toolCallId
                if (id != null && responded.add(id)) keptTools += messages[j]
                j++
            }
            val complete = msg.toolCalls.isNotEmpty() &&
                msg.toolCalls.all { it.id in responded }
            if (complete) {
                out += msg
                out += keptTools
            } else if (!msg.content.isNullOrBlank()) {
                // 不完整工具轮：保留正文，去掉悬空的 tool_calls
                out += msg.copy(toolCalls = emptyList())
            }
            i = j
        } else {
            // 孤立 tool（前面没有声明它的 assistant）→ 丢弃
            if (msg.role != "tool") out += msg
            i++
        }
    }
    return out
}
