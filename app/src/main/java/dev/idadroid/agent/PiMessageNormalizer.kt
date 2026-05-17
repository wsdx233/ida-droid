package dev.idadroid.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

private val normalizerJson = Json { prettyPrint = true; ignoreUnknownKeys = true; explicitNulls = false }

fun normalizePiMessages(messages: List<JsonElement>): List<ChatMessage> {
    val toolCalls = mutableMapOf<String, Int>()
    val out = mutableListOf<ChatMessage>()
    messages.forEachIndexed { idx, element ->
        val rawObj = element.asObject() ?: return@forEachIndexed
        if (shouldSkipSessionRecord(rawObj)) return@forEachIndexed
        val obj = unwrapMessageRecord(rawObj)
        val timestamp = obj.timestampMillis("timestamp") ?: rawObj.timestampMillis("timestamp") ?: System.currentTimeMillis()
        when (obj.string("role")) {
            "user" -> {
                val content = obj["content"]
                val images = attachmentsFromContent(content)
                val expanded = extractInlineFileBlocks(contentToText(content).ifBlank { obj.string("message") ?: "" })
                val attachments = images + expanded.attachments
                out += ChatMessage(
                    id = "$idx-$timestamp-user",
                    role = "user",
                    text = expanded.text.ifBlank { attachmentSummary(attachments) },
                    attachments = attachments,
                    timestamp = timestamp
                )
            }
            "assistant" -> {
                val parts = contentParts(obj["content"])
                val summary = obj.string("summary")?.let { "[summary]\n$it" }.orEmpty()
                if (parts.text.isNotBlank() || parts.thinking.isNotBlank() || summary.isNotBlank()) {
                    out += ChatMessage(
                        id = "$idx-$timestamp-assistant",
                        role = "assistant",
                        text = parts.text.ifBlank { summary },
                        thinking = parts.thinking.ifBlank { null },
                        timestamp = timestamp
                    )
                }
                parts.tools.forEachIndexed { toolIdx, tool ->
                    out += ChatMessage(
                        id = "$idx-$timestamp-tool-$toolIdx",
                        role = "tool",
                        text = "",
                        timestamp = timestamp,
                        toolCallId = tool.toolCallId,
                        toolName = tool.toolName,
                        toolArgs = tool.toolArgs,
                        toolStatus = "pending"
                    )
                    tool.toolCallId?.let { toolCalls[it] = out.lastIndex }
                }
            }
            else -> {
                val looksLikeTool = obj.string("tool_call_id") != null || obj.string("toolCallId") != null ||
                    obj.string("name") != null || obj.string("toolName") != null || obj["result"] != null ||
                    obj.string("type")?.lowercase()?.contains("tool") == true
                if (!looksLikeTool) {
                    val role = obj.string("role") ?: return@forEachIndexed
                    val text = contentToText(obj["content"]).ifBlank { obj.string("text") ?: obj.string("message") ?: pretty(obj) }
                    if (text.isNotBlank()) {
                        out += ChatMessage(
                            id = "$idx-$timestamp-${obj.string("type") ?: "message"}",
                            role = role,
                            text = text,
                            timestamp = timestamp
                        )
                    }
                    return@forEachIndexed
                }
                val callId = obj.string("tool_call_id") ?: obj.string("toolCallId") ?: obj.string("id") ?: "$idx-$timestamp"
                val priorIndex = toolCalls[callId]
                val prior = priorIndex?.let { out[it] }
                val msg = ChatMessage(
                    id = prior?.id ?: "$idx-$timestamp-tool-result",
                    role = "tool",
                    text = "",
                    toolCallId = callId,
                    toolName = obj.string("name") ?: obj.string("toolName") ?: prior?.toolName ?: "tool",
                    toolArgs = obj["args"] ?: obj["arguments"] ?: prior?.toolArgs,
                    toolStatus = if (obj.boolean("isError") == true) "error" else "done",
                    toolResult = contentToText(obj["content"]).ifBlank { resultToText(obj["result"]).ifBlank { pretty(element) } },
                    toolResultMeta = toolResultMeta(obj["result"] ?: obj["content"]),
                    timestamp = timestamp
                )
                if (priorIndex != null) out[priorIndex] = msg else out += msg
            }
        }
    }
    return out
}

