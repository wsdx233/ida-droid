package dev.idadroid.agent

import android.content.Context
import dev.idadroid.env.EnvironmentPaths
import dev.idadroid.util.JsonFormats
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PiConfigManager(
    private val context: Context,
    private val paths: EnvironmentPaths
) {
    private val appContext = context.applicationContext
    private val settings = dev.idadroid.settings.IdaDroidSettings(appContext)
    /** 用户设置的工作区路径 (proot 内可见路径) */
    fun workspaceProotPath(): String = settings.envSettings.value.workspacePath.ifBlank { dev.idadroid.settings.IdaDroidSettings.DEFAULT_WORKSPACE_PATH }
    private val workspace get() = workspaceProotPath().let { path ->
        if (path == "/sdcard" || path.startsWith("/sdcard/") || path == "/storage" || path.startsWith("/storage/")) File(path)
        else File(paths.rootfsDir, path.removePrefix("/"))
    }
    private val idaDroidDir get() = File(workspace, ".idadroid")
    private val agentDir get() = File(idaDroidDir, "pi-agent")
    private val piDir get() = File(workspace, ".pi")
    private val configFile get() = File(agentDir, "idadroid-pi-config.json")
    private val settingsFile get() = File(agentDir, "settings.json")
    private val modelsFile get() = File(agentDir, "models.json")
    private val appendSystemFile get() = File(piDir, "APPEND_SYSTEM.md")
    private val runtimeProotPath get() = "/root/.idadroid/pi-runtime/" + java.util.UUID.nameUUIDFromBytes(workspaceProotPath().toByteArray(Charsets.UTF_8))
    private val runtimeHostDir get() = File(paths.rootfsDir, runtimeProotPath.removePrefix("/"))

    private val lenientJson = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    @Volatile private var cachedSnapshot: PiConfigSnapshot? = null
    @Volatile private var cachedSnapshotTime: Long = 0

    fun readUserConfig(): PiUserConfig {
        if (!configFile.isFile) return PiUserConfig()
        return try { lenientJson.decodeFromString<PiUserConfig>(configFile.readText()) }
        catch (_: Exception) { error("Invalid IDAdroid PI configuration; original preserved") }
    }

    fun readSnapshot(): PiConfigSnapshot {
        // 缓存：如果配置文件未修改，直接返回缓存
        val configLastModified = maxOf(
            configFile.lastModified(),
            modelsFile.lastModified(),
            settingsFile.lastModified(),
            appendSystemFile.lastModified()
        )
        cachedSnapshot?.let { cache ->
            if (cache.materializedDir == runtimeProotPath && cachedSnapshotTime == configLastModified) {
                return cache
            }
        }

        val cfg = readUserConfig()
        val modelsText = modelsFile.readTextOrDefault("{}\n")
        val snapshot = PiConfigSnapshot(
            defaultProvider = cfg.defaultProvider.orEmpty(),
            defaultModel = cfg.defaultModel.orEmpty(),
            defaultThinkingLevel = cfg.defaultThinkingLevel ?: "medium",
            enabledModels = cfg.enabledModels.joinToString(", "),
            settingsText = settingsFile.readTextOrDefault(defaultSettingsText()),
            modelsText = modelsText,
            envText = JsonFormats.pretty.encodeToString(cfg.env),
            appendSystem = appendSystemFile.readTextOrDefault(defaultAppendSystemPrompt()),
            extraArgsText = cfg.extraArgs.joinToString("\n"),
            materializedDir = runtimeProotPath,
            modelCatalog = parseAgentModelCatalog(modelsText)
        )
        cachedSnapshot = snapshot
        cachedSnapshotTime = configLastModified
        return snapshot
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
        privateWrite(configFile, JsonFormats.pretty.encodeToString(config))
        privateWrite(settingsFile, snapshot.settingsText.ifBlank { "{}" }.trimEnd())
        privateWrite(modelsFile, snapshot.modelsText.ifBlank { "{}" }.trimEnd())
        privateWrite(appendSystemFile, snapshot.appendSystem.trimEnd())
        // 使缓存失效
        cachedSnapshot = null
    }

    fun defaultProvider(): String? = readUserConfig().defaultProvider?.takeIf { it.isNotBlank() }
    fun defaultModel(): String? = readUserConfig().defaultModel?.takeIf { it.isNotBlank() }
    fun defaultThinking(): String? = readUserConfig().defaultThinkingLevel?.takeIf { it.isNotBlank() }

    /** Runtime files are separate from editable user originals. Never replace auth.json. */
    fun materialize(): String {
        val snapshot = readSnapshot()
        val cfg = readUserConfig()
        val runtimeDir = runtimeHostDir.apply { mkdirs() }
        val models = parseObject(snapshot.modelsText)
        val providers = models["providers"] as? JsonObject ?: JsonObject(emptyMap())
        val converted = providers.mapValues { (id, value) ->
            val provider = (value as? JsonObject ?: error("Invalid PI provider object")).toMutableMap()
            val legacy = provider.containsKey("baseURL") || provider.containsKey("envKey")
            provider.remove("baseURL")?.let { if (provider["baseUrl"] == null) provider["baseUrl"] = it }
            val envKey = provider.remove("envKey")?.jsonPrimitive?.contentOrNull
            provider.remove("displayName")
            if (legacy && provider["api"] == null) provider["api"] = JsonPrimitive(when (id) {
                "anthropic" -> "anthropic-messages"
                "google" -> "google-generative-ai"
                else -> "openai-completions"
            })
            if (provider["apiKey"] == null && envKey != null) {
                val key = cfg.env[envKey]?.takeIf { it.isNotBlank() }
                    ?: error("Missing credential environment variable: $envKey")
                provider["apiKey"] = JsonPrimitive(key)
            } else {
                val key = provider["apiKey"]?.jsonPrimitive?.contentOrNull
                key?.let { cfg.env[it] }?.let { provider["apiKey"] = JsonPrimitive(it) }
            }
            JsonObject(provider)
        }
        privateWrite(File(runtimeDir, "models.json"), JsonObject(models + ("providers" to JsonObject(converted))).toString())
        val runtimeSettings = parseObject(snapshot.settingsText).toMutableMap().apply {
            remove("sessionDir") // Session location belongs to --session-dir, not settings.json.
            cfg.defaultProvider?.let { put("defaultProvider", JsonPrimitive(it)) }
            cfg.defaultModel?.let { put("defaultModel", JsonPrimitive(it)) }
            cfg.defaultThinkingLevel?.let { put("defaultThinkingLevel", JsonPrimitive(it)) }
        }
        privateWrite(File(runtimeDir, "settings.json"), JsonObject(runtimeSettings).toString())
        listOf("auth.json", "extensions", "skills", "prompts", "themes", "AGENTS.md", "SYSTEM.md").forEach { name ->
            val original = File(agentDir, name).takeIf { it.exists() }
                ?: File(paths.rootfsDir, "root/.pi/agent/$name").takeIf { it.exists() }
            if (original != null) {
                val link = File(runtimeDir, name).toPath()
                if (!java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    // Guest absolute target: OAuth refreshes remain in the user's original auth file.
                    val guest = if (original.path.startsWith(paths.rootfsDir.path + "/")) "/" + original.relativeTo(paths.rootfsDir).path else original.path
                    java.nio.file.Files.createSymbolicLink(link, java.nio.file.Paths.get(guest))
                }
            }
        }
        piDir.mkdirs()
        if (!appendSystemFile.exists()) appendSystemFile.writeText(snapshot.appendSystem)
        val reserved = setOf("--mode", "--session", "--session-dir", "--session-id", "--no-session", "--continue", "-c", "--resume", "-r", "--print", "-p", "--help", "-h", "--version", "-v")
        require(cfg.extraArgs.none { it.substringBefore('=') in reserved }) { "Extra arguments cannot override the managed RPC lifecycle" }
        require(cfg.env.keys.all { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it) }) { "Invalid environment variable name" }
        return runtimeProotPath
    }

    fun runtimeEnvExports(): String = readUserConfig().env.entries.joinToString("\n") { (key, value) ->
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)) { "Invalid environment variable name" }
        "export $key=${dev.idadroid.proot.IdaProotRuntime.shellQuote(value)}"
    }

    fun redact(text: String): String {
        val cfg = runCatching { readUserConfig() }.getOrNull()
        val secrets = cfg?.env?.values.orEmpty().filter { it.length >= 4 }.toMutableList()
        runCatching { parseObject(readSnapshot().modelsText)["providers"] as? JsonObject }.getOrNull()?.values?.forEach { value ->
            (value as? JsonObject)?.get("apiKey")?.jsonPrimitive?.contentOrNull?.takeIf { it.length >= 4 }?.let(secrets::add)
        }
        listOf(File(agentDir, "auth.json"), File(paths.rootfsDir, "root/.pi/agent/auth.json"), File(runtimeHostDir, "auth.json")).filter { it.isFile }.forEach { file ->
            runCatching { parseObject(file.readText()) }.getOrNull()?.values?.forEach { value ->
                (value as? JsonObject)?.let { auth -> listOf("key", "access", "refresh").forEach { field ->
                    (auth[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.length >= 4 }?.let(secrets::add)
                } }
            }
        }
        return secrets.fold(text) { result, secret -> result.replace(secret, "[redacted]") }
            .replace(Regex("(?i)(bearer\\s+)[^\\s,;]+"), "$1[redacted]")
    }

    private fun privateWrite(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            temp.createNewFile()
            temp.setReadable(false, false); temp.setWritable(false, false)
            temp.setReadable(true, true); temp.setWritable(true, true)
            temp.outputStream().use { output ->
                output.write((text + "\n").toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            check(temp.renameTo(file)) { "Cannot commit PI configuration; original preserved" }
        } finally { temp.delete() }
    }

    fun extraArgs(): List<String> = readUserConfig().extraArgs

    private fun parseObject(text: String): JsonObject {
        val parsed = try { JsonFormats.pretty.parseToJsonElement(text.ifBlank { "{}" }) }
        catch (_: Exception) { error("Invalid configuration JSON; content hidden for privacy") }
        return parsed as? JsonObject ?: error("必须是 JSON object")
    }

    private fun parseEnv(text: String): Map<String, String> = parseObject(text).mapValues { (key, value) ->
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)) { "Invalid environment variable name" }
        (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("Environment values must be strings")
    }

    private fun File.readTextOrDefault(default: String): String = if (isFile) readText() else default

    private fun defaultSettingsText(): String = """
        {
          "quietStartup": true,
          "enableInstallTelemetry": false,
          "sessionDir": "${workspaceProotPath()}/.pi-sessions",
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

    private fun defaultAppendSystemPrompt(): String = defaultSystemAppendPrompt(
        workspacePath = settings.envSettings.value.workspacePath.ifBlank { "/root/pi_workspace" }
    )
}

/** Single source of truth for the default APPEND_SYSTEM.md content. */
fun defaultSystemAppendPrompt(workspacePath: String = "/root/pi_workspace"): String = """
# IDAdroid AI 逆向工程助手

## 身份
你是运行在 IDAdroid proot rootfs 内的 AI 逆向工程助手。拥有 IDA Pro 9.3、ida-mcp、jadx、Python、Node.js 完整工具链，可自主操作 IDA 进行二进制分析。

## 环境
- 工作目录: $workspacePath
- IDA: /root/ida-pro-9.3
- ida-mcp 文档: /root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md
- 附件目录: $workspacePath/.upload
- MCP 文件传输: /root/.mcp-transfer
- 路径规则: 容器内没有 /data/user/0/... 宿主路径。如看到宿主形态路径，把
  /data/user/0/dev.idadroid/files/envs/default/rootfs 前缀替换为 / 即可
  （即 /root/...、/etc/...）；MCP 传输文件一律用 /root/.mcp-transfer/ 访问

## 工作准则
1. **先读文档**: 使用 ida-mcp 前先阅读 IDA_MCP_MCPC_USAGE.md
2. **证据驱动**: 每个结论需附带反汇编/反编译证据（函数地址、代码片段）
3. **渐进分析**: 从宏观（文件类型、架构）到微观（关键函数、算法）
4. **错误恢复**: 工具调用失败时尝试替代方案，不轻易中断
5. **上下文记忆**: 同一会话记住之前的分析结论，避免重复劳动

## 任务类型
- CTF 逆向: 解题并输出 writeup
- 漏洞分析: 识别漏洞类型、危害等级、修复建议
- 恶意软件: 提取 IOC、行为特征
- 通用逆向: 描述程序逻辑、数据结构、关键算法

## 工具
- `mcpc` + ida-mcp: IDA 自动化（首选）
- `idadroid-file open <name>`: 自动搜索→传输→打开文件
- `deep-index`: 深度索引模式（CodeGraph + ECC + Memory）
- jadx、python、npm 已安装

## 安全约束
- 不删除工作区外的文件
- 不修改 /sdcard/ 下的文件
- 每个任务在工作区建独立子目录

## 输出规范
- 关键发现用 **加粗**，推测性结论用"可能/疑似"
- 长报告用 markdown 标题分段
""".trimIndent() + "\n"
