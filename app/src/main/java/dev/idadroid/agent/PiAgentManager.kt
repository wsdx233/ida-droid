package dev.idadroid.agent

import android.content.Context
import android.net.Uri
import dev.idadroid.env.EnvironmentPaths
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.idadroid.util.runCatchingSuspend
import dev.idadroid.proot.IdaProotRuntime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class PiAgentManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) : java.io.Closeable {
    private val appContext = context.applicationContext
    private val settings = dev.idadroid.settings.IdaDroidSettings(appContext)
    private val containerFiles = dev.idadroid.files.ContainerFileManager(appContext, paths)
    private val workspaceProotPath get() = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH }
    private val workspaceHostRoot get() = containerFiles.rootfsFile(workspaceProotPath)
    private val repo = AgentSessionRepository(appContext, paths)
    private val configManager = PiConfigManager(appContext, paths)
    private val attachmentManager = AttachmentManager(appContext, paths)
    val workspaceManager = WorkspaceManager(appContext, paths)
    private val deepIndexToolChain = dev.idadroid.deepindex.DeepIndexToolChain(appContext, paths)
    private val runtime = IdaProotRuntime(appContext, paths)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Workspace observation must survive disconnect(), which cancels PI operations.
    private val workspaceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(AgentUiState(activity = "PI 未启动"))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()
    private var transport: PiRpcTransport? = null
    private var boundSession: String? = null
    private var generation = 0L
    private var messageRevision = 0L
    private var turnSerial = 0L
    private data class QueuedPrompt(val wireText: String, val displayText: String, val mode: String)
    private val queuedPrompts = mutableListOf<QueuedPrompt>()
    private var submitting = false
    private var retrying = false
    private var compacting = false
    private var monitor: Job? = null
    private var streamingId: String? = null
    private var streamingBase = emptyList<ChatMessage>()
    private var closed = false
    private var workspaceListing: Job? = null
    private var workspaceListingRevision = 0L

    init {
        workspaceScope.launch {
            settings.envSettings.map { it.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH } }
                .distinctUntilChanged().drop(1).collect { path ->
                    disconnect()
                    _state.value = AgentUiState(activity = "工作区已切换，正在读取配置与会话",
                        workspace = WorkspaceState(workspacePath = path))
                    refresh()
                    refreshWorkspaceFiles("")
                }
        }
    }

    private fun publishQueue() {
        _state.update { it.copy(activeQueue = QueueState(
            queuedPrompts.filter { item -> item.mode == "steer" }.map { item -> item.displayText },
            queuedPrompts.filter { item -> item.mode == "followUp" }.map { item -> item.displayText })) }
    }

    /** Application-owned: never close on screen/activity recreation. */
    override fun close() { closed = true; disconnect(); scope.cancel(); workspaceScope.cancel() }

    private fun disconnect() {
        generation++
        scope.coroutineContext.cancelChildren()
        monitor?.cancel(); monitor = null
        transport?.close(); transport = null; boundSession = null
        submitting = false; retrying = false; compacting = false
        queuedPrompts.clear()
        streamingId = null; streamingBase = emptyList()
        _state.update { it.copy(turnActive = false, processingPhase = null, messagesLoading = false, modelLoading = false, activeQueue = QueueState()) }
    }

    private fun operation(block: suspend () -> Unit) {
        val epoch = generation
        scope.launch {
            operations.withLock {
                if (closed || epoch != generation) return@withLock
                try { block() }
                catch (e: CancellationException) {
                    if (epoch == generation && e is TimeoutCancellationException) fail("PI command timed out")
                    else throw e
                }
                catch (e: Exception) { if (epoch == generation) fail(e.message ?: "PI operation failed") }
            }
        }
    }

    private fun fail(message: String) { val safe = configManager.redact(message); disconnect(); setError(safe) }

    fun refresh(createDefaultIfReady: Boolean = false) = operation {
        if (!paths.rootfsDir.isDirectory) {
            _state.update { it.copy(activity = "rootfs 未导入", status = "idle") }; return@operation
        }
        val snapshot = withContext(Dispatchers.IO) { configManager.readSnapshot() }
        val store = withContext(Dispatchers.IO) {
            if (createDefaultIfReady && repo.listSessions().isEmpty()) createDefaultSessionWithConfiguredModel()
            repo.loadStore()
        }
        val hasWorkspace = withContext(Dispatchers.IO) { workspaceManager.hasWorkspace }
        val active = _state.value.activeSessionId?.takeIf { id -> store.sessions.any { it.id == id } }
            ?: store.activeSessionId ?: store.sessions.firstOrNull()?.id
        _state.update { old -> old.copy(sessions = store.sessions, activeSessionId = active, piConfig = snapshot,
            modelLabel = modelLabel(store.sessions.firstOrNull { it.id == active }),
            workspace = old.workspace.copy(hasWorkspace = hasWorkspace,
                workspaceName = workspaceManager.currentWorkspaceName, workspacePath = workspaceManager.currentWorkspacePath)) }
        if (active != null && !_state.value.turnActive && boundSession == null) readMessages(active)
    }

    fun createSession(name: String = "") {
        disconnect()
        _state.update { it.copy(status = "starting", activity = "正在新建会话", messages = emptyList(), error = null) }
        operation {
            requireReady()
            val session = withContext(Dispatchers.IO) {
                val cfg = configManager.readSnapshot(); val model = resolveDefaultModel(cfg)
                repo.createSession(name, model?.provider, model?.id, cfg.defaultThinkingLevel)
            }
            _state.update { it.copy(activeSessionId = session.id, activeStats = null) }
            // A failed PI startup must not hide the session already saved on disk.
            publishSessions()
            readMessages(session.id)
        }
    }

    fun selectSession(id: String) {
        if (id == _state.value.activeSessionId && transport != null) return
        disconnect()
        _state.update { it.copy(activeSessionId = id, messages = emptyList(), activeStats = null, status = "starting", activity = "正在加载会话", error = null) }
        operation { withContext(Dispatchers.IO) { repo.setActive(id) }; readMessages(id) }
    }

    fun renameSession(id: String, name: String) {
        if (name.isBlank()) return
        if (id != _state.value.activeSessionId) selectSession(id)
        operation {
            connection(id).request("set_session_name", fields("name" to name.trim()))
            withContext(Dispatchers.IO) { repo.patchSession(id) { it.copy(name = name.trim()) } }
            publishSessions()
        }
    }

    fun deleteSession(id: String) {
        if (id == _state.value.activeSessionId) disconnect()
        operation {
            withContext(Dispatchers.IO) {
                val session = repo.listSessions().firstOrNull { it.id == id } ?: return@withContext
                session.sessionFile?.let { path ->
                    val source = guestFile(path)
                    if (source.isFile) {
                        val trash = File(workspaceHostRoot, ".idadroid/deleted-sessions").apply { mkdirs() }
                        check(source.renameTo(File(trash, "${newMessageId()}-${source.name}"))) { "Cannot archive session; nothing deleted" }
                    }
                }
                repo.deleteSession(id)
            }
            val store = withContext(Dispatchers.IO) { repo.loadStore() }
            _state.update { old -> if (old.activeSessionId == id) old.copy(activeSessionId = store.activeSessionId, messages = emptyList(), sessions = store.sessions, status = "idle", activeStats = null)
                else old.copy(sessions = store.sessions) }
            if (boundSession == null) store.activeSessionId?.let { readMessages(it) }
        }
    }

    fun startSession(id: String? = null) {
        val target = id ?: _state.value.activeSessionId
        if (target != null && target != _state.value.activeSessionId) { selectSession(target); return }
        if (_state.value.status == "starting" || transport != null) return
        _state.update { it.copy(status = "starting", activity = "正在启动 PI", error = null) }
        operation {
            val session = target ?: withContext(Dispatchers.IO) { createDefaultSessionWithConfiguredModel().id }
            _state.update { it.copy(activeSessionId = session) }; readMessages(session)
        }
    }

    fun stopSession(id: String? = null) {
        val target = id ?: _state.value.activeSessionId ?: return
        if (target != _state.value.activeSessionId) return
        disconnect()
        _state.update { it.copy(status = "stopped", activity = "PI 已停止", error = null) }
        operation { withContext(Dispatchers.IO) { repo.updateRuntimeStatus(target, "stopped") }; publishSessions() }
    }

    /** Synchronous admission: false means the composer must retain its draft. */
    fun sendPrompt(text: String, attachments: List<DraftAttachment> = emptyList(), sendMode: String? = null): Boolean {
        val queueMode = when (sendMode) { "steer" -> "steer"; "followUp", "follow_up" -> "followUp"; else -> null }
        if (closed || submitting || (text.isBlank() && attachments.isEmpty()) || !paths.readyMarker.isFile) return false
        if (_state.value.status == "starting" || (_state.value.turnActive && queueMode == null)) return false
        val queued = _state.value.turnActive
        if (!queued) turnSerial++
        submitting = true
        val epoch = generation
        _state.update { it.copy(turnActive = true, status = "working", error = null, activity = if (queued) "正在排队" else "正在发送…",
            processingPhase = if (queued) it.processingPhase else "connecting", promptSentAt = if (queued) it.promptSentAt else System.currentTimeMillis(), firstDeltaAt = if (queued) it.firstDeltaAt else 0L) }
        operation {
            var queuedPrompt: QueuedPrompt? = null
            try {
                val id = _state.value.activeSessionId ?: withContext(Dispatchers.IO) { createDefaultSessionWithConfiguredModel().id }
                _state.update { it.copy(activeSessionId = id) }
                val rpc = connection(id)
                val stored = attachmentManager.storeAttachments(attachments)
                val prompt = buildString {
                    append(text.trimEnd())
                    stored.forEach { append(' '); append(attachmentManager.fileRef(it.prootPath)) }
                    if (deepIndexToolChain.isEnabled()) append("\nUse the installed deep-index toolchain (CodeGraph + ECC + Memory) for this task.")
                }
                val expanded = attachmentManager.expandFileReferencesForPrompt(prompt)
                val images = expanded.images.toMutableList()
                stored.filter { it.isImage }.forEach { item ->
                    val image = item.display as ChatAttachment.Image
                    if (images.none { it.data == image.data }) images += ImagePayload(data = image.data, mimeType = image.mimeType)
                }
                if (queued && queueMode != null) {
                    queuedPrompt = QueuedPrompt(expanded.message, text.ifBlank { attachmentSummary(stored.map { it.display }) }, queueMode)
                    queuedPrompts += queuedPrompt
                    publishQueue()
                }
                rpc.request("prompt", buildJsonObject {
                    put("message", expanded.message)
                    if (images.isNotEmpty()) put("images", json.encodeToJsonElement(images))
                    if (queued && queueMode != null) put("streamingBehavior", queueMode)
                })
                if (epoch == generation) { _state.update { it.copy(activity = if (it.turnActive) "PI 已接受消息" else it.activity) }; startMonitor(epoch, id) }
            } catch (error: PiRpcCommandException) {
                queuedPrompt?.let { queuedPrompts.remove(it); publishQueue() }
                if (!queued) throw error
                _state.update { it.copy(error = configManager.redact(error.message ?: "PI rejected queued message")) }
            } finally { if (epoch == generation) submitting = false }
        }
        return true
    }

    fun abort(id: String? = null) {
        val target = id ?: _state.value.activeSessionId ?: return
        if (target != _state.value.activeSessionId) return
        if (submitting || transport == null) { stopSession(target); return }
        val epoch = generation
        val rpc = transport ?: return
        scope.launch {
            try { rpc.request("abort", timeoutMs = 5_000) }
            catch (_: Exception) { /* Hard stop below also handles hung tools/preflight. */ }
            if (epoch == generation) {
                disconnect()
                _state.update { it.copy(status = "stopped", activity = "已取消", error = null) }
                operation { withContext(Dispatchers.IO) { repo.updateRuntimeStatus(target, "stopped") }; publishSessions() }
            }
        }
    }

    fun loadMessages(id: String? = null) = operation {
        val target = id ?: _state.value.activeSessionId ?: return@operation
        if (target == _state.value.activeSessionId && !_state.value.turnActive) readMessages(target)
    }

    private suspend fun readMessages(id: String) {
        _state.update { it.copy(messagesLoading = true) }
        try {
            val rpc = connection(id)
            val revision = messageRevision
            val data = rpc.request("get_messages")
            if (!_state.value.turnActive && messageRevision == revision && _state.value.activeSessionId == id)
                _state.update { it.copy(messages = normalizePiMessages((data["messages"] as? JsonArray).orEmpty())) }
        } finally { _state.update { it.copy(messagesLoading = false) } }
    }

    fun clearRawLog() {
        _state.update { it.copy(rawLogLines = emptyList(), stderrTail = "") }
    }

    // ==================== 工作区相关 ====================

    /** Select the same container directory used by PI, sessions and attachments. */
    fun setWorkspace(path: String, create: Boolean = false, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        workspaceScope.launch {
            try {
                val selected = operations.withLock {
                    check(!closed) { "Agent 已关闭" }
                    check(!_state.value.turnActive && _state.value.status != "starting") { "请先停止当前任务再切换工作区" }
                    val previous = workspaceProotPath
                    val next = workspaceManager.setWorkspace(path, create)
                    if (next == previous) {
                        refresh()
                        refreshWorkspaceFiles("")
                    }
                    next
                }
                val loaded = _state.first { state ->
                    state.workspace.workspacePath == selected &&
                        ((state.piConfig.materializedDir.isNotBlank() && !state.workspace.loading) ||
                            state.workspace.error != null ||
                            (state.status == "error" && state.piConfig.materializedDir.isBlank()))
                }
                check(loaded.workspace.error == null) { loaded.workspace.error.orEmpty() }
                check(loaded.piConfig.materializedDir.isNotBlank()) { loaded.error ?: "无法读取工作区配置" }
                onResult(true, selected)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = "设置工作区失败：${error.message.orEmpty()}"
                _state.update { it.copy(workspace = it.workspace.copy(error = message)) }
                onResult(false, message)
            }
        }
    }

    fun resetWorkspace() = setWorkspace(dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH, create = true)

    /** Only the newest request may publish its directory listing. */
    fun refreshWorkspaceFiles(path: String = _state.value.workspace.currentPath) {
        workspaceListing?.cancel()
        val revision = ++workspaceListingRevision
        val root = workspaceProotPath
        workspaceListing = workspaceScope.launch {
            _state.update { it.copy(workspace = it.workspace.copy(loading = true, error = null)) }
            try {
                val files = workspaceManager.listFiles(path)
                if (root != workspaceProotPath || revision != workspaceListingRevision) return@launch
                _state.update { it.copy(workspace = it.workspace.copy(files = files, currentPath = path, loading = false)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (root == workspaceProotPath && revision == workspaceListingRevision) {
                    _state.update { it.copy(workspace = it.workspace.copy(loading = false,
                        error = "读取工作区失败：${error.message.orEmpty()}")) }
                }
            }
        }
    }

    /** 进入工作区子目录。 */
    fun navigateWorkspace(relativePath: String) {
        val next = workspaceManager.resolvePath(_state.value.workspace.currentPath, relativePath)
        refreshWorkspaceFiles(next)
    }

    /** 读取工作区文件作为草稿附件（用于发送给 Agent）。 */
    suspend fun readWorkspaceFileAsAttachment(entry: WorkspaceFileEntry): DraftAttachment =
        workspaceManager.readAsDraftAttachment(entry)

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
    suspend fun readWorkspaceFileAsText(entry: WorkspaceFileEntry): String = workspaceManager.readAsText(entry)

    suspend fun readDraftAttachment(uri: android.net.Uri): DraftAttachment = attachmentManager.readDraft(uri)

    fun loadSessionModels(force: Boolean = false) = operation {
        if (!force && _state.value.sessionModels.isNotEmpty()) return@operation
        val id = _state.value.activeSessionId ?: return@operation
        _state.update { it.copy(modelLoading = true) }
        try {
            val data = connection(id).request("get_available_models")
            _state.update { it.copy(sessionModels = (data["models"] as? JsonArray).orEmpty().map { model -> json.decodeFromJsonElement<PiModel>(model) }) }
        } finally { _state.update { it.copy(modelLoading = false) } }
    }

    fun setSessionModel(model: PiModel) = operation {
        check(!_state.value.turnActive) { "Stop the active turn before changing models" }
        val id = _state.value.activeSessionId ?: return@operation
        val rpc = connection(id)
        rpc.request("set_model", fields("provider" to (model.providerNameOrNull() ?: error("Missing provider")), "modelId" to model.id))
        persistRuntimeState(id, rpc.request("get_state"))
    }

    fun setSessionModelManual(provider: String, modelId: String) {
        if (provider.isBlank() || modelId.isBlank()) return setError("provider/model 不能为空")
        setSessionModel(PiModel(id = modelId.trim(), provider = provider.trim()))
    }

    fun chooseThinking(level: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val id = _state.value.activeSessionId
        if (id == null) {
            onResult(false, "请先创建或选择会话")
            return
        }
        val epoch = generation
        scope.launch {
            try {
                operations.withLock {
                    if (closed || epoch != generation) {
                        onResult(false, "会话已切换，请重新选择思考强度")
                        return@withLock
                    }
                    val rpc = connection(id)
                    val before = rpc.request("get_state")
                    val model = before["model"] as? JsonObject
                    if (level != "off" && model?.boolean("reasoning") != true) {
                        persistRuntimeState(id, before)
                        val message = if (model == null) "PI 未选择模型，请先选择模型再设置思考强度"
                            else "当前模型未声明支持思考/推理，PI 只能使用 off。若模型实际支持，请在 AI 配置中开启该模型的「支持思考/推理」，保存后重新启动 PI；否则请选择支持推理的模型。"
                        _state.update { it.copy(error = message, activity = message) }
                        onResult(false, message)
                        return@withLock
                    }
                    rpc.request("set_thinking_level", fields("level" to level))
                    val effective = rpc.request("get_state")
                    val applied = effective.string("thinkingLevel") ?: error("PI 未返回实际思考强度，无法确认设置")
                    persistRuntimeState(id, effective)
                    val accepted = applied == level
                    val message = if (accepted) "思考强度已设置为 $applied"
                        else "当前模型未接受 $level，PI 实际应用了 $applied。请选择模型支持的强度。"
                    _state.update { it.copy(error = if (accepted) null else message, activity = message) }
                    onResult(accepted, message)
                }
            } catch (error: CancellationException) {
                onResult(false, "思考强度设置已中断，未确认应用；请重新连接后查看实际状态")
                if (epoch == generation && error is TimeoutCancellationException) fail("PI command timed out")
                else throw error
            } catch (error: PiRpcCommandException) {
                val message = configManager.redact(error.message ?: "PI 拒绝设置思考强度")
                if (epoch == generation) _state.update { it.copy(error = message, activity = message) }
                onResult(false, message)
            } catch (error: Exception) {
                val message = configManager.redact(error.message ?: "思考强度设置失败")
                if (epoch == generation) fail(message)
                onResult(false, message)
            }
        }
    }

    fun setAutoCompaction(enabled: Boolean) = operation {
        val id = _state.value.activeSessionId ?: return@operation
        val rpc = connection(id)
        rpc.request("set_auto_compaction", buildJsonObject { put("enabled", enabled) })
        persistRuntimeState(id, rpc.request("get_state"))
    }

    fun compact(customInstructions: String? = null) {
        if (_state.value.turnActive || _state.value.activeSessionId == null) return
        _state.update { it.copy(turnActive = true, processingPhase = "compacting", status = "working", activity = "正在压缩上下文", error = null) }
        operation {
            val id = _state.value.activeSessionId ?: return@operation
            connection(id).request("compact", buildJsonObject { customInstructions?.let { put("customInstructions", it) } }, timeoutMs = 180_000)
            finishTurn(id)
        }
    }

    fun refreshStats() = operation {
        val id = _state.value.activeSessionId ?: return@operation
        val data = connection(id).request("get_session_stats")
        _state.update { it.copy(activeStats = json.decodeFromJsonElement<SessionStats>(data)) }
    }

    fun clearQueue() = operation {
        val id = _state.value.activeSessionId ?: return@operation
        try {
            connection(id).request("clear_queue")
            _state.update { it.copy(activeQueue = QueueState()) }
        } catch (_: PiRpcCommandException) {
            _state.update { it.copy(activity = "此 PI 版本不支持清空队列，请停止本轮以取消排队消息") }
        }
    }

    fun setQueueModes(steering: String, followUp: String) = operation {
        val id = _state.value.activeSessionId ?: return@operation
        val rpc = connection(id)
        rpc.request("set_steering_mode", fields("mode" to steering))
        rpc.request("set_follow_up_mode", fields("mode" to followUp))
    }

    fun getPiConfigSnapshot(): PiConfigSnapshot = _state.value.piConfig

    suspend fun savePiConfig(snapshot: PiConfigSnapshot): Boolean {
        if (_state.value.turnActive || _state.value.status == "starting") {
            _state.update { it.copy(error = "请先停止当前任务再保存 PI 配置", activity = "请先停止当前任务再保存 PI 配置") }
            return false
        }
        disconnect()
        val epoch = generation
        return operations.withLock {
            try {
                check(!closed && epoch == generation) { "工作区或会话已切换，请重新打开配置" }
                val saved = withContext(Dispatchers.IO) {
                    val current = configManager.readSnapshot()
                    require(snapshot.materializedDir.isBlank() || snapshot.materializedDir == current.materializedDir) {
                        "工作区已切换，请重新打开配置"
                    }
                    configManager.saveSnapshot(snapshot)
                    configManager.materialize()
                    configManager.readSnapshot()
                }
                check(epoch == generation) { "工作区或会话已切换，请重新打开配置" }
                _state.update { it.copy(piConfig = saved, sessionModels = emptyList(), status = "idle",
                    activity = "PI 配置已保存，下次启动生效", error = null) }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch == generation) setError(configManager.redact(error.message ?: "PI 配置保存失败"))
                false
            }
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

    private suspend fun connection(id: String): PiRpcTransport {
        transport?.takeIf { boundSession == id }?.let { return it }
        check(transport == null) { "Cannot bind two sessions to one PI process" }
        requireReady()
        val epoch = generation
        val session = withContext(Dispatchers.IO) {
            val record = repo.setActive(id)
            val originalPath = record.sessionFile ?: "$workspaceProotPath/.idadroid/sessions/$id.jsonl"
            val original = guestFile(originalPath)
            if (original.isFile) {
                val destinationPath = "$workspaceProotPath/.idadroid/pi-sessions/${newMessageId()}.jsonl"
                val migrated = PiSessionMigration.migrate(original, guestFile(destinationPath), record.cwd.ifBlank { workspaceProotPath }, record.provider, record.model)
                if (migrated != original) repo.setSessionFile(id, destinationPath) else record.copy(sessionFile = originalPath)
            } else {
                require(record.sessionFile == null || record.hasPersistedHistory == false) { "Session history is missing; restore the original before continuing" }
                record
            }
        }
        val spec = withContext(Dispatchers.IO) {
            val dir = configManager.materialize()
            val args = mutableListOf("pi", "--mode", "rpc", "--session-dir", "$workspaceProotPath/.idadroid/pi-sessions")
            session.sessionFile?.takeIf { guestFile(it).isFile }?.let { args += listOf("--session", it) }
            session.provider?.let { args += listOf("--provider", it) }
            session.model?.let { args += listOf("--model", it) }
            session.thinkingLevel?.let { args += listOf("--thinking", it) }
            args += configManager.extraArgs()
            runtime.commandSpec(configManager.runtimeEnvExports() + "\nexport PI_CODING_AGENT_DIR=" + IdaProotRuntime.shellQuote(dir) + "\nexec " + args.joinToString(" ") { IdaProotRuntime.shellQuote(it) }, session.cwd.ifBlank { workspaceProotPath })
        }
        var launched: Process? = null
        val process = try {
            withContext(Dispatchers.IO) { ProcessBuilder(spec.command).directory(spec.workingDirectory).apply { environment().putAll(spec.environment) }.start().also { launched = it } }
        } catch (e: CancellationException) { launched?.destroyForcibly(); throw e }
        if (epoch != generation) { process.destroyForcibly(); throw CancellationException("Session changed") }
        val rpc = PiRpcTransport(process,
            onEvent = { event -> scope.launch {
                if (epoch == generation) try { handleEvent(epoch, id, event) }
                catch (e: Exception) { if (epoch == generation) fail(e.message ?: "PI event failed") }
            } },
            onFailure = { error -> scope.launch { if (epoch == generation) fail(error.message ?: "PI exited") } },
            onStderr = { scope.launch { if (epoch == generation) _state.update { it.copy(stderrTail = "PI emitted diagnostic stderr. Raw output is hidden to protect credentials; command errors appear above.") } } })
        transport = rpc; boundSession = id
        val runtimeState = rpc.request("get_state")
        session.autoCompactionEnabled?.let { rpc.request("set_auto_compaction", buildJsonObject { put("enabled", it) }) }
        if (session.name.isNotBlank()) rpc.request("set_session_name", fields("name" to session.name))
        persistRuntimeState(id, runtimeState)
        if (_state.value.turnActive) {
            val history = rpc.request("get_messages")
            _state.update { it.copy(messages = normalizePiMessages((history["messages"] as? JsonArray).orEmpty())) }
        }
        _state.update { it.copy(status = if (it.turnActive) "working" else "running", activity = if (it.turnActive) "正在发送…" else "PI 已就绪", error = null) }
        return rpc
    }

    private suspend fun persistRuntimeState(id: String, data: JsonObject) {
        val model = data["model"] as? JsonObject
        withContext(Dispatchers.IO) {
            repo.patchSession(id) { record ->
                val file = data.string("sessionFile") ?: record.sessionFile
                record.copy(sessionFile = file, hasPersistedHistory = record.hasPersistedHistory == true || file?.let { guestFile(it).isFile } == true,
                    provider = model?.string("provider") ?: record.provider, model = model?.string("id") ?: record.model,
                    thinkingLevel = data.string("thinkingLevel") ?: record.thinkingLevel,
                    autoCompactionEnabled = data.boolean("autoCompactionEnabled") ?: record.autoCompactionEnabled, status = "running", error = null)
            }
        }
        publishSessions()
    }

    private suspend fun publishSessions() {
        val sessions = withContext(Dispatchers.IO) { repo.listSessions() }
        _state.update { old -> old.copy(sessions = sessions, modelLabel = modelLabel(sessions.firstOrNull { it.id == old.activeSessionId })) }
    }

    private fun startMonitor(epoch: Long, id: String) {
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            try {
                // Probe responsiveness, never impose an arbitrary maximum duration on valid tools.
                while (epoch == generation && _state.value.turnActive) {
                    delay(1500)
                    val rpc = transport ?: break
                    val data = rpc.request("get_state", timeoutMs = 15_000)
                    if (epoch != generation) break
                    if (!submitting && !retrying && !compacting && data.boolean("isStreaming") != true && data.boolean("isCompacting") != true && (data.int("pendingMessageCount") ?: 0) == 0) {
                        finishTurn(id); break
                    }
                }
            } catch (e: CancellationException) { if (epoch == generation && e is TimeoutCancellationException) fail("PI became unresponsive") }
            catch (e: Exception) { if (epoch == generation) fail(e.message ?: "PI became unavailable") }
        }
    }

    private fun finishTurn(id: String) {
        val nextQueue = QueueState(queuedPrompts.filter { it.mode == "steer" }.map { it.displayText }, queuedPrompts.filter { it.mode == "followUp" }.map { it.displayText })
        _state.update { it.copy(turnActive = nextQueue.steering.isNotEmpty() || nextQueue.followUp.isNotEmpty(), processingPhase = null, status = if (it.error == null) "running" else "error", activity = if (it.error == null) "已完成" else "本轮失败", activeQueue = nextQueue) }
        streamingId = null
        operation {
            if (_state.value.activeSessionId == id && !_state.value.turnActive) {
                readMessages(id)
                val rpc = transport ?: return@operation
                persistRuntimeState(id, rpc.request("get_state"))
                val stats = rpc.request("get_session_stats")
                _state.update { it.copy(activeStats = json.decodeFromJsonElement<SessionStats>(stats)) }
            }
        }
    }

    private suspend fun handleEvent(epoch: Long, id: String, event: JsonObject) {
        if (id != _state.value.activeSessionId) return
        when (event.string("type")) {
            "agent_start" -> { _state.update { it.copy(turnActive = true, status = "working", processingPhase = "receiving") }; startMonitor(epoch, id) }
            "agent_end" -> startMonitor(epoch, id)
            "agent_settled" -> { retrying = false; compacting = false; finishTurn(id) }
            "message_start" -> {
                val message = event["message"] as? JsonObject ?: return
                if (message.string("role") !in setOf("assistant", "user")) return
                messageRevision++
                if (message.string("role") == "user") {
                    val index = queuedPrompts.indexOfFirst { it.wireText == contentToText(message["content"]) }
                    if (index >= 0) { queuedPrompts.removeAt(index); publishQueue() }
                }
                streamingBase = _state.value.messages
                streamingId = newMessageId()
                if (message.string("role") == "assistant") appendMessage(ChatMessage(streamingId!!, "assistant", "", System.currentTimeMillis()))
                else _state.update { it.copy(messages = streamingBase + normalizePiMessages(listOf(message))) }
            }
            "message_update" -> {
                messageRevision++
                val update = event["assistantMessageEvent"] as? JsonObject ?: return
                val delta = update.string("delta").orEmpty()
                if (update.string("type") in setOf("text_delta", "thinking_delta")) {
                    val thinking = update.string("type") == "thinking_delta"
                    _state.update { old -> old.copy(processingPhase = "receiving", firstDeltaAt = old.firstDeltaAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        messages = old.messages.map { message -> if (message.id == streamingId) {
                            if (thinking) message.copy(thinking = message.thinking.orEmpty() + delta) else message.copy(text = message.text + delta)
                        } else message }) }
                }
            }
            "message_end" -> {
                val message = event["message"] as? JsonObject ?: return
                messageRevision++
                if (message.string("role") in setOf("assistant", "user")) {
                    _state.update { it.copy(messages = streamingBase + normalizePiMessages(listOf(message)).mapIndexed { index, item -> item.copy(id = "${streamingId ?: newMessageId()}-$index") }) }
                }
                if (message.string("role") == "assistant" && message.string("stopReason") == "error") {
                    val error = configManager.redact(message.string("errorMessage") ?: "PI provider request failed")
                    _state.update { it.copy(error = error, activity = error) }
                }
            }
            "tool_execution_start", "tool_execution_update", "tool_execution_end" -> {
                messageRevision++
                val end = event.string("type") == "tool_execution_end"
                val result = event["result"] ?: event["partialResult"]
                _state.update { it.copy(processingPhase = "executing_tool") }
                upsertTool(event.string("toolCallId") ?: return, event.string("toolName") ?: "tool", event["args"], result?.let(::resultToText), result?.let(::toolResultMeta), if (!end) "running" else if (event.boolean("isError") == true) "error" else "done")
            }
            "auto_retry_start", "summarization_retry_scheduled" -> { retrying = true; _state.update { it.copy(processingPhase = "connecting", activity = "PI 正在重试") } }
            "auto_retry_end", "summarization_retry_finished" -> {
                retrying = false
                event.string("finalError")?.let { error -> _state.update { it.copy(error = configManager.redact(error)) } }
            }
            "auto_compaction_start", "compaction_start" -> { compacting = true; _state.update { it.copy(processingPhase = "compacting") } }
            "auto_compaction_end", "compaction_end" -> {
                compacting = false
                event.string("errorMessage")?.let { error -> _state.update { it.copy(error = configManager.redact(error)) } }
            }
            "queue_update" -> _state.update { it.copy(activeQueue = QueueState(event.strings("steering"), event.strings("followUp"))) }
            "extension_ui_request" -> {
                if (event.string("method") in setOf("select", "confirm", "input", "editor")) {
                    transport?.cancelExtensionDialog(event.string("id") ?: return)
                    _state.update { it.copy(activity = "扩展交互已取消：此界面不支持扩展对话框") }
                } else event.string("message")?.let { message -> _state.update { it.copy(activity = configManager.redact(message)) } }
            }
            "extension_error" -> _state.update { it.copy(error = configManager.redact(event.string("error") ?: "PI extension failed")) }
        }
    }

    private fun JsonObject.strings(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun fields(vararg entries: Pair<String, String>) = JsonObject(entries.associate { it.first to JsonPrimitive(it.second) })
    private fun guestFile(path: String): File = if (path == "/sdcard" || path.startsWith("/sdcard/") || path == "/storage" || path.startsWith("/storage/")) File(path)
        else if (path.startsWith('/')) File(paths.rootfsDir, path.removePrefix("/")) else File(workspaceHostRoot, path)

    private fun newMessageId(): String = java.util.UUID.randomUUID().toString()

    private fun appendMessage(message: ChatMessage) {
        _state.update { it.copy(messages = it.messages + message) }
    }

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

}
