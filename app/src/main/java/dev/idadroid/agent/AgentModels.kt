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
    val cwd: String = "",
    val provider: String? = null,
    val model: String? = null,
    val thinkingLevel: String? = null,
    val autoCompactionEnabled: Boolean? = null,
    val sessionFile: String? = null,
    val hasPersistedHistory: Boolean? = null,
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

@Serializable
data class ConfigExport(
    val defaultProvider: String = "",
    val defaultModel: String = "",
    val defaultThinkingLevel: String = "medium",
    val enabledModels: String = "",
    val settingsText: String = "{}",
    val modelsText: String = "{}",
    val envText: String = "{}",
    val appendSystem: String = "",
    val extraArgsText: String = "",
    val version: Int = 1,
    val exportedAt: Long = 0L
)

data class PiConfigSnapshot(
    val defaultProvider: String = "",
    val defaultModel: String = "",
    val defaultThinkingLevel: String = "medium",
    val enabledModels: String = "",
    val settingsText: String = "{}",
    val modelsText: String = "{}",
    val envText: String = "{}",
    val appendSystem: String = "",
    val extraArgsText: String = "",
    val materializedDir: String = "",
    val modelCatalog: AgentModelCatalog = AgentModelCatalog()
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
    val piConfig: PiConfigSnapshot = PiConfigSnapshot(),
    val workspace: WorkspaceState = WorkspaceState(),
    /** 细粒度处理状态，参考 Operit 的 InputProcessingState 设计。
     *  - null: 空闲
     *  - "connecting": 正在连接 Agent 进程 / 发送 prompt
     *  - "receiving": 已收到首个 delta，正在接收流式响应
     *  - "executing_tool": 正在执行工具调用
     *  - "compacting": 正在压缩上下文 */
    val processingPhase: String? = null,
    /** prompt 发送时间戳，用于计算等待时长 */
    val promptSentAt: Long = 0L,
    /** 首个 delta 到达时间戳 */
    val firstDeltaAt: Long = 0L
) {
    val activeSession: AgentSessionRecord? get() = sessions.firstOrNull { it.id == activeSessionId }
    val activeSessionHasConfiguredModel: Boolean get() = activeSession?.let { !it.provider.isNullOrBlank() && !it.model.isNullOrBlank() } == true
    val agentConfigReady: Boolean get() = piConfig.modelCatalog.parseError == null &&
        (piConfig.modelCatalog.isUsable || (piConfig.defaultProvider.isNotBlank() && piConfig.defaultModel.isNotBlank()) || activeSessionHasConfiguredModel || sessionModels.isNotEmpty())
    val canUseActiveSession: Boolean get() = agentConfigReady && activeSessionHasConfiguredModel
    val isWorking: Boolean get() = turnActive || status == "working"
    val canSend: Boolean get() = activeSessionId != null && canUseActiveSession && !isWorking && status != "starting"
    /** 当前正在流式输出的助手消息 ID（用于 UI 渲染闪烁游标）。 */
    val streamingMessageId: String? get() = if (isWorking) messages.lastOrNull { it.role == "assistant" }?.id else null
    /** 等待时长（毫秒），从 prompt 发出到首个 delta 到达 */
    val waitDurationMs: Long get() = if (promptSentAt > 0L && firstDeltaAt > 0L) firstDeltaAt - promptSentAt else 0L
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

