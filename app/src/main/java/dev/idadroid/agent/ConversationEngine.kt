package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.proot.IdaProotRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 对话引擎 — Agent Loop 的核心实现。
 *
 * 设计参考：
 * - Vercel AI SDK ToolLoopAgent: streamText + tool loop + prepareStep lifecycle
 * - OpenAI Agents SDK Runner.run(): simple while loop with tool execution
 * - LangGraph: state machine for explicit state transitions
 *
 * 架构分层：
 * - [ChatHttpClient] Layer 1: HTTPS SSE 流式客户端
 * - [ToolExecutor] Layer 2: 工具并行执行
 * - [ContextWindow] Layer 2.5: 上下文管理（token 估算、压缩、截断）
 * - [ConversationEngine] Layer 3: 编排以上三层，驱动 agent loop
 *
 * 核心循环：
 * 1. 追加用户消息 → trim context → 构建 API 请求
 * 2. SSE 流 → 文本/思考增量实时推送 → Finish
 * 3. 如果有工具调用 → 并行执行 → 追加 tool 结果 → 回到 2
 * 4. 如果无工具调用 → 对话结束
 *
 * 线程安全：
 * - 状态通过 MutableStateFlow (CAS 安全)
 * - 事件通过 MutableSharedFlow (buffered)
 * - 消息通过 ContextWindow 的 Mutex 保护
 * - abort 通过 cancel Job 传播
 */
