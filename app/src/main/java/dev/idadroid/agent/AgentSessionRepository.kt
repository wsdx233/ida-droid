package dev.idadroid.agent

import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.util.JsonFormats
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString

class AgentSessionRepository(
    private val paths: EnvironmentPaths
) {
    private val storeFile get() = java.io.File(paths.rootfsDir, "root/pi_workspace/.idadroid/agent-sessions.json")

    fun loadStore(): AgentSessionStore = runCatching {
        if (!storeFile.isFile) return AgentSessionStore()
        JsonFormats.pretty.decodeFromString<AgentSessionStore>(storeFile.readText())
    }.getOrDefault(AgentSessionStore())

    fun saveStore(store: AgentSessionStore) {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(JsonFormats.pretty.encodeToString(store))
    }

    fun listSessions(): List<AgentSessionRecord> = loadStore().sessions

    fun activeSessionId(): String? = loadStore().activeSessionId

    fun ensureDefaultSession(): AgentSessionRecord {
        val store = loadStore()
        val existing = store.sessions.firstOrNull { it.id == store.activeSessionId } ?: store.sessions.firstOrNull()
        if (existing != null) {
            if (store.activeSessionId == existing.id) return existing
            saveStore(store.copy(activeSessionId = existing.id))
            return existing
        }
        val now = Instant.now().toString()
        val session = AgentSessionRecord(
            id = "session-${UUID.randomUUID()}",
            name = "默认会话",
            status = "idle",
            cwd = "/root/pi_workspace",
            createdAt = now,
            updatedAt = now
        )
        saveStore(AgentSessionStore(listOf(session), session.id))
        return session
    }

    fun createSession(name: String? = null, provider: String? = null, model: String? = null, thinkingLevel: String? = null): AgentSessionRecord {
        val store = loadStore()
        val now = Instant.now().toString()
        val session = AgentSessionRecord(
            id = "session-${UUID.randomUUID()}",
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: "Session ${store.sessions.size + 1}",
            status = "idle",
            cwd = "/root/pi_workspace",
            provider = provider?.trim()?.takeIf { it.isNotBlank() },
            model = model?.trim()?.takeIf { it.isNotBlank() },
            thinkingLevel = thinkingLevel?.trim()?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now
        )
        saveStore(store.copy(sessions = store.sessions + session, activeSessionId = session.id))
        return session
    }

    fun setActive(id: String): AgentSessionRecord {
        val store = loadStore()
        val session = store.sessions.firstOrNull { it.id == id } ?: error("session 不存在：$id")
        saveStore(store.copy(activeSessionId = id))
        return session
    }

    fun patchSession(id: String, patch: (AgentSessionRecord) -> AgentSessionRecord): AgentSessionRecord {
        val store = loadStore()
        var updated: AgentSessionRecord? = null
        val sessions = store.sessions.map { current ->
            if (current.id == id) {
                patch(current).copy(updatedAt = Instant.now().toString()).also { updated = it }
            } else current
        }
        val result = updated ?: error("session 不存在：$id")
        saveStore(store.copy(sessions = sessions, activeSessionId = store.activeSessionId ?: id))
        return result
    }

    fun deleteSession(id: String) {
        val store = loadStore()
        val nextSessions = store.sessions.filterNot { it.id == id }
        val nextActive = when {
            store.activeSessionId != id -> store.activeSessionId
            nextSessions.isNotEmpty() -> nextSessions.first().id
            else -> null
        }
        saveStore(store.copy(sessions = nextSessions, activeSessionId = nextActive))
    }

    fun updateRuntimeStatus(id: String, status: String, error: String? = null): AgentSessionRecord? = runCatching {
        patchSession(id) { it.copy(status = status, error = error ?: it.error, lastActiveAt = Instant.now().toString()) }
    }.getOrNull()

    fun setSessionFile(id: String, sessionFile: String?): AgentSessionRecord? {
        val value = sessionFile?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { patchSession(id) { it.copy(sessionFile = value, lastActiveAt = Instant.now().toString()) } }.getOrNull()
    }
}
