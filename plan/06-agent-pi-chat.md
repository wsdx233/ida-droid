# 06 - agent / pi 聊天方案

## 目标

在 Android App 内直接管理 proot 中的 `pi --mode rpc`，提供类似 BoxedAgent/BoxedAgentAndroid 的移动端聊天、session 管理、附件、tool card、thinking 展示，并让 agent 能通过 `mcpc` 调用 `ida-mcp` 辅助逆向。

## 为什么直接连接 pi RPC

不建议在 Android 中移植完整 BoxedAgent server，因为：

- BoxedAgent 的 Docker/REST/WebSocket 是为远程多客户端和 Docker sandbox 设计的。
- IDAdroid 是单机 App，直接用 Kotlin 管理 proot process 更轻。
- 可以复用 BoxedAgent 的 runtime/session 语义，但不需要 HTTP server。
- Android 生命周期、ForegroundService、通知、权限都更容易统一。

## 工作目录与 pi 配置

proot 内：

```text
/root/pi_workspace                         # 用户/agent 工作区
/root/pi_workspace/.upload                 # 附件
/root/pi_workspace/.pi-sessions            # pi session files
/root/pi_workspace/.pi/SYSTEM.md
/root/pi_workspace/.pi/APPEND_SYSTEM.md
/root/pi_workspace/.idadroid/pi-agent      # PI_CODING_AGENT_DIR
/root/pi_workspace/.idadroid/logs
```

运行时环境：

```text
HOME=/root
WORKSPACE=/root/pi_workspace
PI_CODING_AGENT_DIR=/root/pi_workspace/.idadroid/pi-agent
PI_SKIP_VERSION_CHECK=1
PI_TELEMETRY=0
NODE_OPTIONS=--require /root/pi_workspace/.idadroid/pi-agent/rpc-stdio-guard.cjs
TERM=dumb
```

启动命令：

```bash
cd /root/pi_workspace
pi --mode rpc --session-dir /root/pi_workspace/.pi-sessions
```

带配置：

```bash
pi --mode rpc \
  --session /root/pi_workspace/.pi-sessions/<session>.jsonl \
  --provider <provider> \
  --model <model> \
  --thinking <level>
```

## pi config materialize

每次环境验证/设置变更后写入：

```text
/root/pi_workspace/.idadroid/pi-agent/settings.json
/root/pi_workspace/.idadroid/pi-agent/models.json      # 可选
/root/pi_workspace/.idadroid/pi-agent/AGENTS.md
/root/pi_workspace/.idadroid/pi-agent/rpc-stdio-guard.cjs
/root/pi_workspace/.pi/APPEND_SYSTEM.md
```

默认 `AGENTS.md` 内容要包含：

```md
# IDAdroid Agent Context

- 工作目录：/root/pi_workspace
- 附件目录：/root/pi_workspace/.upload
- IDA 安装目录：/root/ida-pro-9.3
- IDA MCP 二进制：/root/ida-pro-9.3/ida-mcp
- mcpc + ida-mcp 使用说明：/root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md

逆向任务要求：
1. 需要操作 IDA MCP 前，先阅读使用说明。
2. 使用 mcpc 调用 ida-mcp，不要臆造 MCP 参数。
3. 如果连接失败，先提示用户在 IDAdroid 中启动 IDA GUI 并打开目标数据库/二进制。
4. 不要修改 IDA 安装目录，临时输出写入 /root/pi_workspace。
```

## Kotlin 模块划分

```text
agent/
  PiAgentManager.kt
  PiRpcRuntime.kt
  PiRpcProtocol.kt
  AgentSessionRepository.kt
  PiMessageNormalizer.kt
  AttachmentManager.kt
  IdaMcpPromptMaterializer.kt
  RpcStdioGuard.kt
```

### PiRpcRuntime

职责：管理一个 `pi --mode rpc` process。

核心字段：