class ConversationEngine(
    private val context: Context,
    private val paths: EnvironmentPaths,
    private val proot: IdaProotRuntime
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ══════════════════════════════════════════════════════════════
    // 状态 & 事件
    // ══════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<ConversationState>(ConversationState.Idle)
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<ConversationEvent> = _events.asSharedFlow()

    // ══════════════════════════════════════════════════════════════
    // 组件
    // ══════════════════════════════════════════════════════════════

    private val toolContext = ToolContext(proot, paths, dev.idadroid.settings.IdaDroidSettings(appContext))
    private val toolRegistry = ToolRegistry().apply {
        register(ShellTool())
        register(ReadFileTool())
        register(WriteFileTool())
        register(ListDirTool())
        register(SearchFilesTool())
        register(DeleteFileTool())
        register(FileInfoTool())
    }
    private val toolExecutor = ToolExecutor(toolRegistry, toolContext)

    // ══════════════════════════════════════════════════════════════
    // 会话状态
    // ══════════════════════════════════════════════════════════════

    private val sendMutex = Mutex()
    private var currentJob: Job? = null
    private var contextWindow: ContextWindow? = null
    private var currentConfig: ConversationConfig? = null

    /** 累计 token 用量 */
    @Volatile private var totalPromptTokens = 0
    @Volatile private var totalCompletionTokens = 0
    @Volatile private var totalTokens = 0

    /** 设置 compactor — PiAgentManager 设置后启用 LLM 摘要压缩 */
    var compactor: (suspend (List<ChatHttpClient.ChatMessageDto>) -> String?)? = null

    // ══════════════════════════════════════════════════════════════
    // 公共 API
    // ══════════════════════════════════════════════════════════════

    /** 当前消息历史 */
    suspend fun getMessages(): List<ChatHttpClient.ChatMessageDto> =
        contextWindow?.snapshot() ?: emptyList()

    /** 获取 token 用量 */
    fun getTokenUsage(): Triple<Int, Int, Int> =
        Triple(totalPromptTokens, totalCompletionTokens, totalTokens)

    /** 重置 token 计数器 */
    fun resetTokenUsage() {
        totalPromptTokens = 0
        totalCompletionTokens = 0
        totalTokens = 0
    }

    /** 重置对话（新会话） */
    suspend fun reset() = sendMutex.withLock {
        contextWindow = null
        currentConfig = null
        resetTokenUsage()
        _state.update { ConversationState.Idle }
    }

    /** 从已有消息历史恢复对话 */
    suspend fun restoreFromMessages(messages: List<ChatHttpClient.ChatMessageDto>, config: ConversationConfig) = sendMutex.withLock {
        val cw = ContextWindow(config.contextTokenLimit, compactor)
        cw.addAll(messages)
        contextWindow = cw
        currentConfig = config
        _state.update { ConversationState.Idle }
    }

    /**
     * 就地压缩消息列表 — 用于 autoCompact。
     * 不创建新 ContextWindow，直接替换消息列表。
     */
    suspend fun compactMessages(newMessages: List<ChatHttpClient.ChatMessageDto>): Boolean {
        val cw = contextWindow ?: return false
        cw.replaceAll(newMessages)
        return true
    }

    /**
     * 发送用户消息，启动 agent loop。
     *
     * 用 sendMutex 串行化 — 同一时间只允许一个 send()。
     * abort() 可从任意线程取消当前 send()。
     */
    suspend fun send(
        userText: String,
        images: List<String> = emptyList(),
        config: ConversationConfig,
        onEvent: (ConversationEvent) -> Unit
    ) = sendMutex.withLock {
        // 初始化或复用 context window
        val cw = getOrCreateContextWindow(config)
        contextWindow = cw
        currentConfig = config

        // 重置状态
        resetTokenUsage()
        _state.update { ConversationState.Idle }

        // 追加用户消息
        cw.add(ChatHttpClient.ChatMessageDto(
            role = "user",
            content = userText,
            images = images
        ))

        // 开始 agent loop
        runAgentLoop(cw, config, onEvent)
    }

    /** 中止当前对话 */
    fun abort() {
        currentJob?.cancel()
    }

    // ══════════════════════════════════════════════════════════════
    // 核心：Agent Loop
    // ══════════════════════════════════════════════════════════════

    private suspend fun runAgentLoop(
        cw: ContextWindow,
        config: ConversationConfig,
        onEvent: (ConversationEvent) -> Unit
    ) {
        val client = ChatHttpClient(config.baseUrl, config.apiKey, config.model, config.providerId)
        val tools = toolRegistry.definitions()
        var round = 0

        try {
            while (round < config.maxToolRounds) {
                // 检查 abort
                if (_state.value is ConversationState.Aborted) return

                // 1. 上下文管理 — 只在实际压缩时发射状态
                val trimResult = cw.trimIfNeeded()
                if (trimResult is TrimResult.Compacted) {
                    android.util.Log.i("ConversationEngine",
                        "上下文已压缩: 移除 ${trimResult.removedCount} 条消息, 节省 ${trimResult.savedTokens} tokens")
                }

                // 2. LLM 调用
                emit(ConversationEvent.StateChanged(ConversationState.Connecting), onEvent)
                _state.update { ConversationState.Connecting }

                val llmResult = streamLlmRound(client, cw, tools, config, onEvent)

                // 3. 错误处理
                if (llmResult.error != null) {
                    // 保存已收到的部分文本
                    if (llmResult.text.isNotBlank()) {
                        cw.add(ChatHttpClient.ChatMessageDto(
                            role = "assistant",
                            content = llmResult.text,
                            toolCalls = llmResult.toolCalls
                        ))
                    }
                    emit(ConversationEvent.Error(llmResult.error), onEvent)
                    _state.update { ConversationState.Failed(llmResult.error) }
                    emit(ConversationEvent.TurnComplete, onEvent)
                    return
                }

                // 4. 追加 assistant 消息
                cw.add(ChatHttpClient.ChatMessageDto(
                    role = "assistant",
                    content = llmResult.text.ifBlank { null },
                    toolCalls = llmResult.toolCalls
                ))

                // 5. 如果没有工具调用 → 完成
                if (llmResult.toolCalls.isEmpty()) {
                    _state.update { ConversationState.Done }
                    emit(ConversationEvent.TurnComplete, onEvent)
                    return
                }

                // 6. 执行工具
                val toolNames = llmResult.toolCalls.map { it.name }
                emit(ConversationEvent.StateChanged(
                    ConversationState.ExecutingTools(toolNames)), onEvent)
                _state.update { ConversationState.ExecutingTools(toolNames) }

                val executions = toolExecutor.execute(llmResult.toolCalls) { toolCallId, toolName, args, phase, outcome ->
                    if (phase == "start") {
                        emit(ConversationEvent.ToolCallStart(toolCallId, toolName, args), onEvent)
                    } else {
                        emit(ConversationEvent.ToolCallResult(
                            toolCallId, toolName, outcome.output, outcome.success), onEvent)
                    }
                }

                // 7. 追加 tool 结果（截断超长输出）
                executions.forEach { exec ->
                    val output = ContextWindow.truncateToolOutput(exec.outcome.output, config.contextTokenLimit)
                    cw.add(ChatHttpClient.ChatMessageDto(
                        role = "tool",
                        content = output,
                        toolCallId = exec.call.id,
                        name = exec.call.name
                    ))
                }

                round++
            }

            // 超过 maxToolRounds
            val error = ConversationError.MaxRoundsExceeded(round, config.maxToolRounds)
            emit(ConversationEvent.Error(error), onEvent)
            _state.update { ConversationState.Failed(error) }
            emit(ConversationEvent.TurnComplete, onEvent)

        } catch (e: CancellationException) {
            // 中止可能发生在工具执行中途 — 清掉历史中悬挂的不完整工具轮
            // （assistant 带 tool_calls 但无对应 tool 响应），否则下一轮请求会被 API 拒绝。
            // 注意：协程已处于取消状态，任何可取消挂起点（ContextWindow 的 mutex）都会
            // 立即抛 CancellationException，因此清理必须在 NonCancellable 上下文中完成。
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                pruneOrphanToolRound(cw)
            }
            _state.update { ConversationState.Aborted }
            emit(ConversationEvent.TurnComplete, onEvent)
            // 不 re-throw — send() 正常返回，状态已设置为 Aborted
        } catch (e: Exception) {
            val error = ConversationError.LlmError(
                e.message ?: e::class.simpleName ?: "未知错误",
                retriable = false
            )
            pruneOrphanToolRound(cw)
            emit(ConversationEvent.Error(error), onEvent)
            _state.update { ConversationState.Failed(error) }
            emit(ConversationEvent.TurnComplete, onEvent)
        }
    }

    /** 清理历史中悬挂的不完整工具轮（发送侧另有 normalizeToolPairing 兜底）。 */
    private suspend fun pruneOrphanToolRound(cw: ContextWindow) {
        val snapshot = cw.snapshot()
        val cleaned = normalizeToolPairing(snapshot)
        if (cleaned.size != snapshot.size) {
            cw.replaceAll(cleaned)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // LLM 流式调用
    // ══════════════════════════════════════════════════════════════

    private suspend fun streamLlmRound(
        client: ChatHttpClient,
        cw: ContextWindow,
        tools: List<ChatHttpClient.ToolDefinition>,
        config: ConversationConfig,
        onEvent: (ConversationEvent) -> Unit
    ): LlmRoundResult {
        val textBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        var toolCalls = emptyList<ChatHttpClient.ToolCallDto>()
        var finishReason: String? = null
        var usage: ChatHttpClient.TokenUsage? = null
        var error: ConversationError? = null
        var firstDelta = true

        try {
            client.chat(
                messages = cw.snapshot(),
                tools = tools,
                systemPrompt = config.systemPrompt,
                thinkingLevel = config.thinkingLevel,
                temperature = config.temperature,
                maxTokens = config.effectiveMaxTokens,
                topP = config.topP
            ).collect { event ->
                when (event) {
                    is ChatHttpClient.StreamEvent.TextDelta -> {
                        if (firstDelta) {
                            firstDelta = false
                            _state.update { ConversationState.Streaming }
                            emit(ConversationEvent.StateChanged(ConversationState.Streaming), onEvent)
                        }
                        textBuffer.append(event.text)
                        emit(ConversationEvent.TextDelta(event.text), onEvent)
                    }
                    is ChatHttpClient.StreamEvent.ThinkingDelta -> {
                        if (firstDelta) {
                            firstDelta = false
                            _state.update { ConversationState.Streaming }
                            emit(ConversationEvent.StateChanged(ConversationState.Streaming), onEvent)
                        }
                        thinkingBuffer.append(event.text)
                        emit(ConversationEvent.ThinkingDelta(event.text), onEvent)
                    }
                    is ChatHttpClient.StreamEvent.ToolCallDelta -> {
                        // 增量不实时推送，等 Finish 时统一处理
                    }
                    is ChatHttpClient.StreamEvent.Finish -> {
                        finishReason = event.reason
                        toolCalls = event.toolCalls
                        if (event.usage.totalTokens > 0) {
                            usage = event.usage
                            totalPromptTokens += event.usage.promptTokens
                            totalCompletionTokens += event.usage.completionTokens
                            totalTokens += event.usage.totalTokens
                            emit(ConversationEvent.TokenUsage(
                                totalPromptTokens, totalCompletionTokens, totalTokens
                            ), onEvent)
                        }
                    }
                    is ChatHttpClient.StreamEvent.Error -> {
                        error = ConversationError.LlmError(event.message, retriable = true)
                    }
                    is ChatHttpClient.StreamEvent.Retrying -> {
                        _state.update { ConversationState.Retrying(event.attempt, event.reason) }
                        emit(ConversationEvent.Retrying(event.attempt, event.reason, event.delayMs), onEvent)
                        emit(ConversationEvent.StateChanged(
                            ConversationState.Retrying(event.attempt, event.reason)), onEvent)
                    }
                }
            }
        } catch (e: CancellationException) {
            // abort — 保存已收到的部分文本到上下文
            if (textBuffer.isNotEmpty()) {
                cw.add(ChatHttpClient.ChatMessageDto(
                    role = "assistant",
                    content = textBuffer.toString(),
                    toolCalls = toolCalls
                ))
            }
            throw e
        }

        return LlmRoundResult(
            text = textBuffer.toString(),
            thinking = thinkingBuffer.toString(),
            toolCalls = toolCalls,
            finishReason = finishReason,
            error = error,
            usage = usage
        )
    }

    // ══════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════

    private fun getOrCreateContextWindow(config: ConversationConfig): ContextWindow {
        val existing = contextWindow
        return if (existing != null) {
            // 复用已有窗口
            existing
        } else {
            ContextWindow(config.contextTokenLimit, compactor)
        }
    }

    private fun emit(event: ConversationEvent, onEvent: (ConversationEvent) -> Unit) {
        onEvent(event)
        _events.tryEmit(event)
    }
}