data class ContentParts(val text: String, val thinking: String, val tools: List<ToolPart>)
data class ToolPart(val toolCallId: String?, val toolName: String?, val toolArgs: JsonElement?)

fun shouldSkipSessionRecord(obj: JsonObject): Boolean {
    val type = obj.string("type")?.lowercase().orEmpty()
    return type in setOf(
        "session",
        "session_info",
        "model_change",
        "thinking_level_change",
        "label",
        "branch_summary"
    ) || (type == "custom" && obj.string("customType") == "boxedagent.tree_leaf")
}

fun unwrapMessageRecord(obj: JsonObject): JsonObject {
    if (obj.string("role") != null) return obj
    val type = obj.string("type")?.lowercase().orEmpty()
    val keys = when (type) {
        "message", "message_record", "conversation_message" -> listOf("message", "data", "payload", "record")
        "agent_message", "chat_message" -> listOf("message", "data", "payload")
        else -> listOf("message")
    }
    for (key in keys) {
        val inner = obj.obj(key) ?: continue
        if (inner.string("role") != null || inner.string("type") == "message") return unwrapMessageRecord(inner)
    }
    return obj
}

fun contentParts(content: JsonElement?): ContentParts {
    if (content == null) return ContentParts("", "", emptyList())
    if (content is JsonPrimitive) return ContentParts(content.contentOrNull.orEmpty(), "", emptyList())
    if (content is JsonObject) {
        val thinking = if (isThinkingContentPart(content)) content.string("thinking") ?: content.string("reasoning") ?: content.string("summary") ?: "" else ""
        val text = content.string("text") ?: content.string("message") ?: ""
        val tool = findToolCall(content)
        return ContentParts(text, thinking, listOfNotNull(tool))
    }
    val arr = content as? JsonArray ?: return ContentParts(pretty(content), "", emptyList())
    val text = mutableListOf<String>()
    val thinking = mutableListOf<String>()
    val tools = mutableListOf<ToolPart>()
    arr.forEach { part ->
        if (part is JsonPrimitive) {
            part.contentOrNull?.let { text += it }
            return@forEach
        }
        val obj = part.asObject() ?: return@forEach
        when {
            isImageContentPart(obj) -> Unit
            isThinkingContentPart(obj) -> thinking += (obj.string("thinking") ?: obj.string("reasoning") ?: obj.string("summary") ?: obj.string("text") ?: "")
            isToolContentPart(obj) -> tools += ToolPart(
                toolCallId = obj.string("id") ?: obj.string("toolCallId") ?: obj.string("tool_call_id"),
                toolName = obj.string("name") ?: obj.string("toolName") ?: "tool",
                toolArgs = obj["args"] ?: obj["arguments"] ?: obj["input"]
            )
            obj.string("type") == "text" || obj.containsKey("text") -> text += (obj.string("text") ?: "")
            else -> Unit
        }
    }
    return ContentParts(
        text.filter { it.isNotBlank() }.joinToString("\n\n"),
        thinking.filter { it.isNotBlank() }.joinToString("\n\n"),
        tools
    )
}

fun contentToText(content: JsonElement?): String {
    val parts = contentParts(content)
    return listOfNotNull(
        parts.thinking.takeIf { it.isNotBlank() }?.let { "思考：\n$it" },
        parts.text.takeIf { it.isNotBlank() }
    ).joinToString("\n\n")
}

fun attachmentsFromContent(content: JsonElement?): List<ChatAttachment.Image> {
    val arr = content as? JsonArray ?: return emptyList()
    return arr.mapIndexedNotNull { idx, part ->
        val obj = part.asObject() ?: return@mapIndexedNotNull null
        if (!isImageContentPart(obj)) return@mapIndexedNotNull null
        val data = obj.string("data") ?: obj.string("imageData") ?: obj.obj("source")?.string("data") ?: return@mapIndexedNotNull null
        val mime = obj.string("mimeType") ?: obj.string("mediaType") ?: obj.obj("source")?.string("media_type") ?: "image/png"
        ChatAttachment.Image(obj.string("name") ?: "image-${idx + 1}", mime, data.removePrefixDataUrl())
    }
}

