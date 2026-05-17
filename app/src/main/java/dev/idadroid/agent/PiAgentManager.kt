package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.env.PiWorkspaceMaterializer
import dev.idadroid.proot.ProotBinaryInstaller
import java.io.File
import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class PiAgentManager(
    context: Context,
    private val paths: EnvironmentPaths = EnvironmentPaths.of(context)
) {
    private val appContext = context.applicationContext
    private val repo = AgentSessionRepository(paths)
    private val configManager = PiConfigManager(paths)
    private val attachmentManager = AttachmentManager(appContext, paths)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }
    private val runtimes = mutableMapOf<String, PiRpcRuntime>()
    private val rawLines = ArrayDeque<String>()

    private val _state = MutableStateFlow(AgentUiState(activity = "agent 未启动"))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    fun refresh(createDefaultIfReady: Boolean = false) {
        if (!paths.rootfsDir.isDirectory) {
            _state.value = AgentUiState(activity = "rootfs 未导入")
            return
        }
        val store = repo.loadStore()
        val active = when {
            store.activeSessionId != null && store.sessions.any { it.id == store.activeSessionId } -> store.activeSessionId
            store.sessions.isNotEmpty() -> store.sessions.first().id
            createDefaultIfReady -> repo.ensureDefaultSession().id
            else -> null
        }
        val sessions = repo.loadStore().sessions
        _state.update { old ->
            val activeSession = sessions.firstOrNull { it.id == active }
            old.copy(
                sessions = sessions,
                activeSessionId = active,
                status = activeSession?.status ?: "idle",
                error = activeSession?.error,
                modelLabel = modelLabel(activeSession),
                piConfig = configManager.readSnapshot(),
                activity = if (active == null) "点击新建 Session 开始" else old.activity
            )
        }
        active?.let { loadMessages(it) }
    }

    fun createSession(name: String = "") {
        scope.launch {
            runCatching {
                requireReady()
                repo.createSession(
                    name = name.ifBlank { null },
                    provider = configManager.defaultProvider(),
                    model = configManager.defaultModel(),
                    thinkingLevel = configManager.defaultThinking()
                )
            }.onSuccess { session ->
                refresh()
                selectSession(session.id)
            }.onFailure { error -> setError("新建 Session 失败：${error.message}") }
        }
    }

    fun selectSession(id: String) {
        scope.launch {
            runCatching { repo.setActive(id) }
                .onSuccess {
                    refresh()
                    loadMessages(id)
                }
                .onFailure { error -> setError("切换 Session 失败：${error.message}") }
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
                stopSessionInternal(id)
                repo.deleteSession(id)
            }.onSuccess { refresh(createDefaultIfReady = true) }
                .onFailure { error -> setError("删除 Session 失败：${error.message}") }
        }
    }

    fun startSession(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: repo.ensureDefaultSession().id
            runCatching { startSessionInternal(sessionId) }
                .onSuccess { refreshRuntimeState(sessionId) }
                .onFailure { error -> setError("启动 pi RPC 失败：${error.message}") }
        }
    }

    fun stopSession(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            runCatching { stopSessionInternal(sessionId) }
                .onSuccess { refresh() }
                .onFailure { error -> setError("停止 pi RPC 失败：${error.message}") }
        }
    }

    fun sendPrompt(text: String, attachments: List<DraftAttachment> = emptyList(), sendMode: String? = null) {
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: run {
                val created = repo.ensureDefaultSession()
                refresh()
                created.id
            }
            val trimmed = text.trimEnd()
            if (trimmed.isBlank() && attachments.isEmpty()) return@launch
            val wasWorking = _state.value.isWorking
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
                val expanded = attachmentManager.expandFileReferencesForPrompt(displayMessage, session.cwd)
                val runtime = startSessionInternal(sessionId)
                when (sendMode) {
                    "steer" -> runtime.steer(expanded.message)
                    "followUp" -> runtime.followUp(expanded.message)
                    else -> {
                        if (wasWorking) runCatching { runtime.abort() }
                        runtime.prompt(expanded.message, expanded.images)
                    }
                }
            } catch (e: Exception) {
                setTurnActive(sessionId, false)
                appendMessage(ChatMessage(newMessageId(), "system", "发送失败：${e.message}", System.currentTimeMillis()))
                setError("发送失败：${e.message}")
            }
        }
    }

    fun abort(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            runCatching { runtimes[sessionId]?.abort() }
                .onSuccess {
                    setTurnActive(sessionId, false)
                    repo.updateRuntimeStatus(sessionId, "running", null)
                    refreshRuntimeState(sessionId)
                }
                .onFailure { error -> setError("中止失败：${error.message}") }
        }
    }

    fun loadMessages(id: String? = null) {
        scope.launch {
            val sessionId = id ?: _state.value.activeSessionId ?: return@launch
            _state.update { it.copy(messagesLoading = true) }
            val messages = withContext(Dispatchers.IO) { loadMessagesInternal(sessionId) }
            _state.update { it.copy(messages = messages, messagesLoading = false) }
        }
    }

    fun clearRawLog() {
        rawLines.clear()
        _state.update { it.copy(rawLogLines = emptyList(), stderrTail = "") }
    }

    suspend fun readDraftAttachment(uri: android.net.Uri): DraftAttachment = attachmentManager.readDraft(uri)

    fun loadSessionModels(force: Boolean = false) {
        scope.launch {
            if (_state.value.sessionModels.isNotEmpty() && !force) return@launch
            val sessionId = _state.value.activeSessionId ?: repo.ensureDefaultSession().id
            _state.update { it.copy(modelLoading = true) }
            runCatching {
                val runtime = startSessionInternal(sessionId)
                val data = runtime.availableModels()
                ((data as? JsonObject)?.get("models") as? JsonArray).orEmpty().mapNotNull { item ->
                    runCatching { json.decodeFromJsonElement<PiModel>(item) }.getOrNull()
                }
            }.onSuccess { models ->
                _state.update { it.copy(sessionModels = models, modelLoading = false) }
            }.onFailure { error ->
                _state.update { it.copy(modelLoading = false) }
                setError("加载模型失败：${error.message}")
            }
        }
    }

    fun setSessionModel(model: PiModel) {
        val provider = model.providerNameOrNull() ?: return setError("模型缺少 provider")
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                val runtime = startSessionInternal(sessionId)
                runtime.setModel(provider, model.id)
                repo.patchSession(sessionId) { it.copy(provider = provider, model = model.id, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refreshRuntimeState(sessionId) }
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
                val runtime = startSessionInternal(sessionId)
                runtime.setThinkingLevel(level)
                repo.patchSession(sessionId) { it.copy(thinkingLevel = level, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refreshRuntimeState(sessionId) }
                .onFailure { error -> setError("切换 Thinking 失败：${error.message}") }
        }
    }

    fun setAutoCompaction(enabled: Boolean) {
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                val runtime = startSessionInternal(sessionId)
                runtime.setAutoCompaction(enabled)
                repo.patchSession(sessionId) { it.copy(autoCompactionEnabled = enabled, lastActiveAt = Instant.now().toString()) }
            }.onSuccess { refreshRuntimeState(sessionId) }
                .onFailure { error -> setError("切换 Compact 失败：${error.message}") }
        }
    }

    fun compact(customInstructions: String? = null) {
        scope.launch {
            val sessionId = _state.value.activeSessionId ?: return@launch
            runCatching {
                appendMessage(ChatMessage(newMessageId(), "system", if (customInstructions.isNullOrBlank()) "已触发手动上下文压缩。" else "已触发手动上下文压缩：$customInstructions", System.currentTimeMillis()))
                startSessionInternal(sessionId).compact(customInstructions)
            }.onFailure { error -> setError("Compact 失败：${error.message}") }
        }
    }

    fun getPiConfigSnapshot(): PiConfigSnapshot = configManager.readSnapshot()

    fun savePiConfig(snapshot: PiConfigSnapshot) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { configManager.saveSnapshot(snapshot) } }
                .onSuccess { _state.update { it.copy(piConfig = configManager.readSnapshot(), activity = "Pi 配置已保存；重启 session 后生效") } }
                .onFailure { error -> setError("保存 Pi 配置失败：${error.message}") }
        }
    }

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

    suspend fun readFileText(path: String, maxBytes: Long = 512L * 1024L): String = withContext(Dispatchers.IO) {
        val file = workspaceFile(path)
        require(file.isFile) { "文件不存在：${workspaceAbsPath(path)}" }
        require(file.length() <= maxBytes) { "文件过大：${file.length() / 1024} KiB，仅支持预览 ${maxBytes / 1024} KiB 内文本" }
        file.readText(Charsets.UTF_8)
    }

    fun workspaceAbsPath(path: String): String = when {
        path.startsWith("/root/pi_workspace") -> path
        path == "." || path.isBlank() -> "/root/pi_workspace"
        else -> "/root/pi_workspace/${path.trimStart('/')}"
    }

    fun fileRef(path: String): String = attachmentManager.fileRef(path)

    private suspend fun startSessionInternal(sessionId: String): PiRpcRuntime = withContext(Dispatchers.IO) {
        requireReady()
        ProotBinaryInstaller(appContext, paths).ensureInstalled()
        PiWorkspaceMaterializer().materialize(paths.rootfsDir)
        val session = repo.patchSession(sessionId) {
            it.copy(
                status = "starting",
                error = null,
                provider = it.provider ?: configManager.defaultProvider(),
                model = it.model ?: configManager.defaultModel(),
                thinkingLevel = it.thinkingLevel ?: configManager.defaultThinking(),
                lastActiveAt = Instant.now().toString()
            )
        }
        withContext(Dispatchers.Main) {
            _state.update { it.copy(status = "starting", error = null, activity = "正在启动 pi --mode rpc...", activeSessionId = sessionId, sessions = repo.listSessions()) }
        }
        runtimes[sessionId]?.takeIf { it.isActive() }?.let { return@withContext it }
        runtimes[sessionId]?.stop()
        val events = MutableSharedFlow<PiRuntimeEvent>(extraBufferCapacity = 256)
        val runtime = PiRpcRuntime(appContext, session, events, paths)
        runtimes[sessionId] = runtime
        scope.launch(Dispatchers.Main.immediate) {
            events.collect { event -> handleRuntimeEvent(sessionId, event) }
        }
        runtime.start()
        runtime
    }

    private suspend fun stopSessionInternal(sessionId: String) {
        runtimes.remove(sessionId)?.stop()
        repo.updateRuntimeStatus(sessionId, "stopped", null)
    }

    private suspend fun refreshRuntimeState(sessionId: String) {
        val runtime = runtimes[sessionId]?.takeIf { it.isActive() } ?: run {
            refresh()
            return
        }
        val state = runCatching { runtime.getState(timeoutMs = 30_000) }.getOrNull()
        val obj = state as? JsonObject
        val sessionFile = obj?.string("sessionFile")
        if (!sessionFile.isNullOrBlank()) repo.setSessionFile(sessionId, sessionFile)
        val stats = runCatching { json.decodeFromJsonElement<SessionStats>(runtime.getStats()) }.getOrNull()
        if (obj != null) {
            val modelObj = obj.obj("model")
            repo.patchSession(sessionId) { session ->
                session.copy(
                    status = if (session.status == "starting") "running" else session.status,
                    provider = modelObj?.string("provider") ?: modelObj?.string("providerId") ?: modelObj?.string("providerName") ?: session.provider,
                    model = modelObj?.string("id") ?: session.model,
                    thinkingLevel = obj.string("thinkingLevel") ?: session.thinkingLevel,
                    autoCompactionEnabled = obj.boolean("autoCompactionEnabled") ?: session.autoCompactionEnabled,
                    lastActiveAt = Instant.now().toString()
                )
            }
        }
        refresh()
        _state.update { it.copy(activeStats = stats ?: it.activeStats) }
    }

    private suspend fun loadMessagesInternal(sessionId: String): List<ChatMessage> {
        val runtime = runtimes[sessionId]?.takeIf { it.isActive() }
        if (runtime != null) {
            val data = runCatching { runtime.getMessages() }.getOrNull()
            val messages = ((data as? JsonObject)?.get("messages") as? JsonArray)?.toList().orEmpty()
            if (messages.isNotEmpty()) return normalizePiMessages(messages)
        }
        val session = repo.listSessions().firstOrNull { it.id == sessionId } ?: return emptyList()
        val file = session.sessionFile?.let(::sessionFileToHostFile)?.takeIf { it.isFile } ?: return emptyList()
        val messages = file.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) null else runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        }
        return normalizePiMessages(messages)
    }

    private fun sessionFileToHostFile(sessionFile: String): File? {
        val normalized = sessionFile.replace('\\', '/').trim()
        val rel = when {
            normalized.startsWith("/root/pi_workspace/") -> normalized.removePrefix("/root/pi_workspace/")
            normalized.startsWith("/root/") -> return File(paths.rootfsDir, normalized.trimStart('/'))
            normalized.startsWith("/") -> return File(paths.rootfsDir, normalized.trimStart('/'))
            else -> normalized
        }
        return File(paths.rootfsDir, "root/pi_workspace/$rel")
    }

    private suspend fun handleRuntimeEvent(sessionId: String, event: PiRuntimeEvent) {
        when (event) {
            is PiRuntimeEvent.Status -> {
                repo.updateRuntimeStatus(sessionId, event.status, event.error)
                _state.update { it.copy(status = event.status, error = event.error, sessions = repo.listSessions(), activity = event.error ?: event.status) }
            }
            is PiRuntimeEvent.Stderr -> {
                val next = (_state.value.stderrTail + event.text).takeLast(12_000)
                _state.update { it.copy(stderrTail = next) }
            }
            is PiRuntimeEvent.RawStdout -> appendRaw("stdout: ${event.line}")
            is PiRuntimeEvent.Exited -> {
                repo.updateRuntimeStatus(sessionId, "error", event.error)
                _state.update { it.copy(status = "error", error = event.error, turnActive = false, sessions = repo.listSessions()) }
            }
            is PiRuntimeEvent.RpcEvent -> handleAgentEvent(sessionId, event.event)
        }
    }

    private suspend fun handleAgentEvent(sessionId: String, event: JsonObject) {
        when (event.string("type")) {
            "agent_start", "turn_start", "message_start" -> setTurnActive(sessionId, true)
            "agent_end", "turn_end", "message_end" -> {
                setTurnActive(sessionId, false)
                refreshRuntimeState(sessionId)
            }
            "message_update" -> handleMessageUpdate(sessionId, event.obj("assistantMessageEvent") ?: return)
            "tool_execution_start" -> {
                setTurnActive(sessionId, true)
                upsertTool(
                    toolCallId = event.string("toolCallId") ?: "${event.string("toolName")}-${System.currentTimeMillis()}",
                    name = event.string("toolName") ?: "tool",
                    args = event["args"],
                    result = null,
                    resultMeta = null,
                    status = "running"
                )
            }
            "tool_execution_update" -> upsertTool(
                toolCallId = event.string("toolCallId") ?: "${event.string("toolName")}-${System.currentTimeMillis()}",
                name = event.string("toolName") ?: "tool",
                args = event["args"],
                result = resultToText(event["partialResult"]),
                resultMeta = toolResultMeta(event["partialResult"]),
                status = "running"
            )
            "tool_execution_end" -> upsertTool(
                toolCallId = event.string("toolCallId") ?: "${event.string("toolName")}-${System.currentTimeMillis()}",
                name = event.string("toolName") ?: "tool",
                args = event["args"],
                result = resultToText(event["result"]),
                resultMeta = toolResultMeta(event["result"]),
                status = if (event.boolean("isError") == true) "error" else "done"
            )
            "queue_update" -> _state.update {
                it.copy(activeQueue = QueueState(event.arrayStrings("steering"), event.arrayStrings("followUp")))
            }
            "compaction_start" -> {
                setTurnActive(sessionId, true)
                appendMessage(ChatMessage(newMessageId(), "system", "正在压缩上下文：${event.string("reason") ?: "manual"}", System.currentTimeMillis()))
            }
            "compaction_end" -> {
                setTurnActive(sessionId, false)
                appendMessage(ChatMessage(newMessageId(), "system", if (event.boolean("aborted") == true) "上下文压缩已取消" else "上下文压缩完成", System.currentTimeMillis()))
                refreshRuntimeState(sessionId)
            }
            else -> appendRaw("event: ${event.string("type") ?: event.toString()}")
        }
    }

    private fun handleMessageUpdate(sessionId: String, delta: JsonObject) {
        when (delta.string("type")) {
            "start", "text_start", "thinking_start", "reasoning_start" -> setTurnActive(sessionId, true)
            "text_delta" -> applyAssistantDeltas(textDelta = delta.string("delta") ?: delta.string("text") ?: "", thinkingDelta = "")
            "thinking_delta", "reasoning_delta" -> applyAssistantDeltas(textDelta = "", thinkingDelta = delta.string("delta") ?: delta.string("text") ?: "")
            "toolcall_start", "toolcall_delta", "toolcall_end" -> {
                setTurnActive(sessionId, true)
                val tool = toolCallFromDelta(delta)
                val id = tool.toolCallId ?: delta.string("id") ?: delta.string("contentIndex") ?: "tool"
                upsertTool(id, tool.toolName ?: "tool", tool.toolArgs, null, null, "pending")
            }
            "done", "error" -> setTurnActive(sessionId, false)
        }
    }

    private fun setTurnActive(sessionId: String, active: Boolean) {
        repo.updateRuntimeStatus(sessionId, if (active) "working" else "running", null)
        _state.update { it.copy(turnActive = active, status = if (active) "working" else "running", sessions = repo.listSessions()) }
    }

    private fun appendMessage(message: ChatMessage) {
        _state.update { it.copy(messages = it.messages + message) }
    }

    private fun applyAssistantDeltas(textDelta: String, thinkingDelta: String) {
        if (textDelta.isEmpty() && thinkingDelta.isEmpty()) return
        _state.update { old ->
            val list = old.messages.toMutableList()
            val last = list.lastOrNull()
            if (last?.role == "assistant") {
                list[list.lastIndex] = last.copy(
                    text = last.text + textDelta,
                    thinking = if (thinkingDelta.isNotEmpty()) (last.thinking ?: "") + thinkingDelta else last.thinking
                )
            } else {
                list += ChatMessage(newMessageId(), "assistant", textDelta, System.currentTimeMillis(), thinking = thinkingDelta.takeIf { it.isNotEmpty() })
            }
            old.copy(messages = list)
        }
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

    private fun appendRaw(line: String) {
        rawLines += "${Instant.now()} $line"
        while (rawLines.size > 120) rawLines.removeFirst()
        _state.update { it.copy(rawLogLines = rawLines.toList()) }
    }

    private fun setError(message: String) {
        _state.update { it.copy(error = message, activity = message, status = "error", turnActive = false) }
    }

    private fun workspaceFile(path: String): File {
        val normalized = normalizeWorkspacePath(path)
        val rel = when {
            normalized == "." -> ""
            normalized.startsWith("/root/pi_workspace/") -> normalized.removePrefix("/root/pi_workspace/")
            normalized == "/root/pi_workspace" -> ""
            normalized.startsWith("/") -> error("路径必须位于 /root/pi_workspace：$path")
            else -> normalized
        }
        val root = File(paths.rootfsDir, "root/pi_workspace").canonicalFile
        val file = if (rel.isBlank()) root else File(root, rel).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "路径越界：$path" }
        return file
    }

    private fun normalizeWorkspacePath(path: String): String {
        val trimmed = path.trim().ifBlank { "." }.replace('\\', '/')
        val prefix = if (trimmed.startsWith("/root/pi_workspace")) "/root/pi_workspace" else ""
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
        val root = File(paths.rootfsDir, "root/pi_workspace").canonicalFile
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

    private fun modelLabel(session: AgentSessionRecord?): String = listOfNotNull(
        session?.provider?.takeIf { it.isNotBlank() },
        session?.model?.takeIf { it.isNotBlank() },
        session?.thinkingLevel?.takeIf { it.isNotBlank() }?.let { "thinking=$it" }
    ).joinToString(" / ")
}