```kotlin
class PiRpcRuntime(
    val session: AgentSession,
    val proot: ProotRuntime,
    val events: MutableSharedFlow<PiRuntimeEvent>
) {
    private var process: Process? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private var seq = AtomicLong(0)
}
```

核心方法：

```kotlin
suspend fun start()
suspend fun stop()
suspend fun send(command: JsonObject, timeoutMs: Long = 120_000): JsonElement
suspend fun prompt(payload: PromptPayload)
suspend fun abort()
suspend fun getState(): JsonObject
suspend fun getMessages(): List<JsonElement>
suspend fun getStats(): SessionStats?
```

stdout 处理：

- 按 UTF-8 行读取。
- 空行忽略。
- JSON parse 成功：
  - `type=response` 且 `id` 命中 pending → complete/decomplete。
  - 否则作为 agent event 发给 UI。
- JSON parse 失败：作为 raw log。

stderr 处理：

- 进入 ring buffer。
- UI 可显示 `agent stderr` 折叠卡片。
- runtime exit 时把最近 stderr 拼到 error。

### PiAgentManager

本地 session manager，参考 BoxedAgent `agent-manager.ts`。

MVP API：

```kotlin
suspend fun createSession(input: CreateSessionInput): AgentSession
suspend fun startSession(id: String): AgentSession
suspend fun stopSession(id: String)
suspend fun deleteSession(id: String)
suspend fun renameSession(id: String, name: String)
suspend fun sendPrompt(id: String, text: String, attachments: List<DraftAttachment>, mode: SendMode?)
suspend fun abort(id: String)
suspend fun loadMessages(id: String): List<ChatMessage>
```

后续 API：

```kotlin
suspend fun duplicateSession(id: String)
suspend fun cloneSession(id: String)
suspend fun forkSession(id: String, entryId: String)
suspend fun sessionTree(id: String)
suspend fun navigateTree(id: String, targetId: String)
suspend fun setModel(id: String, provider: String, modelId: String)
suspend fun setThinking(id: String, level: ThinkingLevel)
suspend fun compact(id: String, customInstructions: String?)
```

## RPC 命令参考

来自 BoxedAgent 调研，可先支持：

| 功能 | JSON line |
| --- | --- |
| prompt | `{ "id":"req_1", "type":"prompt", "message":"...", "images":[...] }` |
| steer | `{ "id":"req_2", "type":"steer", "message":"..." }` |
| follow up | `{ "id":"req_3", "type":"follow_up", "message":"..." }` |
| abort | `{ "id":"req_4", "type":"abort" }` |
| state | `{ "id":"req_5", "type":"get_state" }` |
| messages | `{ "id":"req_6", "type":"get_messages" }` |
| models | `{ "id":"req_7", "type":"get_available_models" }` |
| stats | `{ "id":"req_8", "type":"get_session_stats" }` |
| set model | `{ "id":"req_9", "type":"set_model", "provider":"...", "modelId":"..." }` |
| thinking | `{ "id":"req_10", "type":"set_thinking_level", "level":"medium" }` |
| compact | `{ "id":"req_11", "type":"compact", "customInstructions":"..." }` |

需要以实际 pi 版本验证命令名称；若差异，封装在 `PiRpcProtocol` 中。

## UI event 映射

事件类型参考：

- `agent_start`
- `agent_end`
- `turn_start`
- `turn_end`
- `message_start`
- `message_update`
- `message_end`
- `tool_execution_start`
- `tool_execution_update`
- `tool_execution_end`
- `compaction_start`
- `compaction_end`
- `queue_update`

`message_update` 内部常见：

- `text_delta`
- `thinking_delta`
- `toolcall_start/toolcall_delta/toolcall_end`
- `done/error`

UI 状态：

```text
idle/running      -> 可发送普通 prompt
working           -> 发送按钮变 Stop；若有输入，弹出 send mode：Abort+Send / Steer / Follow-up
error/stopped     -> 可 Start / Reload
```

## MessageNormalizer

复用 BoxedAgentAndroid 的思路：把 pi message JSON 转成 UI 模型：

