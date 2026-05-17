package dev.idadroid.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AgentSessionRecord(
    val id: String = "",
    val name: String = "",
    val status: String = "idle",
    val createdAt: String = "",
    val updatedAt: String = "",
    val lastActiveAt: String? = null,
    val cwd: String = "/root/pi_workspace",
    val provider: String? = null,
    val model: String? = null,
    val thinkingLevel: String? = null,
    val autoCompactionEnabled: Boolean? = null,
    val sessionFile: String? = null,
    val error: String? = null
)

@Serializable
data class AgentSessionStore(
    val sessions: List<AgentSessionRecord> = emptyList(),
    val activeSessionId: String? = null
)

@Serializable
data class ImagePayload(
    val type: String = "image",
    val data: String,
    val mimeType: String
)

@Serializable
data class PiModel(
    val id: String = "",
    val provider: String? = null,
    val name: String? = null,
    val reasoning: Boolean? = null,
    val input: List<String>? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val providerId: String? = null,
    val providerName: String? = null
)

fun PiModel.providerNameOrNull(): String? = (provider ?: providerId ?: providerName)?.trim()?.takeIf { it.isNotEmpty() }

@Serializable
data class TokenStats(
    val input: Long? = null,
    val output: Long? = null,
    val cacheRead: Long? = null,
    val cacheWrite: Long? = null,
    val total: Long? = null
)

@Serializable
data class ContextUsage(
    val tokens: Long? = null,
    val contextWindow: Long? = null,
    val percent: Double? = null
)

@Serializable
data class SessionStats(
    val sessionFile: String? = null,
    val sessionId: String? = null,
    val userMessages: Int? = null,
    val assistantMessages: Int? = null,
    val toolCalls: Int? = null,
    val toolResults: Int? = null,
    val totalMessages: Int? = null,
    val tokens: TokenStats? = null,
    val cost: Double? = null,
    val contextUsage: ContextUsage? = null
)

@Serializable
data class FileEntry(
    val name: String = "",
    val path: String = "",
    val type: String = "file", // file / directory
    val size: Long = 0,
    val modifiedAt: String = ""
)

@Serializable
data class PiUserConfig(
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: String? = "medium",
    val enabledModels: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val extraArgs: List<String> = emptyList()
)

data class PiConfigSnapshot(
    val defaultProvider: String = "",
    val defaultModel: String = "",
    val defaultThinkingLevel: String = "medium",
    val enabledModels: String = "",
    val settingsText: String = "{}",
    val modelsText: String = "{}",
    val envText: String = "{}",
    val systemPrompt: String = "",
    val appendSystem: String = "",
    val agentsMd: String = "",
    val extraArgsText: String = "",
    val materializedDir: String = "/root/pi_workspace/.idadroid/pi-agent"
)

data class QueueState(
    val steering: List<String> = emptyList(),
    val followUp: List<String> = emptyList()
)

data class AgentUiState(
    val sessions: List<AgentSessionRecord> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val status: String = "idle",
    val error: String? = null,
    val activity: String = "",
    val stderrTail: String = "",
    val rawLogLines: List<String> = emptyList(),
    val modelLabel: String = "",
    val turnActive: Boolean = false,
    val messagesLoading: Boolean = false,
    val sessionModels: List<PiModel> = emptyList(),
    val modelLoading: Boolean = false,
    val activeStats: SessionStats? = null,
    val activeQueue: QueueState = QueueState(),
    val piConfig: PiConfigSnapshot = PiConfigSnapshot()
) {
    val activeSession: AgentSessionRecord? get() = sessions.firstOrNull { it.id == activeSessionId }
    val isWorking: Boolean get() = turnActive || status == "working"
    val canSend: Boolean get() = activeSessionId != null && !isWorking && status != "starting"
}

sealed interface ChatAttachment {
    val name: String

    data class Image(
        override val name: String,
        val mimeType: String,
        val data: String,
        val path: String? = null,
        val size: Long? = null
    ) : ChatAttachment

    data class File(
        override val name: String,
        val path: String,
        val size: Long? = null,
        val mimeType: String? = null
    ) : ChatAttachment
}

data class ToolResultMeta(
    val truncated: Boolean? = null,
    val totalLines: Long? = null,
    val shownLines: Long? = null,
    val omittedLines: Long? = null,
    val totalBytes: Long? = null,
    val shownBytes: Long? = null,
    val label: String? = null
)

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: Long,
    val attachments: List<ChatAttachment> = emptyList(),
    val thinking: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: JsonElement? = null,
    val toolResult: String? = null,
    val toolResultMeta: ToolResultMeta? = null,
    val toolStatus: String? = null
)

sealed interface PiRuntimeEvent {
    data class RpcEvent(val event: kotlinx.serialization.json.JsonObject) : PiRuntimeEvent
    data class RawStdout(val line: String) : PiRuntimeEvent
    data class Stderr(val text: String) : PiRuntimeEvent
    data class Status(val status: String, val error: String? = null) : PiRuntimeEvent
    data class Exited(val exitCode: Int?, val error: String? = null) : PiRuntimeEvent
}
