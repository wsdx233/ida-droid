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
    private val workspace get() = File(paths.rootfsDir, workspaceProotPath().removePrefix("/").ifBlank { "root/pi_workspace" })
    private val idaDroidDir get() = File(workspace, ".idadroid")
    private val agentDir get() = File(idaDroidDir, "pi-agent")
    private val piDir get() = File(workspace, ".pi")
    private val configFile get() = File(agentDir, "idadroid-pi-config.json")
    private val settingsFile get() = File(agentDir, "settings.json")
    private val modelsFile get() = File(agentDir, "models.json")
    private val appendSystemFile get() = File(piDir, "APPEND_SYSTEM.md")

    private val lenientJson = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true }

    @Volatile private var cachedSnapshot: PiConfigSnapshot? = null
    @Volatile private var cachedSnapshotTime: Long = 0

    fun readUserConfig(): PiUserConfig = runCatching {
        if (!configFile.isFile) return PiUserConfig()
        lenientJson.decodeFromString<PiUserConfig>(configFile.readText())
    }.getOrDefault(PiUserConfig())

    fun readSnapshot(): PiConfigSnapshot {
        // 缓存：如果配置文件未修改，直接返回缓存
        val configLastModified = maxOf(
            configFile.lastModified(),
            modelsFile.lastModified(),
            settingsFile.lastModified(),
            appendSystemFile.lastModified()
        )
        cachedSnapshot?.let { cache ->
            if (cache.materializedDir.isNotBlank() && cachedSnapshotTime == configLastModified) {
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
            materializedDir = "${workspaceProotPath()}/.idadroid/pi-agent",
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
        configFile.writeText(JsonFormats.pretty.encodeToString(config))
        settingsFile.writeText(snapshot.settingsText.ifBlank { "{}" }.trimEnd() + "\n")
        modelsFile.writeText(snapshot.modelsText.ifBlank { "{}" }.trimEnd() + "\n")
        appendSystemFile.writeText(snapshot.appendSystem.trimEnd() + "\n")
        // 使缓存失效
        cachedSnapshot = null
    }

    fun defaultProvider(): String? = readUserConfig().defaultProvider?.takeIf { it.isNotBlank() }
    fun defaultModel(): String? = readUserConfig().defaultModel?.takeIf { it.isNotBlank() }
    fun defaultThinking(): String? = readUserConfig().defaultThinkingLevel?.takeIf { it.isNotBlank() }

    fun runtimeEnvExports(): String {
        val cfg = readUserConfig()
        val lines = mutableListOf<String>()
        
        // 导出 envText 里的环境变量 (API Key 等)
        cfg.env.forEach { (key, value) ->
            val safeKey = key.replace(Regex("[^A-Za-z0-9_]"), "_").takeIf { it.isNotBlank() } ?: "IDADROID_ENV"
            lines += "export $safeKey=${dev.idadroid.proot.IdaProotRuntime.shellQuote(value)}"
        }
        
        // 只对 openai/openai-generic/custom provider 导出 OPENAI_BASE_URL
        // 其他 provider (deepseek, anthropic 等) pi agent 内置了 Base URL
        val snapshot = readSnapshot()
        val defaultProviderId = snapshot.defaultProvider.trim()
        if (defaultProviderId in setOf("openai", "openai-generic", "custom")) {
            val modelsObj = if (snapshot.modelsText.isBlank()) JsonObject(emptyMap()) 
                else JsonFormats.pretty.parseToJsonElement(snapshot.modelsText).jsonObject
            val providersObj = modelsObj["providers"] as? JsonObject ?: JsonObject(emptyMap())
            val providerObj = providersObj[defaultProviderId] as? JsonObject
            val baseUrl = providerObj?.get("baseURL")?.jsonPrimitive?.contentOrNull
                ?: providerObj?.get("baseUrl")?.jsonPrimitive?.contentOrNull
            if (!baseUrl.isNullOrBlank()) {
                lines += "export OPENAI_BASE_URL=${dev.idadroid.proot.IdaProotRuntime.shellQuote(baseUrl)}"
                lines += "export OPENAI_API_BASE=${dev.idadroid.proot.IdaProotRuntime.shellQuote(baseUrl)}"
            }
        }
        
        return lines.joinToString("\n")
    }

    fun extraArgs(): List<String> = readUserConfig().extraArgs

    private fun parseObject(text: String): JsonObject {
        val parsed = JsonFormats.pretty.parseToJsonElement(text.ifBlank { "{}" })
        return parsed as? JsonObject ?: error("必须是 JSON object")
    }

    private fun parseEnv(text: String): Map<String, String> {
        val obj = parseObject(text)
        return obj.mapNotNull { (key, value) ->
            val str = (value as? JsonPrimitive)?.contentOrNull
                ?: (value as? JsonPrimitive)?.toString()?.trim('"')
                ?: return@mapNotNull null
            key to str
        }.toMap()
    }

    private fun File.readTextOrDefault(default: String): String = runCatching {
        if (isFile) readText() else default
    }.getOrDefault(default)

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
