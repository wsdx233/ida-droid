package dev.idadroid.agent

import android.content.Context
import android.net.Uri
import dev.idadroid.env.EnvironmentPaths
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.idadroid.util.runCatchingSuspend
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class PiAgentManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext
    private val settings = dev.idadroid.settings.IdaDroidSettings(appContext)
    /** 用户设置的工作区路径 (proot 内可见路径)，默认 /root/pi_workspace */
    private val workspaceProotPath: String get() = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH }
    /** 工作区在主机文件系统上的根目录 */
    private val workspaceHostRoot: File get() {
        val ws = workspaceProotPath
        // /root/xxx → 在 rootfs 内
        if (ws.startsWith("/root/")) return File(paths.rootfsDir, ws.removePrefix("/"))
        // /sdcard/xxx 或 /storage/xxx → proot 已绑定，直接用主机路径
        if (ws.startsWith("/sdcard") || ws.startsWith("/storage")) return File(ws)
        // 其他情况默认在 rootfs 内
        return File(paths.rootfsDir, ws.removePrefix("/").ifBlank { "root/pi_workspace" })
    }
    private val repo = AgentSessionRepository(appContext, paths)
    private val configManager = PiConfigManager(appContext, paths)
    val aiConfigTools = AiConfigTools(paths, configManager)
    private val attachmentManager = AttachmentManager(appContext, paths)
    val workspaceManager = WorkspaceManager(appContext, paths)
    private val deepIndexToolChain = dev.idadroid.deepindex.DeepIndexToolChain(appContext, paths)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
    // Send lock: prevents concurrent sendPrompt calls from racing. Operit uses
    // a similar coordination pattern in MessageCoordinationDelegate.
    private val sendMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var sendingInProgress = false
    /** 当主动 abort 上一轮对话时设为 true，收到 abort 错误事件后静默处理 */
    @Volatile private var suppressAbortError = false

    // ==================== 新架构：分层对话引擎 ====================
    // Layer 1 (ChatHttpClient) + Layer 2 (ToolExecutor) + Layer 2.5 (ContextWindow)
    // + Layer 3 (ConversationEngine) — 替代旧 ConversationEngine
    private val conversationEngine = ConversationEngine(
        appContext, paths, dev.idadroid.proot.IdaProotRuntime(appContext, paths)
    ).apply {
        compactor = { messages -> generateLlmSummary(messages.map { it.toUiMessage() }, null) }
    }
    @Volatile private var currentSendJob: Job? = null
    /** ConversationEngine 当前上下文归属的会话 id（引擎全局共享，切换会话时必须 reset/恢复）。 */
    @Volatile private var engineSessionId: String? = null

    private val _state = MutableStateFlow(AgentUiState(activity = "agent 未启动"))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    // ==================== 流式 delta 合并器 ====================
    // 提取为 DeltaFlusher 独立类 — 高频 delta 节拍 flush，避免主线程卡帧
    private val deltaFlusher = DeltaFlusher(_state, scope) { newMessageId() }

    fun refresh(createDefaultIfReady: Boolean = false) {
        if (!paths.rootfsDir.isDirectory) {
            _state.update { AgentUiState(activity = "rootfs 未导入") }
            return
        }
        // 文件 IO 切到 IO 线程；任何一步失败只更新错误状态，绝不能让
        // 启动链路（IdaDroidApp LaunchedEffect → refresh）的未捕获异常杀死进程。
        scope.launch {
            dev.idadroid.util.runCatchingSuspending {
                val loaded = withContext(Dispatchers.IO) {
                    val store = repo.loadStore()
                    val snapshot = configManager.readSnapshot()
                    val defaultPair = if (createDefaultIfReady && store.sessions.isEmpty()) resolveDefaultModel(snapshot) else null
                    Triple(store, snapshot, defaultPair)
                }
                val (store, snapshot, defaultPair) = loaded
                val defaultProvider = defaultPair?.provider ?: snapshot.defaultProvider.trim().takeIf { it.isNotBlank() }
                val defaultModel = defaultPair?.id ?: snapshot.defaultModel.trim().takeIf { it.isNotBlank() }
                val defaultThinking = snapshot.defaultThinkingLevel.trim().takeIf { it.isNotBlank() }
                val active = when {
                    store.activeSessionId != null && store.sessions.any { it.id == store.activeSessionId } -> store.activeSessionId
                    store.sessions.isNotEmpty() -> store.sessions.first().id
                    createDefaultIfReady -> repo.ensureDefaultSession(
                        provider = defaultProvider,
                        model = defaultModel,
                        thinkingLevel = defaultThinking
                    ).id
                    else -> null
                }
                val sessions = store.sessions
                _state.update { old ->
                    val activeSession = sessions.firstOrNull { it.id == active }
                    old.copy(
                        sessions = sessions,
                        activeSessionId = active,
                        status = activeSession?.status ?: "idle",
                        error = activeSession?.error,
                        modelLabel = modelLabel(activeSession),
                        piConfig = snapshot,
                        activity = if (active == null) "点击新建 Session 开始" else old.activity,
                        workspace = old.workspace.copy(
                            hasWorkspace = workspaceManager.hasWorkspace,
                            workspaceName = workspaceManager.currentWorkspaceName,
                            workspaceUri = workspaceManager.currentWorkspaceUri?.toString().orEmpty()
                        )
                    )
                }
                active?.let { loadMessages(it) }
            }.onFailure { error ->
                _state.update { it.copy(error = "刷新 Agent 状态失败：${error.message}", activity = "刷新失败") }
            }
        }
    }

    fun createSession(name: String = "") {
        scope.launch {
            runCatching {
                requireReady()
                val snapshot = configManager.readSnapshot()
                val defaultPair = resolveDefaultModel(snapshot)
                repo.createSession(
                    name = name.ifBlank { null },
                    provider = configManager.defaultProvider() ?: defaultPair?.provider,
                    model = configManager.defaultModel() ?: defaultPair?.id,
                    thinkingLevel = configManager.defaultThinking()
                )
            }.onSuccess { session ->
                // selectSession 内部会先把引擎从旧会话切走（abort + bind + loadMessages）
                selectSession(session.id)
            }.onFailure { error -> setError("新建 Session 失败：${error.message}") }
        }
    }

    fun selectSession(id: String) {
        scope.launch {
            try {
                // 切换会话前先中止/收尾旧会话的发送，避免引擎上下文串写
                val current = _state.value.activeSessionId
                if (current != null && current != id && _state.value.turnActive) {
                    runCatching { abortSession(current) }
                }
                // 先把引擎绑定到目标会话（必要时持久化旧会话并从文件恢复历史）。
                // 绑定成功后才提交 active：若这里失败（读历史文件异常等），store 仍保持
                // 旧 active，不会出现"store 已切换但引擎丢失目标历史"的错位。
                val session = repo.listSessions().firstOrNull { it.id == id }
                    ?: error("session 不存在：$id")
                val convConfig = session.let { resolveConvConfig(it.id, it) }
                bindEngineToSession(id, convConfig)
                repo.setActive(id)
            } catch (e: Exception) {
                setError("切换 Session 失败：${e.message}")
                return@launch
            }
            refresh()
            loadMessages(id)
        }
    }

    fun renameSession(id: String, name: String) {
        if (name.isBlank()) return
        scope.launch {
            runCatching { repo.patchSession(id) { it.copy(name = name.trim()) } }
                .onSuccess { refresh() }
                .onFailure { error -> setError("重命名失败：${error.message}") }
        }
    }

    fun deleteSession(id: String) {
        scope.launch {
            runCatching {
                conversationEngine.abort()
                repo.deleteSession(id)
            }.onSuccess {
                // 删除的是引擎当前绑定的会话 → 清空引擎，等待下一个会话重新 bind
                if (engineSessionId == id) {
                    dev.idadroid.util.runCatchingSuspending { conversationEngine.reset() }
                    engineSessionId = null
                }
                refresh(createDefaultIfReady = true)
            }.onFailure { error -> setError("删除 Session 失败：${error.message}") }
        }
    }

    fun startSession(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: createDefaultSessionWithConfiguredModel().id
            // 新架构：不需要启动 pi 进程，只需更新状态
            repo.updateRuntimeStatus(sessionId, "running", null)
            _state.update { it.copy(status = "running", error = null, activeSessionId = sessionId, sessions = repo.listSessions(), activity = "就绪") }
        }
    }

    fun stopSession(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            // 新架构：abort 当前对话 + 更新状态
            conversationEngine.abort()
            repo.updateRuntimeStatus(sessionId, "idle", null)
            _state.update { it.copy(status = "idle", turnActive = false, processingPhase = null, activity = "已停止") }
            refresh()
        }
    }

    fun sendPrompt(text: String, attachments: List<DraftAttachment> = emptyList(), sendMode: String? = null) {
        scope.launch {
            // 用 sendMutex 串行化发送，消除 sendingInProgress 检查和实际发送之间的竞态
            sendMutex.withLock {
                if (sendingInProgress) {
                    _state.update { it.copy(activity = "上一条消息仍在发送中，请稍候…") }
                    return@withLock
                }
                sendingInProgress = true
                try {
                    sendPromptInternal(text, attachments, sendMode)
                } finally {
                    sendingInProgress = false
                }
            }
        }
    }

    private suspend fun sendPromptInternal(text: String, attachments: List<DraftAttachment>, sendMode: String?) {
        val sessionId = _state.value.activeSessionId ?: run {
            val created = createDefaultSessionWithConfiguredModel()
            refresh()
            created.id
        }
        val trimmed = text.trimEnd()
        if (trimmed.isBlank() && attachments.isEmpty()) return
        // sendMutex 已在 sendPrompt 中获取，这里直接执行
        try {
                val session = repo.setActive(sessionId)
                val stored = attachmentManager.storeAttachments(attachments)
                val uploadedPaths = stored.map { it.prootPath }
                val displayAttachments = stored.map { it.display }
                val displayMessage = buildString {
                    append(trimmed)
                    if (uploadedPaths.isNotEmpty()) {
                        if (isNotEmpty() && !last().isWhitespace()) append(' ')
                        append(uploadedPaths.joinToString(" ") { attachmentManager.fileRef(it) })
                    }
                }.ifBlank { attachmentSummary(displayAttachments) }

                appendMessage(ChatMessage(newMessageId(), "user", displayMessage, System.currentTimeMillis(), attachments = displayAttachments))
                setTurnActive(sessionId, true)
                val promptStartTime = System.currentTimeMillis()
                _state.update { it.copy(activity = "正在发送消息…", processingPhase = "connecting", promptSentAt = promptStartTime, firstDeltaAt = 0L) }

                // ==================== 新架构路径 ====================
                // 解析 API Key / Base URL / Model 从 PiConfigManager
                val convConfig = resolveConvConfig(sessionId, session)
                    ?: run {
                        setTurnActive(sessionId, false)
                        appendMessage(ChatMessage(newMessageId(), "system", "配置缺失：请先在设置中配置 API Key 和 Base URL", System.currentTimeMillis()))
                        setError("配置缺失：请先在设置中配置 API Key 和 Base URL")
                        return
                    }

                val expanded = attachmentManager.expandFileReferencesForPrompt(displayMessage, session.cwd)
                currentSendJob = coroutineContext[Job]

                try {
                    kotlinx.coroutines.withTimeout(PROMPT_TIMEOUT_MS) {
                        // 发送前把引擎绑定到当前会话：应用重启/切换会话后引擎可能为空或
                        // 属于其它会话，需要先从该会话文件恢复历史，否则对话会丢失上下文
                        bindEngineToSession(sessionId, convConfig)
                        val imageUris = expanded.images.map { img ->
                            "data:${img.mimeType};base64,${img.data}"
                        }
                        conversationEngine.send(expanded.message, imageUris, convConfig) { event ->
                            handleEngineEvent(sessionId, event, promptStartTime)
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    appendMessage(ChatMessage(newMessageId(), "system", "发送超时（3分钟无完成）", System.currentTimeMillis()))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    android.util.Log.i("PiAgentManager", "Prompt cancelled by abort (sessionId=$sessionId)")
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: e::class.simpleName ?: "未知异常"
                    appendMessage(ChatMessage(newMessageId(), "system", "发送失败：$msg", System.currentTimeMillis()))
                    setError("发送失败：$msg")
                } finally {
                    currentSendJob = null
                    finishStreamingFlush()
                    if (_state.value.turnActive) setTurnActive(sessionId, false)
                    // 持久化对话历史 — 防止崩溃/重启后丢失
                    persistMessages(sessionId)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                setTurnActive(sessionId, false)
                val msg = e.message ?: e::class.simpleName ?: "未知异常"
                appendMessage(ChatMessage(newMessageId(), "system", "发送失败：$msg", System.currentTimeMillis()))
                setError("发送失败：$msg")
            }
    }

    fun abort(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            abortSession(sessionId)
        }
    }

    /** abort 的实际逻辑（可在切换会话前同步调用）。 */
    private suspend fun abortSession(sessionId: String) {
        // 标记：后续收到的 abort 相关错误事件静默处理
        suppressAbortError = true
        // 新架构：cancel sendJob 让 ConversationEngine.send() 的流收集中断
        currentSendJob?.let { job ->
            job.cancel()
            try { job.join() } catch (_: kotlinx.coroutines.CancellationException) {}
        }
        currentSendJob = null
        conversationEngine.abort()
        finishStreamingFlush()
        setTurnActive(sessionId, false)
        repo.updateRuntimeStatus(sessionId, "running", null)
    }

    fun loadMessages(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            _state.update { it.copy(messagesLoading = true) }
            try {
                val session = repo.listSessions().firstOrNull { it.id == sessionId }
                val convConfig = session?.let { resolveConvConfig(it.id, it) }
                // 引擎绑定失败（历史文件读取异常）不阻塞展示：loadMessagesInternal 会回退读文件
                runCatching { bindEngineToSession(sessionId, convConfig) }
                val messages = withContext(Dispatchers.IO) { loadMessagesInternal(sessionId) }
                _state.update { it.copy(messages = messages, messagesLoading = false) }
            } catch (e: Exception) {
                setError("加载消息失败：${e.message}")
                _state.update { it.copy(messagesLoading = false) }
            }
        }
    }

    /**
     * 把 ConversationEngine 的全局上下文绑定到指定会话：
     * 切换会话时先把旧上下文持久化到旧会话文件，重置引擎，再从目标会话
     * 文件恢复历史消息。这样每个会话的消息互相隔离，不再"新建会话却显示
     * 上一个会话的内容"。调用方需保证当前没有正在运行的 send（切换前 abort）。
     *
     * 注意：目标历史文件的读取放在任何引擎状态变更之前 —— 若读取失败会抛异常，
     * 引擎与 engineSessionId 均保持不变，调用方（如 selectSession）可以放弃切换，
     * 不会出现"store 已切到目标会话但引擎丢失目标历史"的错位。
     */
    private suspend fun bindEngineToSession(sessionId: String, config: ConversationConfig?) {
        val bound = engineSessionId
        if (bound == sessionId) return
        // 先读目标会话的历史（可能抛 IOException），确认能恢复再动引擎
        var dtos = emptyList<ChatHttpClient.ChatMessageDto>()
        if (config != null) {
            val session = repo.listSessions().firstOrNull { it.id == sessionId }
            val sessionFile = session?.sessionFile ?: defaultSessionFilePath(sessionId)
            val file = sessionFileToHostFile(sessionFile)?.takeIf { it.isFile }
            if (file != null) {
                dtos = withContext(Dispatchers.IO) {
                    file.readLines().mapNotNull { line ->
                        val trimmed = line.trim()
                        if (trimmed.isBlank()) null
                        else runCatching { json.decodeFromString<ChatHttpClient.ChatMessageDto>(trimmed) }.getOrNull()
                    }
                }
            }
        }
        // 引擎里残留其它会话的上下文 → 先写回它的会话文件，避免丢失
        if (bound != null && conversationEngine.getMessages().isNotEmpty()) {
            persistMessages(bound)
        }
        conversationEngine.reset()
        // 仅当配置可用（能恢复历史或确认空会话）时才标记引擎已绑定该会话。
        // config == null（例如尚未配置 API Key）时保持未绑定，让之后配置就绪的
        // send/loadMessages 能重新进入本方法并加载文件历史 —— 否则 send 会因
        // engineSessionId == sessionId 提前返回，引擎空转导致历史永久丢失。
        engineSessionId = if (config != null) sessionId else null
        if (dtos.isNotEmpty() && config != null) {
            conversationEngine.restoreFromMessages(dtos, config)
        }
    }

    /** 会话消息文件（proot 路径，缺省在 workspace 的 .idadroid/sessions 下）。 */
    private fun defaultSessionFilePath(sessionId: String): String =
        "$workspaceProotPath/.idadroid/sessions/${sessionId}.jsonl"

    /**
     * 持久化对话历史到 session 文件。
     * 在 sendPrompt 结束后自动调用，防止崩溃/重启后丢失。
     */
    private suspend fun persistMessages(sessionId: String) {
        try {
            val messages = conversationEngine.getMessages()
            if (messages.isEmpty()) return
            withContext(Dispatchers.IO) {
                val session = repo.listSessions().firstOrNull { it.id == sessionId } ?: return@withContext
                // sessionFile 为空时创建默认路径，并回写到 session 记录便于后续恢复
                val sessionFile = session.sessionFile ?: defaultSessionFilePath(sessionId)
                val file = sessionFileToHostFile(sessionFile) ?: return@withContext
                file.parentFile?.mkdirs()
                // 每条消息一行 JSON，便于增量读取和恢复
                file.writeText(messages.joinToString("\n") { msg ->
                    json.encodeToString(ChatHttpClient.ChatMessageDto.serializer(), msg)
                } + "\n")
                if (session.sessionFile.isNullOrBlank()) {
                    repo.setSessionFile(sessionId, sessionFile)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("PiAgentManager", "消息持久化失败: ${e.message}")
        }
    }

    fun clearRawLog() {
        _state.update { it.copy(rawLogLines = emptyList(), stderrTail = "") }
    }

    // ==================== 工作区相关 ====================

    /** 设置工作区 URI（由 SAF 选择回调传入）。 */
    fun setWorkspace(uri: android.net.Uri) {
        scope.launch {
            runCatching {
                workspaceManager.setWorkspace(uri)
                _state.update { it.copy(workspace = it.workspace.copy(
                    hasWorkspace = true,
                    workspaceName = workspaceManager.currentWorkspaceName,
                    workspaceUri = uri.toString(),
                    currentPath = "",
                    error = null
                )) }
                refreshWorkspaceFiles()
            }.onFailure { error -> setError("设置工作区失败：${error.message}") }
        }
    }

    /** 清除当前工作区。 */
    fun clearWorkspace() {
        workspaceManager.clearWorkspace()
        _state.update { it.copy(workspace = WorkspaceState()) }
    }

    /** 刷新工作区文件列表。 */
    fun refreshWorkspaceFiles(path: String = _state.value.workspace.currentPath) {
        scope.launch {
            _state.update { it.copy(workspace = it.workspace.copy(loading = true, error = null)) }
            try {
                val files = withContext(Dispatchers.IO) { workspaceManager.listFiles(path) }
                _state.update { it.copy(workspace = it.workspace.copy(
                    files = files,
                    currentPath = path,
                    loading = false
                )) }
            } catch (error: Exception) {
                _state.update { it.copy(workspace = it.workspace.copy(
                    loading = false,
                    error = "读取工作区失败：${error.message}"
                )) }
            }
        }
    }

    /** 进入工作区子目录。 */
    fun navigateWorkspace(relativePath: String) {
        val next = workspaceManager.resolvePath(_state.value.workspace.currentPath, relativePath)
        refreshWorkspaceFiles(next)
    }

    /** 将工作区中的文件导入到 pi_workspace 容器内，供 Agent 使用。 */
    fun importWorkspaceFileToContainer(entry: WorkspaceFileEntry, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            runCatchingSuspend {
                withContext(Dispatchers.IO) { workspaceManager.importToContainer(entry) }
            }.onSuccess { prootPath ->
                val path = prootPath ?: "未知路径"
                appendMessage(ChatMessage(newMessageId(), "system", "已从工作区导入文件：${entry.name} → $path", System.currentTimeMillis()))
                onResult(true, path)
            }.onFailure { error ->
                setError("导入工作区文件失败：${error.message}")
                onResult(false, error.message ?: "未知错误")
            }
        }
    }

    /** 将 pi_workspace 容器内的文件导出到工作区。 */
    fun exportFileToWorkspace(containerRelativePath: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            runCatchingSuspend {
                withContext(Dispatchers.IO) { workspaceManager.exportFromContainer(containerRelativePath) }
            }.onSuccess { name ->
                val fileName = name ?: containerRelativePath.substringAfterLast('/').ifBlank { containerRelativePath }
                appendMessage(ChatMessage(newMessageId(), "system", "已导出到工作区：$containerRelativePath → $fileName", System.currentTimeMillis()))
                refreshWorkspaceFiles()
                onResult(true, fileName)
            }.onFailure { error ->
                setError("导出到工作区失败：${error.message}")
                onResult(false, error.message ?: "未知错误")
            }
        }
    }

    /** 读取工作区文件作为草稿附件（用于发送给 Agent）。 */
    suspend fun readWorkspaceFileAsAttachment(entry: WorkspaceFileEntry): DraftAttachment? {
        return runCatchingSuspend {
            withContext(Dispatchers.IO) { workspaceManager.readAsDraftAttachment(entry) }
        }.onFailure { error ->
            setError("读取工作区文件失败：${error.message}")
        }.getOrNull()
    }

    /** 将指定助手回复保存为工作区文件（满足"生成的代码文件都放工作区"）。 */
    fun saveAssistantMessageToWorkspace(messageId: String, fileName: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val message = _state.value.messages.firstOrNull { it.id == messageId && it.role == "assistant" }
        if (message == null) {
            onResult(false, "未找到该回复")
            return
        }
        val content = message.text.ifBlank { message.thinking ?: "" }
        if (content.isBlank()) {
            onResult(false, "回复内容为空")
            return
        }
        val safeName = safeFileName(fileName).ifBlank { "reply.txt" }
        val finalName = if (safeName.contains('.')) safeName else "$safeName.txt"
        scope.launch {
            runCatchingSuspend {
                withContext(Dispatchers.IO) { workspaceManager.writeFileText(finalName, content) }
            }.onSuccess { ok ->
                if (ok) {
                    appendMessage(ChatMessage(newMessageId(), "system", "已保存到工作区：$finalName", System.currentTimeMillis()))
                    refreshWorkspaceFiles()
                    onResult(true, finalName)
                } else {
                    setError("保存到工作区失败：工作区未就绪")
                    onResult(false, "工作区未就绪")
                }
            }.onFailure { error ->
                setError("保存到工作区失败：${error.message}")
                onResult(false, error.message ?: "未知错误")
            }
        }
    }

    /** 将工作区文件内容作为文本引用插入到输入框。 */
    suspend fun readWorkspaceFileAsText(entry: WorkspaceFileEntry): String? {
        return runCatchingSuspend {
            withContext(Dispatchers.IO) { workspaceManager.readAsText(entry) }
        }.onFailure { error ->
            setError("读取工作区文件失败：${error.message}")
        }.getOrNull()
    }

    suspend fun readDraftAttachment(uri: android.net.Uri): DraftAttachment = attachmentManager.readDraft(uri)

    fun loadSessionModels(force: Boolean = false) {
        scope.launch {
            if (_state.value.sessionModels.isNotEmpty() && !force) return@launch
            _state.update { it.copy(modelLoading = true) }
            runCatching {
                // 新架构：直接从 config 读模型列表，不需要 pi agent
                configManager.readSnapshot().modelCatalog.models.map { it.toPiModel() }
            }.onSuccess { models ->
                _state.update { it.copy(sessionModels = models, modelLoading = false) }
            }.onFailure { error ->
                val fallback = configManager.readSnapshot().modelCatalog.models.map { it.toPiModel() }
                _state.update { it.copy(sessionModels = fallback, modelLoading = false) }
                if (fallback.isEmpty()) setError("加载模型失败：${error.message}")
            }
        }
    }

    fun setSessionModel(model: PiModel) {
        val rawProvider = model.providerNameOrNull() ?: return setError("模型缺少 provider")
        // 新架构：provider 映射保留用于 session 存储一致性
        val provider = when (rawProvider) {
            "custom", "openai-generic" -> "openai"
            else -> rawProvider
        }
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                // 新架构：不需要 runtime.setModel()，下次 sendPrompt 会用新 config
                repo.patchSession(sessionId) { it.copy(provider = provider, model = model.id, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refresh() }
                .onFailure { error -> setError("切换模型失败：${error.message}") }
        }
    }

    fun setSessionModelManual(provider: String, modelId: String) {
        if (provider.isBlank() || modelId.isBlank()) return setError("provider/model 不能为空")
        setSessionModel(PiModel(id = modelId.trim(), provider = provider.trim()))
    }

    fun chooseThinking(level: String) {
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                // 新架构：thinking level 存在 session 里，下次 sendPrompt 时传给 ConvConfig
                repo.patchSession(sessionId) { it.copy(thinkingLevel = level, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refresh() }
                .onFailure { error -> setError("切换 Thinking 失败：${error.message}") }
        }
    }

    fun setAutoCompaction(enabled: Boolean) {
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                // 新架构：auto compaction 由 ConversationEngine 的 maxToolRounds 控制
                repo.patchSession(sessionId) { it.copy(autoCompactionEnabled = enabled, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refresh() }
                .onFailure { error -> setError("切换 Compact 失败：${error.message}") }
        }
    }

    fun compact(customInstructions: String? = null) {
        // 上下文压缩：保留 system 消息和最近 1/3 的对话历史，
        // 用 LLM 生成摘要消息替代被移除的内容（替代简单截取）。
        scope.launch {
            sendMutex.withLock {
                val sessionId = _state.value.activeSessionId ?: return@withLock
                val messages = _state.value.messages
                if (messages.size <= 6) {
                    appendMessage(ChatMessage(newMessageId(), "system", "消息较少，无需压缩。", System.currentTimeMillis()))
                    return@withLock
                }

                val keepCount = maxOf(4, messages.size / 3)
                val toSummarize = messages.dropLast(keepCount)
                val kept = messages.takeLast(keepCount)

                // 用 LLM 生成摘要（异步，不阻塞 UI）
                val summary = generateLlmSummary(toSummarize, customInstructions)

                val apiMessages = conversationEngine.getMessages()
                val keptApiMessages = apiMessages.takeLast(minOf(keepCount, apiMessages.size))

                conversationEngine.reset()
                val convConfig = sessionId.let { id ->
                    val session = repo.listSessions().firstOrNull { it.id == id }
                    session?.let { resolveConvConfig(id, it) }
                }
                if (convConfig != null && keptApiMessages.isNotEmpty()) {
                    conversationEngine.restoreFromMessages(keptApiMessages, convConfig)
                }

                _state.update { it.copy(messages = listOf(
                    ChatMessage(newMessageId(), "system", summary, System.currentTimeMillis())
                ) + kept) }
                _state.update { it.copy(sessions = repo.listSessions()) }
            }
        }
    }

    /**
     * 用 LLM 生成上下文摘要 — 替代旧版的简单截取。
     * 如果 LLM 调用失败，回退到简单截取摘要。
     */
    private suspend fun generateLlmSummary(
        toSummarize: List<ChatMessage>,
        customInstructions: String?
    ): String = withContext(Dispatchers.IO) {
        // 复用 resolveConvConfig 的完整 provider/env/baseUrl 解析逻辑
        val sessionId = _state.value.activeSessionId
        val session = sessionId?.let { repo.listSessions().firstOrNull { s -> s.id == it } }
        val convConfig = session?.let { resolveConvConfig(it.id, it) }
            ?: return@withContext buildFallbackSummary(toSummarize, customInstructions)
        val snapshot = configManager.readSnapshot()
        val model = (session?.model ?: snapshot.defaultModel).trim().ifBlank {
            return@withContext buildFallbackSummary(toSummarize, customInstructions)
        }
        val apiKey = convConfig.apiKey
        val baseUrl = convConfig.baseUrl

        val prompt = buildString {
            append("请将以下对话历史压缩为简洁的摘要，保留关键技术发现、分析结论和未完成任务。\n")
            if (!customInstructions.isNullOrBlank()) {
                append("用户特别要求：$customInstructions\n")
            }
            append("\n--- 对话历史 ---\n")
            toSummarize.filter { it.role == "user" || it.role == "assistant" }
                .forEach { msg ->
                    val role = if (msg.role == "user") "用户" else "助手"
                    val preview = msg.text.take(500)
                    append("[$role] $preview\n")
                }
            append("--- 摘要 ---\n")
        }

        try {
            val client = ChatHttpClient(baseUrl, apiKey, model, convConfig.providerId)
            val response = StringBuffer()
            client.chat(
                messages = listOf(ChatHttpClient.ChatMessageDto(role = "user", content = prompt)),
                tools = emptyList(),
                systemPrompt = "你是一个对话摘要助手。请简洁地总结对话中的关键信息。",
                thinkingLevel = null,
                temperature = 0.3,
                maxTokens = 2000,
                topP = null
            ).collect { event ->
                when (event) {
                    is ChatHttpClient.StreamEvent.TextDelta -> response.append(event.text)
                    else -> {}
                }
            }
            val result = response.toString().trim()
            if (result.isNotBlank()) "[上下文压缩]\n$result" else buildFallbackSummary(toSummarize, customInstructions)
        } catch (e: Exception) {
            buildFallbackSummary(toSummarize, customInstructions)
        }
    }

    /** 回退摘要 — LLM 不可用时使用 */
    private fun buildFallbackSummary(
        toSummarize: List<ChatMessage>,
        customInstructions: String?
    ): String = buildString {
        append("[上下文压缩]\n")
        append("已压缩 ${toSummarize.size} 条早期消息。\n")
        if (!customInstructions.isNullOrBlank()) {
            append("用户指示：$customInstructions\n")
        }
        append("\n压缩前的关键内容摘要：\n")
        toSummarize.filter { it.role == "user" || it.role == "assistant" }
            .takeLast(5)
            .forEach { msg ->
                val role = if (msg.role == "user") "用户" else "助手"
                val preview = msg.text.take(200).replace("\n", " ")
                append("- $role: $preview...\n")
            }
    }

    /**
     * 自动压缩消息历史 — 由 ConversationEngine.trimContextIfNeeded 触发。
     *
     * 流程：
     * 1. 将 API 级 ChatMessageDto 转为 UI 级 ChatMessage
     * 2. 调用 generateLlmSummary 生成 LLM 摘要
     * 3. 重置 ConversationEngine 并用压缩后的历史恢复
     * 4. 更新 UI 状态
     *
     * 返回 true 表示压缩成功，false 表示失败（ConversationEngine 会回退到直接截断）。
     */
    private suspend fun autoCompactMessages(messages: List<ChatHttpClient.ChatMessageDto>): Boolean {
        if (messages.size <= 6) return false

        val keepCount = maxOf(4, messages.size / 3)
        val toSummarizeDto = messages.dropLast(keepCount)
        val keptDto = messages.takeLast(keepCount)

        // 转为 UI 级 ChatMessage 用于 generateLlmSummary
        val toSummarizeUi = toSummarizeDto.toUiMessages().filter { it.role == "user" || it.role == "assistant" }
        if (toSummarizeUi.isEmpty()) return false

        // 生成摘要
        val summary = generateLlmSummary(toSummarizeUi, null)

        // 就地替换消息列表 — 不创建新 Conversation
        // 确保 send() 的 while 循环中持有的 conv 引用仍然有效
        val newMessages = listOf(
            ChatHttpClient.ChatMessageDto(role = "system", content = summary)
        ) + keptDto

        conversationEngine.compactMessages(newMessages)

        // 更新 UI 状态
        val keptUi = keptDto.toUiMessages()

        _state.update { it.copy(messages = listOf(
            ChatMessage(newMessageId(), "system", summary, System.currentTimeMillis())
        ) + keptUi) }

        android.util.Log.i("PiAgentManager", "自动压缩完成: ${toSummarizeDto.size} 条消息 → 摘要")
        return true
    }

    fun getPiConfigSnapshot(): PiConfigSnapshot = configManager.readSnapshot()

    fun savePiConfig(snapshot: PiConfigSnapshot) {
        scope.launch {
            runCatchingSuspend { withContext(Dispatchers.IO) { configManager.saveSnapshot(snapshot) } }
                .onSuccess { _state.update { it.copy(piConfig = configManager.readSnapshot(), activity = "Pi 配置已保存；重启 session 后生效") } }
                .onFailure { error -> setError("保存 Pi 配置失败：${error.message}") }
        }
    }

    // ==================== Deep Index Mode ====================

    fun enableDeepIndexMode() {
        deepIndexToolChain.setEnabled(true)
        _state.update { it.copy(activity = "深度索引模式已开启：deep-index 工具链已就绪，agent 将使用 CodeGraph + ECC + Memory 联动分析") }
    }

    fun disableDeepIndexMode() {
        deepIndexToolChain.setEnabled(false)
        _state.update { it.copy(activity = "深度索引模式已关闭") }
    }

    fun isDeepIndexEnabled(): Boolean = deepIndexToolChain.isEnabled()

    suspend fun listFiles(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = workspaceFile(path)
        require(dir.isDirectory) { "目录不存在：${workspaceAbsPath(path)}" }
        dir.listFiles().orEmpty().map { file ->
            FileEntry(
                name = file.name,
                path = workspaceRelPath(file),
                type = if (file.isDirectory) "directory" else "file",
                size = if (file.isFile) file.length() else 0L,
                modifiedAt = Instant.ofEpochMilli(file.lastModified()).toString()
            )
        }
    }

    suspend fun uploadFile(path: String, draft: DraftAttachment): FileEntry = withContext(Dispatchers.IO) {
        val dir = workspaceFile(path).apply { mkdirs() }
        val target = uniqueFile(dir, draft.name)
        target.writeBytes(draft.bytes)
        FileEntry(target.name, workspaceRelPath(target), "file", target.length(), Instant.ofEpochMilli(target.lastModified()).toString())
    }

    suspend fun importInstalledApk(packageName: String, label: String, apkPath: String, destinationPath: String = "."): FileEntry = withContext(Dispatchers.IO) {
        val source = File(apkPath)
        require(source.isFile && source.canRead()) { "无法读取 APK：$apkPath" }
        val dir = workspaceFile(destinationPath).apply { mkdirs() }
        val target = uniqueFile(dir, "${safeFileName(label.ifBlank { packageName }).removeSuffix(".apk")}.apk")
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        FileEntry(target.name, workspaceRelPath(target), "file", target.length(), Instant.ofEpochMilli(target.lastModified()).toString())
    }

    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        workspaceFile(path).mkdirs()
    }

    suspend fun deleteFile(path: String) = withContext(Dispatchers.IO) {
        val file = workspaceFile(path)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    suspend fun fileForSharing(path: String): File = withContext(Dispatchers.IO) {
        val file = workspaceFile(path)
        require(file.isFile) { "文件不存在：${workspaceAbsPath(path)}" }
        file
    }

    suspend fun saveFileAs(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val file = workspaceFile(path)
        require(file.isFile) { "文件不存在：${workspaceAbsPath(path)}" }
        val output = appContext.contentResolver.openOutputStream(destination, "wt") ?: error("无法写入目标文件")
        output.use { target -> file.inputStream().use { source -> source.copyTo(target) } }
    }

    suspend fun readFileText(path: String, maxBytes: Long = 512L * 1024L): String = withContext(Dispatchers.IO) {
        val file = workspaceFile(path)
        require(file.isFile) { "文件不存在：${workspaceAbsPath(path)}" }
        require(file.length() <= maxBytes) { "文件过大：${file.length() / 1024} KiB，仅支持预览 ${maxBytes / 1024} KiB 内文本" }
        file.readText(Charsets.UTF_8)
    }

    fun workspaceAbsPath(path: String): String = when {
        path.startsWith(workspaceProotPath) -> path
        path == "." || path.isBlank() -> workspaceProotPath
        else -> "$workspaceProotPath/${path.trimStart('/')}"
    }

    fun fileRef(path: String): String = attachmentManager.fileRef(path)

    private fun createDefaultSessionWithConfiguredModel(): AgentSessionRecord {
        val snapshot = configManager.readSnapshot()
        val defaultPair = resolveDefaultModel(snapshot)
        return repo.ensureDefaultSession(
            provider = snapshot.defaultProvider.trim().takeIf { it.isNotBlank() } ?: defaultPair?.provider,
            model = snapshot.defaultModel.trim().takeIf { it.isNotBlank() } ?: defaultPair?.id,
            thinkingLevel = snapshot.defaultThinkingLevel.trim().takeIf { it.isNotBlank() }
        )
    }

    private suspend fun refreshRuntimeState(sessionId: String) {
        // 新架构：不依赖 pi agent runtime state，只需 refresh
        refresh()
    }

    private fun newMessageId(): String = java.util.UUID.randomUUID().toString()

    private suspend fun loadMessagesInternal(sessionId: String): List<ChatMessage> {
        // 引擎已绑定该会话且内存有消息 → 直接用引擎快照（含本轮未落盘内容）
        if (engineSessionId == sessionId) {
            val convMessages = conversationEngine.getMessages()
            if (convMessages.isNotEmpty()) {
                return convMessages.toUiMessages()
            }
        }
        // 回退：从该会话的 session 文件读取（新版为 ChatMessageDto JSONL；
        // 兼容旧 pi-agent 格式 JSON）
        val session = repo.listSessions().firstOrNull { it.id == sessionId } ?: return emptyList()
        val sessionFile = session.sessionFile ?: defaultSessionFilePath(sessionId)
        val file = sessionFileToHostFile(sessionFile)?.takeIf { it.isFile } ?: return emptyList()
        val lines = file.readLines()
        val dtos = lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) null
            else runCatching { json.decodeFromString<ChatHttpClient.ChatMessageDto>(trimmed) }.getOrNull()
        }
        if (dtos.isNotEmpty()) return dtos.toUiMessages()
        // 旧 pi 格式兼容
        val elements = lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) null else runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        }
        return normalizePiMessages(elements)
    }

    private fun sessionFileToHostFile(sessionFile: String): File? {
        val normalized = sessionFile.replace('\\', '/').trim()
        val rel = when {
            normalized.startsWith("$workspaceProotPath/") -> normalized.removePrefix("$workspaceProotPath/")
            normalized.startsWith("/root/") -> return File(paths.rootfsDir, normalized.trimStart('/'))
            normalized.startsWith("/") -> return File(paths.rootfsDir, normalized.trimStart('/'))
            else -> normalized
        }
        return File(paths.rootfsDir, "root/pi_workspace/$rel")
    }

    private fun setTurnActive(sessionId: String, active: Boolean) {
        if (!active) {
            // 本轮对话结束：把流式缓冲区里残余的 delta 强制 flush 干净，
            // 确保最后一段文字不会因为节拍 flusher 还没到点而丢失。
            finishStreamingFlush()
        }
        repo.updateRuntimeStatus(sessionId, if (active) "working" else "running", null)
        _state.update { it.copy(
            turnActive = active,
            status = if (active) "working" else "running",
            sessions = repo.listSessions(),
            processingPhase = if (active) it.processingPhase else null,
            promptSentAt = if (active) it.promptSentAt else 0L,
            firstDeltaAt = if (active) it.firstDeltaAt else 0L
        ) }
    }

    private fun appendMessage(message: ChatMessage) {
        _state.update { it.copy(messages = it.messages + message) }
    }

    private fun appendSystemError(message: String) {
        // 主动 abort 时不显示 abort 相关错误
        if (suppressAbortError) {
            // 匹配英文 "abort"/"cancelled" 和中文 "已中止"/"已取消"
            if (message.contains("abort", ignoreCase = true) ||
                message.contains("cancel", ignoreCase = true) ||
                message.contains("已中止") ||
                message.contains("已取消")) {
                suppressAbortError = false
                return
            }
        }
        val text = formatAgentErrorMessage(message)
        _state.update { old ->
            if (old.messages.lastOrNull()?.let { it.role == "system" && it.text == text } == true) old
            else old.copy(messages = old.messages + ChatMessage(newMessageId(), "system", text, System.currentTimeMillis()))
        }
    }

    private fun applyAssistantDeltas(textDelta: String, thinkingDelta: String) {
        deltaFlusher.apply(textDelta, thinkingDelta)
    }

    /** 结束本轮流式：强制 flush 残余 delta */
    private fun finishStreamingFlush() {
        deltaFlusher.finish()
    }

    /**
     * 插入或更新工具调用消息。
     *
     * 线程安全说明：并行工具执行时多个 IO 线程可能同时调用此方法。
     * MutableStateFlow.update 内部使用 CAS 循环，失败时自动重试，
     * 因此每次 update 都基于最新状态，不会丢失更新（无 ABA 问题）。
     * lambda 内的 toMutableList + indexOfFirst 是无副作用的纯操作，
     * 重试时重复执行不会产生问题。
     */
    private fun upsertTool(toolCallId: String, name: String, args: JsonElement?, result: String?, resultMeta: ToolResultMeta?, status: String) {
        _state.update { old ->
            val list = old.messages.toMutableList()
            val idx = list.indexOfFirst { it.role == "tool" && it.toolCallId == toolCallId }
            if (idx >= 0) {
                val current = list[idx]
                list[idx] = current.copy(
                    toolName = name,
                    toolArgs = args ?: current.toolArgs,
                    toolResult = result ?: current.toolResult,
                    toolResultMeta = resultMeta ?: current.toolResultMeta,
                    toolStatus = status,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                list += ChatMessage(newMessageId(), "tool", "", System.currentTimeMillis(), toolCallId = toolCallId, toolName = name, toolArgs = args, toolResult = result, toolResultMeta = resultMeta, toolStatus = status)
            }
            old.copy(messages = list)
        }
    }

    private fun setError(message: String) {
        _state.update { it.copy(error = message, activity = message, status = "error", turnActive = false, processingPhase = null, promptSentAt = 0L, firstDeltaAt = 0L) }
    }

    // ==================== 新架构：配置解析 + 事件映射 ====================

    /**
     * 从 PiConfigManager 解析出 ConversationEngine 需要的配置。
     * 读取 API Key、Base URL、Model、System Prompt。
     */
    private suspend fun resolveConvConfig(sessionId: String, session: AgentSessionRecord): ConversationConfig? {
        val snapshot = configManager.readSnapshot()
        val userConfig = configManager.readUserConfig()

        // Provider 映射
        val rawProvider = (session.provider ?: snapshot.defaultProvider).trim()
        val providerId = when (rawProvider) {
            "custom", "openai-generic", "openai" -> "openai-generic"
            else -> rawProvider
        }.ifBlank { return null }

        // 从 env 中找 API Key — 优先匹配 provider 对应的环境变量名
        val envKeyForProvider = when (providerId) {
            "openai-generic" -> listOf("OPENAI_API_KEY")
            "anthropic" -> listOf("ANTHROPIC_API_KEY")
            "deepseek" -> listOf("DEEPSEEK_API_KEY")
            "google" -> listOf("GOOGLE_API_KEY", "GEMINI_API_KEY")
            "openrouter" -> listOf("OPENROUTER_API_KEY")
            "moonshot" -> listOf("MOONSHOT_API_KEY")
            "dashscope" -> listOf("DASHSCOPE_API_KEY")
            "ark" -> listOf("ARK_API_KEY")
            "baidu" -> listOf("BAIDU_API_KEY")
            "hunyuan" -> listOf("HUNYUAN_API_KEY")
            "siliconflow" -> listOf("SILICONFLOW_API_KEY")
            "mistral" -> listOf("MISTRAL_API_KEY")
            "groq" -> listOf("GROQ_API_KEY")
            "xai" -> listOf("XAI_API_KEY")
            "together" -> listOf("TOGETHER_API_KEY")
            else -> emptyList()
        }
        val apiKey = envKeyForProvider.firstNotNullOfOrNull { keyName ->
            userConfig.env[keyName]?.takeIf { it.isNotBlank() }
        } ?: userConfig.env.entries.firstOrNull { (_, v) -> v.isNotBlank() }?.value
            ?: return null

        // 从 models.json 找 Base URL — 尝试多个可能的 key
        val modelsObj = runCatching {
            dev.idadroid.util.JsonFormats.pretty.parseToJsonElement(snapshot.modelsText).let {
                (it as? JsonObject)?.get("providers") as? JsonObject
            }
        }.getOrNull()
        // 尝试 providerId 和原始 rawProvider 两种 key
        val providerObj = modelsObj?.get(providerId) as? JsonObject
            ?: modelsObj?.get(rawProvider) as? JsonObject
            ?: modelsObj?.get("openai-generic") as? JsonObject
            ?: modelsObj?.get("openai") as? JsonObject
        val baseUrl = providerObj?.get("baseURL")?.jsonPrimitive?.contentOrNull
            ?: providerObj?.get("baseUrl")?.jsonPrimitive?.contentOrNull
            ?: when (providerId) {
                "openai-generic" -> "https://api.openai.com/v1"
                "deepseek" -> "https://api.deepseek.com/v1"
                "anthropic" -> "https://api.anthropic.com/v1"
                else -> return null
            }

        val model = (session.model ?: snapshot.defaultModel).trim().ifBlank { return null }
        val thinkingLevel = session.thinkingLevel ?: snapshot.defaultThinkingLevel.trim().takeIf { it.isNotBlank() }
        val systemPrompt = snapshot.appendSystem.ifBlank { defaultSystemAppendPrompt(workspaceProotPath) }

        // Anthropic API 要求 max_tokens 必填，设置默认值
        val maxTokens = if (providerId == "anthropic") 8192 else null

        return ConversationConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            providerId = providerId,
            systemPrompt = systemPrompt,
            thinkingLevel = thinkingLevel,
            maxTokens = maxTokens
        )
    }

    /**
     * 将 Engine 事件映射到 UI 状态更新。
     * 复用 DeltaFlusher / upsertTool 等现有机制。
     */
    private fun handleEngineEvent(sessionId: String, event: ConversationEvent, promptStartTime: Long) {
        when (event) {
            is ConversationEvent.TextDelta -> {
                if (_state.value.firstDeltaAt == 0L) {
                    _state.update { it.copy(firstDeltaAt = System.currentTimeMillis(), processingPhase = "receiving") }
                }
                applyAssistantDeltas(textDelta = event.text, thinkingDelta = "")
            }
            is ConversationEvent.ThinkingDelta -> {
                if (_state.value.firstDeltaAt == 0L) {
                    _state.update { it.copy(firstDeltaAt = System.currentTimeMillis(), processingPhase = "receiving") }
                }
                applyAssistantDeltas(textDelta = "", thinkingDelta = event.text)
            }
            is ConversationEvent.ToolCallStart -> {
                finishStreamingFlush()
                _state.update { it.copy(processingPhase = "executing_tool") }
                upsertTool(
                    toolCallId = event.toolCallId,
                    name = event.toolName,
                    args = runCatching { json.parseToJsonElement(event.args) }.getOrNull(),
                    result = null,
                    resultMeta = null,
                    status = "running"
                )
            }
            is ConversationEvent.ToolCallResult -> {
                upsertTool(
                    toolCallId = event.toolCallId,
                    name = event.toolName,
                    args = null,
                    result = event.result,
                    resultMeta = null,
                    status = if (event.success) "done" else "error"
                )
            }
            is ConversationEvent.StateChanged -> {
                val phase = when (event.to) {
                    is ConversationState.Connecting -> "connecting"
                    is ConversationState.Streaming -> "receiving"
                    is ConversationState.ExecutingTools -> "executing_tool"
                    is ConversationState.Compacting -> "compacting"
                    is ConversationState.Retrying -> "connecting"
                    is ConversationState.Done, is ConversationState.Failed, is ConversationState.Aborted, ConversationState.Idle -> null
                }
                _state.update { it.copy(processingPhase = phase) }
            }
            is ConversationEvent.Error -> {
                val message = when (val err = event.error) {
                    is ConversationError.LlmError -> err.message
                    is ConversationError.ToolError -> "工具 ${err.toolName} 错误: ${err.message}"
                    is ConversationError.ToolTimeout -> "工具 ${err.toolName} 超时 (${err.timeoutMs / 1000}s)"
                    is ConversationError.ContextOverflow -> "上下文溢出: ${err.estimatedTokens}/${err.limit} tokens"
                    is ConversationError.MaxRoundsExceeded -> "工具调用轮次超过上限 (${err.rounds}/${err.max})"
                    ConversationError.Aborted -> "对话已中止"
                }
                appendSystemError(message)
            }
            is ConversationEvent.TokenUsage -> {
                _state.update { it.copy(
                    activity = "Token 用量 — 输入: ${event.promptTokens} / 输出: ${event.completionTokens} / 总计: ${event.totalTokens}"
                ) }
            }
            is ConversationEvent.Retrying -> {
                appendMessage(ChatMessage(
                    newMessageId(), "system",
                    "网络重试中 (${event.attempt}/3)：${event.reason}，${event.delayMs / 1000}s 后重试...",
                    System.currentTimeMillis()
                ))
            }
            ConversationEvent.TurnComplete -> {
                setTurnActive(sessionId, false)
                repo.updateRuntimeStatus(sessionId, "running", null)
                _state.update { it.copy(processingPhase = null, promptSentAt = 0L, firstDeltaAt = 0L) }
            }
        }
    }

    private fun workspaceFile(path: String): File {
        val normalized = normalizeWorkspacePath(path)
        val rel = when {
            normalized == "." -> ""
            normalized.startsWith("$workspaceProotPath/") -> normalized.removePrefix("$workspaceProotPath/")
            normalized == workspaceProotPath -> ""
            normalized.startsWith("/") -> error("路径必须位于 $workspaceProotPath：$path")
            else -> normalized
        }
        val root = workspaceHostRoot.canonicalFile
        val file = if (rel.isBlank()) root else File(root, rel).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "路径越界：$path" }
        return file
    }

    private fun normalizeWorkspacePath(path: String): String {
        val trimmed = path.trim().ifBlank { "." }.replace('\\', '/')
        val wsPath = workspaceProotPath
        val prefix = if (trimmed.startsWith(wsPath)) wsPath else ""
        val body = if (prefix.isNotBlank()) trimmed.removePrefix(prefix).trimStart('/') else trimmed
        val parts = mutableListOf<String>()
        body.split('/').forEach { part ->
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        val rel = parts.joinToString("/")
        return if (prefix.isNotBlank()) listOf(prefix, rel).filter { it.isNotBlank() }.joinToString("/") else rel.ifBlank { "." }
    }

    private fun workspaceRelPath(file: File): String {
        val root = workspaceHostRoot.canonicalFile
        val canonical = file.canonicalFile
        return canonical.relativeTo(root).path.replace('\\', '/').ifBlank { "." }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val safe = safeFileName(name).ifBlank { "upload" }
        val dot = safe.lastIndexOf('.').takeIf { it > 0 && it < safe.lastIndex }
        val base = dot?.let { safe.substring(0, it) } ?: safe
        val ext = dot?.let { safe.substring(it) }.orEmpty()
        var candidate = File(dir, safe)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-$i$ext")
            i++
        }
        return candidate
    }

    private fun safeFileName(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
        .trim()
        .take(120)
        .ifBlank { "upload" }

    private fun requireReady() {
        require(paths.readyMarker.isFile && paths.rootfsDir.isDirectory) { "rootfs 尚未 ready，请先导入并验证环境" }
    }

    private fun resolveDefaultModel(snapshot: PiConfigSnapshot): AgentConfiguredModel? {
        val provider = snapshot.defaultProvider.trim().takeIf { it.isNotBlank() }
        val model = snapshot.defaultModel.trim().takeIf { it.isNotBlank() }
        val models = snapshot.modelCatalog.models
        if (provider != null && model != null) {
            models.firstOrNull { it.provider == provider && it.id == model }?.let { return it }
            return AgentConfiguredModel(provider = provider, id = model)
        }
        if (provider != null) models.firstOrNull { it.provider == provider }?.let { return it }
        if (model != null) models.firstOrNull { it.id == model }?.let { return it }
        return models.firstOrNull()
    }

    private fun mergeModels(primary: List<PiModel>, fallback: List<PiModel>): List<PiModel> {
        val seen = linkedSetOf<String>()
        return (primary + fallback).filter { model ->
            val key = "${model.providerNameOrNull().orEmpty()}/${model.id}"
            seen.add(key)
        }
    }

    private fun modelLabel(session: AgentSessionRecord?): String = listOfNotNull(
        session?.provider?.takeIf { it.isNotBlank() },
        session?.model?.takeIf { it.isNotBlank() },
        session?.thinkingLevel?.takeIf { it.isNotBlank() }?.let { "thinking=$it" }
    ).joinToString(" / ")

    private companion object {
        /** 流式 delta 合并 flush 节拍，约 30fps：兼顾逐字流式的视觉流畅与状态更新开销。 */
        const val STREAM_FLUSH_INTERVAL_MS = 33L
        /** 单次 prompt 调用的超时时间。超过后中止并提示用户，避免 UI 永久卡在 working 状态。 */
        const val PROMPT_TIMEOUT_MS = 180_000L // 3 分钟
    }
}
