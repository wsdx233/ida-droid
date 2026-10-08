package dev.idadroid.agent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PiProviderConfigTest {
    @Test fun nativeFieldsTakePrecedenceWithoutLosingCustomModelMetadata() {
        val source = Json.parseToJsonElement("""{
            "baseUrl":"https://native.example/proxy/v3", "baseURL":"https://legacy.example/v1",
            "api":"anthropic-messages", "apiKey":"NATIVE_KEY", "envKey":"OLD_KEY",
            "compat":{"supportsStore":false,"custom":{"flag":true}},
            "headers":{"X-Proxy":"value"}, "models":[{"id":"custom","api":"openai-responses",
            "contextWindow":8192,"cost":{"input":1,"currency":"credit"},"vendor":{"version":2}}]
        }""").jsonObject
        val migrated = normalizePiProvider("openai", source)
        assertEquals("https://native.example/proxy/v3", migrated.getValue("baseUrl").jsonPrimitive.content)
        assertEquals("anthropic-messages", migrated.getValue("api").jsonPrimitive.content)
        assertEquals("NATIVE_KEY", migrated.getValue("apiKey").jsonPrimitive.content)
        assertFalse(migrated.containsKey("baseURL"))
        assertFalse(migrated.containsKey("envKey"))
        assertEquals(source["models"], migrated["models"])
        assertEquals(source["compat"], migrated["compat"])
        assertEquals(source["headers"], migrated["headers"])
    }

    @Test fun legacyCredentialRemainsAnEnvironmentReferenceEvenWhenUnresolved() {
        val source = Json.parseToJsonElement("""{"baseURL":"https://example.com/gateway","envKey":"PRIVATE_KEY"}""").jsonObject
        val migrated = normalizePiProvider("anthropic", source)
        assertEquals("PRIVATE_KEY", migrated.getValue("apiKey").jsonPrimitive.content)
        assertEquals("anthropic-messages", migrated.getValue("api").jsonPrimitive.content)
        assertEquals(migrated, normalizePiProvider("anthropic", migrated))
    }

    @Test fun rejectsMalformedProviderAndDuplicateOrInvalidModelsWithoutLeakingJson() {
        val invalid = listOf(
            """{"providers":{"custom":"secret-key-123456"}}""",
            """{"providers":{"custom":{"models":[{"id":"same"},{"id":"same"}]}}}""",
            """{"providers":{"custom":{"models":[{"id":"model","contextWindow":0}]}}}""",
            """{"providers":{"custom":{"models":[{"id":"model","cost":{"input":-1}}]}}}""",
            """{"providers":{"custom":{"apiKey":"secret-key-123456","models":[{"id":"model","input":"image"}]}}}""",
            """{"providers":{"custom":{"apiKey":"secret-key-123456"},"broken":[]}}"""
        )
        invalid.forEach { text ->
            val failure = runCatching { parsePiModels(text) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure?.message.orEmpty().contains("secret-key-123456"))
        }
    }

    @Test fun acceptsHttpProviderBaseUrlForLocalProvider() {
        val config = """{"providers":{"local":{"baseUrl":"HTTP://127.0.0.1:8080/v1","api":"openai-completions"}}}"""
        val parsed = parsePiModels(config)
        assertEquals("HTTP://127.0.0.1:8080/v1", parsed.getValue("providers").jsonObject.getValue("local").jsonObject.getValue("baseUrl").jsonPrimitive.content)
    }

    @Test fun incompleteUrlCanRemainInDraftButCannotPassSaveValidation() {
        val draft = """{"providers":{"custom":{"baseUrl":"https://","apiKey":"secret"}}}"""
        assertEquals("https://", parsePiModels(draft, validateValues = false).getValue("providers").jsonObject.getValue("custom").jsonObject.getValue("baseUrl").jsonPrimitive.content)
        assertTrue(runCatching { parsePiModels(draft) }.isFailure)
    }
}
