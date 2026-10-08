# IDAdroid 架构文档

> 版本: 2.0 | 更新日期: 2026-07-14

## 系统概览

IDAdroid 是 Android 端 IDA Pro 逆向工程 + AI Agent 平台，通过 proot 运行 Linux rootfs，实现无需 root 的移动端逆向分析。

```
┌─────────────────────────────────────────────────────┐
│                    Android App                      │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │ VNC GUI  │  │ Terminal │  │   AI Agent Chat   │  │
│  │ (IDA UI) │  │ (Shell)  │  │                   │  │
│  └────┬─────┘  └────┬─────┘  └────────┬──────────┘  │
│       │              │                  │            │
│  ┌────┴──────────────┴──────────────────┴─────────┐  │
│  │              proot rootfs (Linux)               │  │
│  │  ┌─────────┐ ┌────────┐ ┌──────────┐ ┌───────┐ │  │
│  │  │IDA Pro  │ │ida-mcp │ │ pi agent │ │ tools │ │  │
│  │  │  9.3    │ │        │ │ (RPC)    │ │       │ │  │
│  │  └─────────┘ └────────┘ └──────────┘ └───────┘ │  │
│  └────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

## 模块结构

```
idamcp-/
├── app/                          # 主应用模块
│   └── src/main/java/dev/idadroid/
│       ├── agent/                # PI RPC、Session、配置和工作区
│       │   ├── PiRpcTransport.kt     # JSONL 请求关联、超时和失败闭合
│       │   ├── PiAgentManager.kt     # 生命周期、事件、状态和 Session
│       │   ├── PiSessionMigration.kt # 旧历史到 PI v3 tree 的安全迁移
│       │   ├── PiMessageNormalizer.kt # PI 消息格式标准化
│       │   ├── PiConfigManager.kt    # 运行时配置物化与凭据隔离
│       │   ├── AttachmentManager.kt  # 附件读取、限制和容器存储
│       │   ├── WorkspaceManager.kt   # 容器工作区目录与文件管理
│       │   ├── AgentSessionRepository.kt # Session 原子持久化
│       │   ├── AgentModelCatalog.kt   # 模型目录解析
│       │   └── AgentModels.kt         # Agent 状态和消息模型
│       ├── proot/                # proot 运行时
│       ├── env/                  # 环境管理
│       ├── mcp/                  # MCP 客户端
│       ├── ui/                   # Compose UI
│       ├── settings/             # 应用设置
│       ├── files/                # 文件管理
│       └── util/                 # 工具函数
├── terminal-emulator/            # 终端模拟器 (Termux)
├── terminal-view/                # 终端视图组件
├── plan/                         # 设计文档
└── docs/                         # 技术文档
```

## Agent / PI RPC 架构

Android 不直接复制一套 LLM HTTP、工具循环或会话引擎。`PiAgentManager` 在 rootfs 内启动官方 `pi --mode rpc`，通过 `PiRpcTransport` 使用 JSONL stdin/stdout 通讯；PI 自己负责 Provider、模型请求和工具执行。

### `PiRpcTransport`

- 每个请求生成 UUID，并按 `id` 与 `command` 关联响应；响应乱序不会串台。
- stdout 只接受 JSONL；空行忽略，坏 JSON、无类型记录、未知响应和命令不匹配都会使当前 transport 失败闭合。
- stderr 独立 drain，不与协议流混用；界面只显示脱敏诊断摘要。
- 命令有独立 deadline；超时、EOF、进程退出和取消都会完成所有 pending 请求并终止进程。
- 关闭顺序是 RPC abort（可用时）、销毁进程、有限等待、强制销毁；不会无限等待工具返回。

### `PiAgentManager`

Manager 以 generation 隔离会话切换和旧进程回调，公开 `StateFlow<AgentUiState>`。发送采用同步 admission：只有消息/附件被当前会话接受后 UI 才清空草稿；模型未配置、rootfs 未 ready、当前 turn 不允许插入或 Manager 已关闭时返回 `false`，草稿保留。

状态流转：

```text
idle/running
  → connecting (prompt 已被接受)
  → receiving / executing_tool / compacting
  → running (agent_settled 或 get_state 确认无 streaming/pending)
  → error/stopped (命令失败、EOF、超时或用户取消)
