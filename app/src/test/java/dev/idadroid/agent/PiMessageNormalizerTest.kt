package dev.idadroid.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PiMessageNormalizerTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

    @Test
    fun unwrapsHistoryMessageRecordsInsteadOfTreatingThemAsTools() {
        val input = json.parseToJsonElement(
            """
            [
              {
                "type": "message",
                "timestamp": 123,
                "message": {
                  "role": "assistant",
                  "content": [{"type":"text","text":"历史回答"}]
                }
              }
            ]
            """.trimIndent()
        ) as JsonArray

        val messages = normalizePiMessages(input.toList())

        assertEquals(1, messages.size)
        assertEquals("assistant", messages[0].role)
        assertEquals("历史回答", messages[0].text)
        assertNull(messages[0].toolName)
    }

    @Test
    fun filtersSessionMetadataRecordsFromChat() {
        val input = json.parseToJsonElement(
            """
            [
              {
                "type": "thinking_level_change",
                "id": "e5647b44",
                "parentId": "50608480",
                "timestamp": "2026-05-15T04:05:43.742Z",
                "thinkingLevel": "high"
              },
              {
                "type": "model_change",
                "id": "50608480",
                "parentId": null,
                "timestamp": "2026-05-15T04:05:43.742Z",
                "provider": "WC",
                "modelId": "gpt-5.5"
              },
              {
                "type": "session",
                "version": 3,
                "id": "019e29cf-c88d-7263-b4bf-825a6c2d3ab5",
                "timestamp": "2026-05-15T04:05:43.695Z",
                "cwd": "/root/pi_workspace"
              }
            ]
            """.trimIndent()
        ) as JsonArray

        val messages = normalizePiMessages(input.toList())

        assertEquals(emptyList<ChatMessage>(), messages)
    }

    @Test
    fun doesNotRenderUnknownMessageWrapperAsToolCard() {
        val input = json.parseToJsonElement(
            """
            [
              {
                "type": "message",
                "timestamp": 123,
                "message": "plain event payload"
              }
            ]
            """.trimIndent()
        ) as JsonArray

        val messages = normalizePiMessages(input.toList())

        assertEquals(emptyList<ChatMessage>(), messages)
    }

    @Test
    fun unwrapsHistoryToolUseAndToolResultRecords() {
        val input = json.parseToJsonElement(
            """
            [
              {
                "type": "message",
                "timestamp": 1,
                "message": {
                  "role": "assistant",
                  "content": [
                    {"type":"text","text":"先读取文件"},
                    {"type":"tool_use","id":"tool-1","name":"read","input":{"path":"README.md"}}
                  ]
                }
              },
              {
                "type": "message",
                "timestamp": 2,
                "message": {
                  "role": "tool",
                  "tool_call_id": "tool-1",
                  "content": [{"type":"text","text":"# Title"}]
                }
              }
            ]
            """.trimIndent()
        ) as JsonArray

        val messages = normalizePiMessages(input.toList())

        assertEquals(2, messages.size)
        assertEquals("assistant", messages[0].role)
        assertEquals("tool", messages[1].role)
        assertEquals("read", messages[1].toolName)
        assertEquals("done", messages[1].toolStatus)
        assertEquals("# Title", messages[1].toolResult)
    }
}