data class ExtractedFiles(val text: String, val attachments: List<ChatAttachment.File>)

fun extractInlineFileBlocks(text: String): ExtractedFiles {
    val attachments = mutableListOf<ChatAttachment.File>()
    val regex = Regex("<file\\s+name=[\"']([^\"']+)[\"']>[\\s\\S]*?</file>\\n?")
    val stripped = regex.replace(text) { match ->
        val path = match.groupValues[1]
        attachments += ChatAttachment.File(name = path.split('/').filter { it.isNotBlank() }.lastOrNull() ?: path, path = path)
        ""
    }.trimStart()
    return ExtractedFiles(stripped, attachments)
}

fun isImageContentPart(obj: JsonObject): Boolean {
    val type = obj.string("type")?.lowercase().orEmpty()
    val mime = obj.string("mimeType")
    return type in setOf("image", "image_url", "input_image") || obj.containsKey("imageData") || mime?.startsWith("image/") == true || obj.obj("source")?.string("type") == "base64"
}

fun isThinkingContentPart(obj: JsonObject): Boolean {
    val type = obj.string("type")?.lowercase().orEmpty()
    return type in setOf("thinking", "reasoning", "reasoning_delta", "redacted_thinking") ||
        obj.containsKey("thinking") || obj.containsKey("reasoning") ||
        (type == "summary" && obj.containsKey("text"))
}

fun isToolContentPart(obj: JsonObject): Boolean {
    val type = obj.string("type")?.lowercase().orEmpty()
    if (isThinkingContentPart(obj)) return false
    return type in setOf("toolcall", "tool_call", "tool-call", "tool_use", "tooluse") ||
        ((obj.containsKey("name") || obj.containsKey("toolName")) && (obj.containsKey("args") || obj.containsKey("arguments") || obj.containsKey("input")))
}

fun toolCallFromDelta(delta: JsonObject): ToolPart {
    findToolCall(delta["toolCall"])?.let { return it }
    findToolCall(delta["tool_call"])?.let { return it }
    findToolCall(delta["content"], delta.int("contentIndex"))?.let { return it }
    findToolCall(delta["partial"])?.let { return it }
    findToolCall(delta["message"], delta.int("contentIndex"))?.let { return it }
    return ToolPart(delta.string("toolCallId"), delta.string("toolName"), null)
}

fun findToolCall(value: JsonElement?, contentIndex: Int? = null): ToolPart? {
    if (value == null) return null
    if (value is JsonArray) {
        if (contentIndex != null) findToolCall(value.getOrNull(contentIndex))?.let { return it }
        value.forEach { findToolCall(it)?.let { found -> return found } }
        return null
    }
    val obj = value.asObject() ?: return null
    obj["content"]?.let { findToolCall(it, contentIndex)?.let { found -> return found } }
    if (!isToolContentPart(obj)) return null
    return ToolPart(
        toolCallId = obj.string("id") ?: obj.string("toolCallId") ?: obj.string("tool_call_id"),
        toolName = obj.string("name") ?: obj.string("toolName") ?: "tool",
        toolArgs = obj["args"] ?: obj["arguments"] ?: obj["input"]
    )
}

fun resultToText(result: JsonElement?): String {
    if (result == null) return ""
    if (result is JsonPrimitive) return result.contentOrNull.orEmpty()
    val obj = result as? JsonObject
    val content = obj?.get("content")
    if (content is JsonArray) return content.joinToString("\n") { item ->
        val io = item.asObject()
        io?.string("text") ?: io?.string("content") ?: pretty(item)
    }
    obj?.string("text")?.let { return it }
    return pretty(result)
}

