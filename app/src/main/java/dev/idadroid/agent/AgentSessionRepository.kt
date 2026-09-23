package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.util.JsonFormats
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString

class AgentSessionRepository(
    context: Context,
    private val paths: EnvironmentPaths
) {
    private val settings = dev.idadroid.settings.IdaDroidSettings(context.applicationContext)

    private val workspaceProotPath: String
        get() = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH }

    /**
     * 工作区在主机文件系统上的根目录。
     * 与 PiAgentManager.workspaceHostRoot / AttachmentManager 保持同一规则：
     * /root/xxx 在 rootfs 内；/sdcard、/storage 前缀由 proot 绑定，直接使用宿主同路径。
     */
    private val workspaceHostRoot: java.io.File get() {
        val ws = workspaceProotPath
        if (ws.startsWith("/root/")) return java.io.File(paths.rootfsDir, ws.removePrefix("/"))
        if (ws.startsWith("/sdcard") || ws.startsWith("/storage")) return java.io.File(ws)
        return java.io.File(paths.rootfsDir, ws.removePrefix("/").ifBlank { "root/pi_workspace" })
    }

    private val storeFile: java.io.File get() = java.io.File(workspaceHostRoot, ".idadroid/agent-sessions.json")

    // Protects all read/write access to storeFile so that concurrent coroutines don't race.
    private val lock = Any()

    fun loadStore(): AgentSessionStore = synchronized(lock) { loadStoreInternal() }

    private fun loadStoreInternal(): AgentSessionStore = synchronized(lock) {
        runCatching {
            if (!storeFile.isFile) return@synchronized AgentSessionStore()
            JsonFormats.pretty.decodeFromString<AgentSessionStore>(storeFile.readText())
        }.getOrDefault(AgentSessionStore())
    }

    fun saveStore(store: AgentSessionStore) { saveStoreInternal(store) }

    /** 写盘；失败返回 false（调用方决定抛错或降级）。 */
    private fun saveStoreInternal(store: AgentSessionStore): Boolean = synchronized(lock) {
        runCatching {
            val file = storeFile
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(JsonFormats.pretty.encodeToString(store))
            if (!tmp.renameTo(file)) {
                // renameTo 在某些文件系统上可能失败（如目标被占用），回退到 copy+delete
                file.writeText(tmp.readText())
                tmp.delete()
            }
            true
        }.onFailure { error ->
            android.util.Log.w("AgentSessionRepository", "保存 agent 会话失败（路径=${storeFile.absolutePath}）", error)
        }.getOrDefault(false)
    }

    /** 写盘失败即抛错，让上层把"会话未保存"如实反馈给用户，而不是 UI 看似成功实则无变化。 */
    private fun requireSaved(saved: Boolean) {
        check(saved) { "会话状态保存失败：${storeFile.absolutePath} 不可写" }
    }

    fun listSessions(): List<AgentSessionRecord> = loadStore().sessions

    fun activeSessionId(): String? = loadStore().activeSessionId

    fun ensureDefaultSession(provider: String? = null, model: String? = null, thinkingLevel: String? = null): AgentSessionRecord = synchronized(lock) {
        val store = loadStoreInternal()
        val existing = store.sessions.firstOrNull { it.id == store.activeSessionId } ?: store.sessions.firstOrNull()
        if (existing != null) {
            val patched = existing.copy(
                provider = existing.provider ?: provider?.trim()?.takeIf { it.isNotBlank() },
                model = existing.model ?: model?.trim()?.takeIf { it.isNotBlank() },
                thinkingLevel = existing.thinkingLevel ?: thinkingLevel?.trim()?.takeIf { it.isNotBlank() }
            )
            val nextSessions = if (patched == existing) store.sessions else store.sessions.map { if (it.id == existing.id) patched else it }
            if (store.activeSessionId == existing.id && patched == existing) return@synchronized existing
            // 自动兜底路径：静默保存失败，由后续显式操作（新建/切换）如实报错
            saveStoreInternal(store.copy(sessions = nextSessions, activeSessionId = existing.id))
            return@synchronized patched
        }
        val now = Instant.now().toString()
        val session = AgentSessionRecord(
            id = "session-${UUID.randomUUID()}",
            name = "默认会话",
            status = "idle",
            cwd = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH },
            provider = provider?.trim()?.takeIf { it.isNotBlank() },
            model = model?.trim()?.takeIf { it.isNotBlank() },
            thinkingLevel = thinkingLevel?.trim()?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now
        )
        // 自动兜底路径：静默保存失败，由后续显式操作（新建/切换）如实报错
        saveStoreInternal(AgentSessionStore(listOf(session), session.id))
        session
    }

    fun createSession(name: String? = null, provider: String? = null, model: String? = null, thinkingLevel: String? = null): AgentSessionRecord = synchronized(lock) {
        val store = loadStoreInternal()
        val now = Instant.now().toString()
        val session = AgentSessionRecord(
            id = "session-${UUID.randomUUID()}",
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: "Session ${store.sessions.size + 1}",
            status = "idle",
            cwd = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH },
            provider = provider?.trim()?.takeIf { it.isNotBlank() },
            model = model?.trim()?.takeIf { it.isNotBlank() },
            thinkingLevel = thinkingLevel?.trim()?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now
        )
        requireSaved(saveStoreInternal(store.copy(sessions = store.sessions + session, activeSessionId = session.id)))
        session
    }

    fun setActive(id: String): AgentSessionRecord = synchronized(lock) {
        val store = loadStoreInternal()
        val session = store.sessions.firstOrNull { it.id == id } ?: error("session 不存在：$id")
        requireSaved(saveStoreInternal(store.copy(activeSessionId = id)))
        session
    }

    fun patchSession(id: String, patch: (AgentSessionRecord) -> AgentSessionRecord): AgentSessionRecord = synchronized(lock) {
        val store = loadStoreInternal()
        var updated: AgentSessionRecord? = null
        val sessions = store.sessions.map { current ->
            if (current.id == id) {
                patch(current).copy(updatedAt = Instant.now().toString()).also { updated = it }
            } else current
        }
        val result = updated ?: error("session 不存在：$id")
        requireSaved(saveStoreInternal(store.copy(sessions = sessions, activeSessionId = store.activeSessionId ?: id)))
        result
    }

    fun deleteSession(id: String) = synchronized(lock) {
        val store = loadStoreInternal()
        val nextSessions = store.sessions.filterNot { it.id == id }
        val nextActive = when {
            store.activeSessionId != id -> store.activeSessionId
            nextSessions.isNotEmpty() -> nextSessions.first().id
            else -> null
        }
        requireSaved(saveStoreInternal(store.copy(sessions = nextSessions, activeSessionId = nextActive)))
    }

    fun updateRuntimeStatus(id: String, status: String, error: String? = null): AgentSessionRecord? = runCatching {
        patchSession(id) { it.copy(status = status, error = error ?: it.error, lastActiveAt = Instant.now().toString()) }
    }.getOrNull()

    fun setSessionFile(id: String, sessionFile: String?): AgentSessionRecord? {
        val value = sessionFile?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { patchSession(id) { it.copy(sessionFile = value, lastActiveAt = Instant.now().toString()) } }.getOrNull()
    }
}
