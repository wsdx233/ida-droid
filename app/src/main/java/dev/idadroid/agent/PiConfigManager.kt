package dev.idadroid.agent

import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.util.JsonFormats
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class PiConfigManager(
    private val paths: EnvironmentPaths
) {
    private val workspace get() = File(paths.rootfsDir, "root/pi_workspace")
    private val idaDroidDir get() = File(workspace, ".idadroid")
    private val agentDir get() = File(idaDroidDir, "pi-agent")
    private val piDir get() = File(workspace, ".pi")
    private val configFile get() = File(agentDir, "idadroid-pi-config.json")
    private val settingsFile get() = File(agentDir, "settings.json")
    private val modelsFile get() = File(agentDir, "models.json")
    private val agentsFile get() = File(agentDir, "AGENTS.md")
    private val systemFile get() = File(piDir, "SYSTEM.md")
    private val appendSystemFile get() = File(piDir, "APPEND_SYSTEM.md")

    private val lenientJson = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    fun readUserConfig(): PiUserConfig = runCatching {
        if (!configFile.isFile) return PiUserConfig()
        lenientJson.decodeFromString<PiUserConfig>(configFile.readText())
    }.getOrDefault(PiUserConfig())

    fun readSnapshot(): PiConfigSnapshot {
        val cfg = readUserConfig()
        return PiConfigSnapshot(
            defaultProvider = cfg.defaultProvider.orEmpty(),
            defaultModel = cfg.defaultModel.orEmpty(),
            defaultThinkingLevel = cfg.defaultThinkingLevel ?: "medium",
            enabledModels = cfg.enabledModels.joinToString(", "),
            settingsText = settingsFile.readTextOrDefault(defaultSettingsText()),
            modelsText = modelsFile.readTextOrDefault("{}\n"),
            envText = JsonFormats.pretty.encodeToString(cfg.env),
            systemPrompt = systemFile.readTextOrDefault("# IDAdroid\n\nThis workspace is managed by the Android IDAdroid app.\n"),
            appendSystem = appendSystemFile.readTextOrDefault(defaultAgentInstructions()),
            agentsMd = agentsFile.readTextOrDefault(defaultAgentInstructions()),
            extraArgsText = cfg.extraArgs.joinToString("\n"),
            materializedDir = "/root/pi_workspace/.idadroid/pi-agent"
        )
    }

    fun saveSnapshot(snapshot: PiConfigSnapshot) {
        agentDir.mkdirs()
        piDir.mkdirs()
        parseObject(snapshot.settingsText.ifBlank { "{}" })
        parseObject(snapshot.modelsText.ifBlank { "{}" })
        val env = parseEnv(snapshot.envText)
        val config = PiUserConfig(
            defaultProvider = snapshot.defaultProvider.trim().takeIf { it.isNotBlank() },
            defaultModel = snapshot.defaultModel.trim().takeIf { it.isNotBlank() },
            defaultThinkingLevel = snapshot.defaultThinkingLevel.trim().takeIf { it.isNotBlank() } ?: "medium",
            enabledModels = snapshot.enabledModels.split(',').map { it.trim() }.filter { it.isNotBlank() },
            env = env,
            extraArgs = snapshot.extraArgsText.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
        )
        configFile.writeText(JsonFormats.pretty.encodeToString(config))
        settingsFile.writeText(snapshot.settingsText.ifBlank { "{}" }.trimEnd() + "\n")
        modelsFile.writeText(snapshot.modelsText.ifBlank { "{}" }.trimEnd() + "\n")
        systemFile.writeText(snapshot.systemPrompt.trimEnd() + "\n")
        appendSystemFile.writeText(snapshot.appendSystem.trimEnd() + "\n")
        agentsFile.writeText(snapshot.agentsMd.trimEnd() + "\n")
    }

    fun defaultProvider(): String? = readUserConfig().defaultProvider?.takeIf { it.isNotBlank() }
    fun defaultModel(): String? = readUserConfig().defaultModel?.takeIf { it.isNotBlank() }
    fun defaultThinking(): String? = readUserConfig().defaultThinkingLevel?.takeIf { it.isNotBlank() }

    fun runtimeEnvExports(): String {
        val cfg = readUserConfig()
        return cfg.env.entries.joinToString("\n") { (key, value) ->
            val safeKey = key.replace(Regex("[^A-Za-z0-9_]"), "_").takeIf { it.isNotBlank() } ?: "IDADROID_ENV"
            "export $safeKey=${dev.idadroid.proot.IdaProotRuntime.shellQuote(value)}"
        }
    }

    fun extraArgs(): List<String> = readUserConfig().extraArgs

    private fun parseObject(text: String): JsonObject {
        val parsed = JsonFormats.pretty.parseToJsonElement(text.ifBlank { "{}" })
        return parsed as? JsonObject ?: error("必须是 JSON object")
    }

    private fun parseEnv(text: String): Map<String, String> = parseObject(text).mapValues { (_, value) ->
        value.toString().trim('"')
    }

    private fun File.readTextOrDefault(default: String): String = runCatching {
        if (isFile) readText() else default
    }.getOrDefault(default)

    private fun defaultSettingsText(): String = """
        {
          "quietStartup": true,
          "enableInstallTelemetry": false,
          "sessionDir": "/root/pi_workspace/.pi-sessions",
          "compaction": {
            "enabled": true,
            "reserveTokens": 16384,
            "keepRecentTokens": 20000
          },
          "retry": {
            "enabled": true,
            "maxRetries": 3,
            "baseDelayMs": 2000,
            "maxDelayMs": 60000
          },
          "steeringMode": "one-at-a-time",
          "followUpMode": "one-at-a-time"
        }
    """.trimIndent() + "\n"

    private fun defaultAgentInstructions(): String = """
        # IDAdroid workspace

        You are running inside IDAdroid's proot rootfs.

        - Working directory: `/root/pi_workspace`.
        - IDA home: `/root/ida-pro-9.3`.
        - ida-mcp entry: `/root/ida-pro-9.3/ida-mcp`.
        - ida-mcp/mcpc usage doc: `/root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md`.
        - Attachments copied from Android live in `/root/pi_workspace/.upload`.
        - pi sessions live in `/root/pi_workspace/.pi-sessions`.

        For reverse-engineering tasks, first read `IDA_MCP_MCPC_USAGE.md`, then use `mcpc` to call `ida-mcp` only when IDA/MCP is running. If IDA GUI or MCP is not running, ask the user to launch IDA GUI from the app.
    """.trimIndent() + "\n"
}
