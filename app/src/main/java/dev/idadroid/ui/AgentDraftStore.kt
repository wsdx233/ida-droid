package dev.idadroid.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.idadroid.agent.DraftAttachment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class PendingAgentAttachment(
    val file: String,
    val name: String,
    val mimeType: String,
    val isImage: Boolean,
    val size: Long
)

internal data class AgentDraft(
    val text: String = "",
    val attachments: List<PendingAgentAttachment> = emptyList()
)

/** Private local drafts; attachment bytes never enter a saved-state Bundle. */
internal class AgentDraftStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("agent-composer-drafts", Context.MODE_PRIVATE)
    private val directory = File(context.applicationContext.filesDir, "agent-composer-drafts")
    var revision by mutableStateOf(0)
        private set

    fun get(sessionId: String): AgentDraft {
        val source = prefs.getString(sessionId, null) ?: return AgentDraft()
        return runCatching {
            val json = JSONObject(source)
            val attachments = json.optJSONArray("attachments") ?: JSONArray()
            AgentDraft(json.optString("text"), List(attachments.length()) { index ->
                val item = attachments.getJSONObject(index)
                PendingAgentAttachment(item.getString("file"), item.getString("name"), item.getString("mimeType"), item.getBoolean("isImage"), item.getLong("size"))
            })
        }.getOrDefault(AgentDraft(text = source))
    }

    fun put(sessionId: String, draft: AgentDraft) {
        val json = JSONObject().put("text", draft.text).put("attachments", JSONArray().apply {
            draft.attachments.forEach { item -> put(JSONObject().put("file", item.file).put("name", item.name).put("mimeType", item.mimeType).put("isImage", item.isImage).put("size", item.size)) }
        })
        prefs.edit().putString(sessionId, json.toString()).apply()
        revision++
    }

    suspend fun attach(sessionId: String, attachment: DraftAttachment) {
        val cached = withContext(Dispatchers.IO) {
            check(directory.exists() || directory.mkdirs()) { "无法保存附件草稿" }
            val file = File(directory, UUID.randomUUID().toString())
            file.writeBytes(attachment.bytes)
            PendingAgentAttachment(file.name, attachment.name, attachment.mimeType, attachment.isImage, attachment.size)
        }
        val current = get(sessionId)
        put(sessionId, current.copy(attachments = current.attachments + cached))
    }

    suspend fun materialize(draft: AgentDraft): List<DraftAttachment> = withContext(Dispatchers.IO) {
        draft.attachments.map { item ->
            DraftAttachment(item.name, item.mimeType, File(directory, item.file).readBytes(), item.isImage)
        }
    }

    suspend fun remove(sessionId: String, attachment: PendingAgentAttachment) {
        val current = get(sessionId)
        put(sessionId, current.copy(attachments = current.attachments.filterNot { it.file == attachment.file }))
        withContext(Dispatchers.IO) { File(directory, attachment.file).delete() }
    }

    suspend fun clearAccepted(sessionId: String, accepted: AgentDraft) {
        // Preserve anything typed while the attachment bytes were being read.
        val current = get(sessionId)
        put(sessionId, current.copy(
            text = if (current.text == accepted.text) "" else current.text,
            attachments = current.attachments.filterNot { item -> accepted.attachments.any { it.file == item.file } }
        ))
        withContext(Dispatchers.IO) { accepted.attachments.forEach { File(directory, it.file).delete() } }
    }
}