```kotlin
data class ChatMessage(
    val id: String,
    val role: String, // user / assistant / tool / system
    val text: String,
    val timestamp: Long,
    val thinking: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: JsonElement? = null,
    val toolResult: String? = null,
    val toolStatus: ToolStatus? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val transport: TransportMeta? = null
)
```

MVP 不需要完美覆盖所有 pi message 形态，但必须覆盖：

- user text
- assistant text delta
- thinking delta
- tool call/result
- error/system

## 附件处理

### 选择附件

- Android SAF 选择一个或多个文件。
- 读取 display name、mime、size。
- 图片判断：mime startsWith `image/` 或扩展名。

### 写入 workspace

host path：

```text
<rootfs>/root/pi_workspace/.upload/<safe-name>
```

proot path：

```text
/root/pi_workspace/.upload/<safe-name>
```

composer 插入：

```text
@/root/pi_workspace/.upload/<safe-name>
```

### 发送前展开

解析规则参考 BoxedAgent：

- 只识别前面是空白或开头的 `@path`。
- 支持绝对路径 `/root/pi_workspace/...`。
- 支持相对路径，相对于 session cwd `/root/pi_workspace`。
- 不要把普通 npm scope、邮箱、Android resource 误识别。

文本文件：

```xml
<file name="/root/pi_workspace/.upload/a.txt">
...
</file>
```

图片：

```xml
<file name="/root/pi_workspace/.upload/a.png"></file>
```

并放入 RPC：

```json
{"type":"image","data":"base64...","mimeType":"image/png"}
```

缺失/不可读：保持原文，不阻塞发送。

## IDA MCP / mcpc 集成

### MVP 行为

App 不硬编码 `mcpc` 的具体参数，而是让 agent 先读取：

```text
/root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md
```

并按文档调用：

```text
/root/ida-pro-9.3/ida-mcp
mcpc ...
```

因为用户 rootfs 中的 ida-mcp/mcpc 版本和用法可能不同，硬编码容易失效。

### 状态卡片

Chat 顶部显示：

- IDA GUI：Stopped/Starting/Running/Error。
- ida-mcp：binary exists/executable。
- mcpc：command exists/missing。
- usage doc：exists/missing。

按钮：

- Launch IDA GUI。
- Open Terminal。
- Copy MCP diagnostic prompt。

### 后续增强

- 根据 usage doc 生成 MCP command template。
- 一键启动 ida-mcp bridge。
- MCP 调用日志与最近错误卡片。

## session 文件与历史

`pi --mode rpc --session-dir /root/pi_workspace/.pi-sessions` 会创建 session file。App 需要从 `get_state` 中读取 `sessionFile` 并写入 `AgentSessionEntity.sessionFile`。

重新打开历史：

```bash
pi --mode rpc --session /root/pi_workspace/.pi-sessions/<file>.jsonl
```

如果 runtime 未启动但要显示历史，MVP 可先启动 runtime 后 `get_messages`；后续可直接读取 jsonl 文件减少启动成本。

## 错误处理

| 场景 | 行为 |
| --- | --- |
| pi 不存在 | Chat disabled，提示导入 rootfs 不满足要求 |
| pi RPC 启动失败 | 显示 stderr tail，提供 Open Terminal |
| response timeout | 标记请求失败但不立即杀 runtime；允许 abort/reload |
| stdout 非 JSON | raw log 折叠显示 |
| process exited | session status = error，保留 stderr tail |
| 附件太大 | 提示大小；MVP 可限制单文件 50MB，图片 20MB |
| 图片无法编码 | 当普通文件引用发送 |

## MVP 实现顺序

1. materialize pi workspace/config。
2. `PiRpcRuntime.start + get_state`。
3. `prompt + stream text_delta`。
4. tool/thinking card。
5. session CRUD。
6. 附件复制 + `@path` 插入。
7. 发送前文本/图片展开。
8. IDA MCP status/prompt。
9. abort/reload。
