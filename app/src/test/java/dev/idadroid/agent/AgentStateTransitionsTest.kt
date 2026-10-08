package dev.idadroid.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AgentStateTransitionsTest {
    @Test fun completedAssistantPreservesInterleavedUserAndFinishedTool() {
        val arguments = buildJsonObject { put("path", "evidence.txt") }
        val prior = ChatMessage("prior", "user", "inspect", 1)
        val placeholder = ChatMessage("stream", "assistant", "partial", 2)
        val tool = ChatMessage("tool-live", "tool", "", 3, toolCallId = "read-1", toolName = "read", toolArgs = arguments, toolResult = "original evidence", toolStatus = "done")
        val user = ChatMessage("follow-up", "user", "also check exports", 4)
        val completed = listOf(
            ChatMessage("normalized-assistant", "assistant", "analysis complete", 2, thinking = "reasoning"),
            ChatMessage("normalized-tool", "tool", "", 2, toolCallId = "read-1", toolName = "read", toolArgs = arguments, toolStatus = "running")
        )
        val messages = mergeCompletedPiMessage(listOf(prior, placeholder, tool, user), "stream", completed)
        assertEquals(listOf("prior", "stream", "tool-live", "follow-up"), messages.map { it.id })
        assertEquals("analysis complete", messages[1].text)
        assertEquals("reasoning", messages[1].thinking)
        assertEquals(tool, messages[2])
        assertEquals(user, messages[3])
    }

    @Test fun sessionChoicesOverrideDefaultsButUnsetFieldsInheritThem() {
        val explicit = AgentSessionRecord(thinkingLevel = "low", autoCompactionEnabled = false)
        assertEquals(explicit, explicit.withAgentDefaults("high", true))
        val inherited = AgentSessionRecord(thinkingLevel = " ").withAgentDefaults("high", false)
        assertEquals("high", inherited.thinkingLevel)
        assertEquals(false, inherited.autoCompactionEnabled)
    }

    @Test fun explicitlySavingMediumOverridesWorkspaceWhileUntouchedDefaultDoesNot() {
        assertEquals("high", resolveAgentDefaultThinking(null, "high"))
        assertEquals("medium", resolveAgentDefaultThinking("medium", "high"))
        assertEquals("medium", resolveAgentDefaultThinking(null, " "))
    }
}
