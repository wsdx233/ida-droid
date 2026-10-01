package dev.idadroid.agent

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Lossless, fail-closed conversion. The source is never edited, renamed, or deleted. */
internal object PiSessionMigration {
    fun migrate(source: File, destination: File, cwd: String, provider: String?, model: String?): File {
        val records = source.useLines { lines ->
            lines.filter { it.isNotBlank() }.mapIndexed { index, line ->
                try { Json.parseToJsonElement(line) as? JsonObject ?: error("object required") }
                catch (_: Exception) { error("History migration failed at line ${index + 1}; original preserved") }
            }.toList()
        }
        if (records.firstOrNull()?.get("type")?.jsonPrimitive?.contentOrNull == "session") return source
        require(records.isNotEmpty()) { "History file is empty; original preserved" }
        // Convert everything before creating any output; unknown/malformed entries are never skipped.
        val now = System.currentTimeMillis()
        val messages = records.mapIndexed { index, record -> convert(record, now + index, provider, model) }
        val timestamp = Instant.ofEpochMilli(now).toString()
        var parent: String? = null
        val entries = buildList {
            add(buildJsonObject {
                put("type", "session"); put("version", 3); put("id", UUID.randomUUID().toString())
                put("timestamp", timestamp); put("cwd", cwd)
            })
            messages.forEach { message ->
                val id = UUID.randomUUID().toString()
                add(buildJsonObject {
                    put("type", "message"); put("id", id); put("parentId", parent?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("timestamp", timestamp); put("message", message)
                })
                parent = id
            }
        }
        destination.parentFile?.mkdirs()
        require(!destination.exists()) { "Migration destination already exists; original preserved" }
        val temporary = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output ->
                entries.forEach { output.write((it.toString() + "\n").toByteArray(Charsets.UTF_8)) }
                output.fd.sync()
            }
            check(temporary.renameTo(destination)) { "Cannot commit migrated history; original preserved" }
        } finally { temporary.delete() }
        return destination
    }

    private fun convert(record: JsonObject, timestamp: Long, provider: String?, model: String?): JsonObject {
        val role = record["role"]?.jsonPrimitive?.contentOrNull
        require(role in setOf("user", "assistant", "tool", "system")) { "Unknown native history record; original preserved" }
        val text = (record["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val content = buildJsonArray {
            if (text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", text) })
            (record["images"] as? JsonArray).orEmpty().forEach { image ->
                val uri = image.jsonPrimitive.content
                val match = Regex("^data:([^;]+);base64,(.*)$", RegexOption.DOT_MATCHES_ALL).matchEntire(uri)
                    ?: error("Invalid native image; original preserved")
                add(buildJsonObject { put("type", "image"); put("mimeType", match.groupValues[1]); put("data", match.groupValues[2]) })
            }
            (record["toolCalls"] as? JsonArray).orEmpty().forEach { element ->
                val tool = element.jsonObject
                val args = Json.parseToJsonElement(tool.getValue("arguments").jsonPrimitive.content)
                require(args is JsonObject) { "Invalid native tool arguments; original preserved" }
                add(buildJsonObject {
                    put("type", "toolCall"); put("id", tool.getValue("id")); put("name", tool.getValue("name")); put("arguments", args)
                })
            }
        }
        return buildJsonObject {
            put("role", when (role) { "tool" -> "toolResult"; "system" -> "custom"; else -> role!! })
            put("content", content); put("timestamp", timestamp)
            when (role) {
                "assistant" -> {
                    // Metadata identifies imported native history, not a newly generated PI response.
                    put("api", "openai-completions"); put("provider", provider ?: "native-import")
                    put("model", model ?: "native-import"); put("stopReason", if ((record["toolCalls"] as? JsonArray).isNullOrEmpty()) "stop" else "toolUse")
                    put("usage", buildJsonObject {
                        listOf("input", "output", "cacheRead", "cacheWrite", "totalTokens").forEach { put(it, 0) }
                        put("cost", buildJsonObject { listOf("input", "output", "cacheRead", "cacheWrite", "total").forEach { put(it, 0) } })
                    })
                }
                "tool" -> {
                    put("toolCallId", record["toolCallId"]?.takeUnless { it is JsonNull } ?: error("Missing native tool ID; original preserved"))
                    put("toolName", record["name"]?.takeUnless { it is JsonNull } ?: JsonPrimitive("tool")); put("isError", false)
                }
                "system" -> { put("customType", "idadroid.native-history"); put("display", true) }
            }
        }
    }
}