fun toolResultMeta(result: JsonElement?): ToolResultMeta? {
    if (result == null) return null
    val records = collectRecords(result)
    val text = if (result is JsonPrimitive) result.contentOrNull.orEmpty() else resultToText(result)
    var totalLines = numericMeta(records, "totalLines", "total_lines", "lineCount", "line_count", "totalLineCount", "total_line_count", "linesTotal")
    var shownLines = numericMeta(records, "shownLines", "shown_lines", "displayedLines", "displayed_lines", "returnedLines", "returned_lines", "visibleLines", "visible_lines", "outputLines", "output_lines")
    var omittedLines = numericMeta(records, "omittedLines", "omitted_lines", "truncatedLines", "truncated_lines", "remainingLines", "remaining_lines")
    val totalBytes = numericMeta(records, "totalBytes", "total_bytes", "byteLength", "byte_length", "size", "totalSize", "total_size")
    val shownBytes = numericMeta(records, "shownBytes", "shown_bytes", "displayedBytes", "displayed_bytes", "returnedBytes", "returned_bytes", "outputBytes", "output_bytes")
    var truncated = booleanMeta(records, "truncated", "isTruncated", "is_truncated", "wasTruncated", "was_truncated")
    val label = stringMeta(records, "label", "title", "summary")
    Regex("(?:omitted|省略)\\s*([0-9,]+)\\s*(?:more\\s*)?(?:lines?|行)", RegexOption.IGNORE_CASE).find(text)?.let { match ->
        if (omittedLines == null) omittedLines = match.groupValues[1].replace(",", "").toLongOrNull()
    }
    Regex("(?:total|共)\\s*([0-9,]+)\\s*(?:lines?|行)", RegexOption.IGNORE_CASE).find(text)?.let { match ->
        if (totalLines == null) totalLines = match.groupValues[1].replace(",", "").toLongOrNull()
    }
    if (truncated == null && Regex("\\b(truncated|omitted)\\b|截断|省略", RegexOption.IGNORE_CASE).containsMatchIn(text)) truncated = true
    if (omittedLines != null && truncated != true) truncated = true
    if (truncated == null && totalLines == null && shownLines == null && omittedLines == null && totalBytes == null && shownBytes == null && label == null) return null
    return ToolResultMeta(truncated, totalLines, shownLines, omittedLines, totalBytes, shownBytes, label)
}

private fun collectRecords(value: JsonElement, out: MutableList<JsonObject> = mutableListOf(), depth: Int = 0): List<JsonObject> {
    if (depth > 3) return out
    when (value) {
        is JsonObject -> {
            out += value
            listOf("metadata", "meta", "stats", "summary", "details", "truncation", "content").forEach { key ->
                value[key]?.let { collectRecords(it, out, depth + 1) }
            }
        }
        is JsonArray -> value.take(20).forEach { collectRecords(it, out, depth + 1) }
        else -> Unit
    }
    return out
}

private fun numericMeta(records: List<JsonObject>, vararg keys: String): Long? {
    for (record in records) for (key in keys) {
        val value = (record[key] as? JsonPrimitive)?.contentOrNull?.replace(",", "")?.trim() ?: continue
        val number = value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: continue
        return number.toLong()
    }
    return null
}

private fun booleanMeta(records: List<JsonObject>, vararg keys: String): Boolean? {
    for (record in records) for (key in keys) {
        when ((record[key] as? JsonPrimitive)?.contentOrNull?.trim()) {
            "true", "TRUE", "True" -> return true
            "false", "FALSE", "False" -> return false
        }
    }
    return null
}

private fun stringMeta(records: List<JsonObject>, vararg keys: String): String? {
    for (record in records) for (key in keys) {
        val value = (record[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: continue
        return value.take(120)
    }
    return null
}

fun attachmentSummary(attachments: List<ChatAttachment>): String {
    val imageCount = attachments.count { it is ChatAttachment.Image }
    val fileCount = attachments.count { it is ChatAttachment.File }
    return listOfNotNull(
        imageCount.takeIf { it > 0 }?.let { "$it 张图片" },
        fileCount.takeIf { it > 0 }?.let { "$it 个文件" }
    ).joinToString("，").ifBlank { "[附件]" }
}

fun pretty(value: JsonElement?): String = value?.let { runCatching { normalizerJson.encodeToString(it) }.getOrDefault(it.toString()) }.orEmpty()
fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
fun JsonObject.timestampMillis(key: String): Long? {
    val raw = this[key]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
    return raw.toLongOrNull() ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}
fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arrayStrings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
fun newMessageId(): String = UUID.randomUUID().toString()
fun String.removePrefixDataUrl(): String = replace(Regex("^data:[^,]+,"), "")