```

Manager 处理 `agent_start/end/settled`、`message_*`、工具执行、重试、compact 和 queue 事件，维护当前消息、工具时间线、首个 delta 时间、队列和 session stats。`steer`/`follow-up` 只在 PI 报告支持时入队，不会把失败的草稿误报为已发送。

### Session 与配置

- `AgentSessionRepository` 以原子临时文件更新 `.idadroid/agent-sessions.json`，索引损坏或写盘失败会显式报错，不能静默覆盖用户数据。
- PI session 文件位于 `.idadroid/pi-sessions/`；旧 IDAdroid JSONL 历史只在确认可转换后写入 PI v3 tree，原文件保持不变。
- `PiConfigManager` 在写盘前验证模型、环境变量和受管理的 RPC 参数，将旧 `baseURL`/`envKey` 转成 PI 的 `baseUrl`/`api`/`apiKey`，保留未知配置字段并物化到私有 runtime 目录。诊断消息会脱敏，未解析的凭据引用交由 PI 处理；受管理的 `--mode`、`--session` 和 session 目录参数不可被 extra args 覆盖。
- `IdaDroidSettings.envSettings.workspacePath` 是工作区唯一来源，Agent 与设置页共用 rootfs 目录选择器。文件、配置、session 和附件均使用此容器路径，不再另存 SAF tree URI。外部绑定路径、路径越界和符号链接不可选。
- 切换工作区时停止旧 RPC 并清空旧 UI 状态，然后加载该目录的配置、session 和文件；原目录保持不变。目录读取独立于 PI 连接失败，只有最新浏览请求可发布列表。工作区文件附件进入对应 session 的私有草稿，而不是只读取后丢弃。

### Agent UI

`AgentScreen` 使用 Material 3 响应式布局：宽屏显示 session rail，窄屏使用全屏 Sessions/Tools/Workspace/Configuration/Search 面板。消息列表支持工具/思考时间线、错误重试、保存回复、搜索跳转、近底部自动跟随和“回到最新”；用户上移时流式 delta 不抢回滚动位置。`AgentDraftStore` 将每个 session 的文本和附件放在应用私有目录，附件 materialize 在 IO dispatcher 完成。

## proot / proroot 运行时

`IdaProotRuntime` 在每次构造启动命令前调用 `RootfsConfigurator.ensureResolverConfig`。resolver 内容固定为：

```text
nameserver 8.8.8.8
nameserver 8.8.4.4
options use-vc timeout:2 attempts:2
```

arm64 且五个随 APK 分发的 proroot `.so` 完整性校验通过、能力探测在 4 秒内成功时优先使用 proroot；否则选择现有 proot 并保留 `--kill-on-exit`。选择结果按 rootfs 和 payload 身份缓存，诊断明确报告回退原因。proroot 当前不提供 proot 等价的 descendant kill-on-exit 保证。

## 数据流

```text
AgentComposer
  → PiAgentManager.sendPrompt() [同步 admission]
  → PiRpcTransport.request("prompt")
  → pi --mode rpc
  → JSONL event
  → generation/session guard
  → AgentUiState / AgentScreen
```

历史恢复通过 `get_messages`；运行时状态通过 `get_state`、`get_session_stats` 以及事件 settle 交叉确认，避免只依赖单个 UI 回调清除 loading 状态。

## 配置系统

### Pi 配置 (`pi-config.json`)

```json
{
  "defaultProvider": "deepseek",
  "defaultModel": "deepseek-chat",
  "defaultThinkingLevel": "auto",
  "env": {
    "DEEPSEEK_API_KEY": "sk-..."
  },
  "appendSystem": "...",
  "modelsText": "..."
}
```

### 模型目录 (`models.json`)

使用 PI 原生 `baseUrl`、`api`、`apiKey` 和模型列表，支持凭据环境变量引用；简易表单与完整编辑器均保留 Provider、模型、compat、headers 等扩展字段。配置保存等待实际写盘与运行时物化完成，失败不发布新快照或关闭编辑器。

外层选择器支持独立 ID 的多个自定义 Provider；模型的 `reasoning` 能力由用户明确声明。思考强度选择以 PI 返回的实际 `thinkingLevel` 为准，不支持或被钳制时不会伪装成已应用。

### PI 更新

`IdaDroidApplication` 持有 `PiUpdateManager`，启动就绪后检测新版并请求用户确认。忽略记录按环境与更新版本生效。更新先安装、校验新的独立 npm prefix，再原子切换已有启动器；旧包保留供正在运行的进程使用，检测或安装失败不阻止已安装版本运行。


### Session 存储 (`agent-sessions.json`)

记录所有 Agent Session 的元数据（ID、名称、状态、provider/model 配置等）。

## 安全设计

### 路径安全
- 工作区文件操作有路径越界检查（`require(file.path.startsWith(root.path))`)
- proot rootfs 隔离

### API Key 安全
- Key 存储在应用私有目录
- 不在日志中输出 Key
- 传输使用 HTTPS

### 工具安全
- `run_shell` 在 proot rootfs 内执行，无宿主文件系统访问
- 工具执行有超时保护
- 文件操作限制在工作区内

## 性能优化

### 流式输出
- 33ms flush 节拍（~30fps），平衡流畅度和性能
- StringBuilder 缓冲 pending deltas，避免高频 state 更新

### 上下文管理
- Token 估算（chars/3 粗略估算）
- 自动截断避免 context overflow
- 手动压缩保留关键信息

### 并行工具执行
- 多工具调用时使用 `async` + `awaitAll` 并行执行
- 单工具调用时直接执行，避免 async 开销
