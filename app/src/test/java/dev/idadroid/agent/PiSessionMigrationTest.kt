package dev.idadroid.agent

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PiSessionMigrationTest {
    @Test fun preservesOriginalAndToolImageHistoryInVersionThreeTree() {
        val directory = Files.createTempDirectory("pi-migration").toFile()
        try {
            val source = File(directory, "native.jsonl")
            val original = """
                {"role":"user","content":"inspect","images":["data:image/png;base64,AQID"]}
                {"role":"assistant","content":"reading","toolCalls":[{"id":"call-1","name":"read_file","arguments":"{\"path\":\"a.txt\"}"}]}
                {"role":"tool","content":"original evidence","toolCallId":"call-1","name":"read_file"}
            """.trimIndent() + "\n"
            source.writeText(original)
            val destination = File(directory, "pi.jsonl")
            PiSessionMigration.migrate(source, destination, "/root/work", "custom", "model")
            assertEquals(original, source.readText())
            val records = destination.readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(3, records.first().getValue("version").jsonPrimitive.int)
            assertEquals(JsonNull, records[1]["parentId"])
            assertEquals(records[1]["id"], records[2]["parentId"])
            assertEquals(records[2]["id"], records[3]["parentId"])
            val user = records[1].getValue("message").jsonObject
            assertEquals("AQID", user.getValue("content").jsonArray[1].jsonObject.getValue("data").jsonPrimitive.content)
            val call = records[2].getValue("message").jsonObject.getValue("content").jsonArray[1].jsonObject
            assertEquals("a.txt", call.getValue("arguments").jsonObject.getValue("path").jsonPrimitive.content)
            val result = records[3].getValue("message").jsonObject
            assertEquals("toolResult", result.getValue("role").jsonPrimitive.content)
            assertEquals(call["id"], result["toolCallId"])
            assertEquals("original evidence", result.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidMiddleRecordDoesNotCreatePartialHistoryOrChangeOriginal() {
        val directory = Files.createTempDirectory("pi-migration-invalid").toFile()
        try {
            val source = File(directory, "native.jsonl")
            val original = "{\"role\":\"user\",\"content\":\"keep\"}\ninvalid\n{\"role\":\"assistant\",\"content\":\"also keep\"}\n"
            source.writeText(original)
            val destination = File(directory, "pi.jsonl")
            assertTrue(runCatching { PiSessionMigration.migrate(source, destination, "/root/work", null, null) }.isFailure)
            assertEquals(original, source.readText())
            assertFalse(destination.exists())
        } finally { directory.deleteRecursively() }
    }
}
