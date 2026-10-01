# IDAdroid — Android 端 IDA Pro 逆向工程 + AI Agent 平台

IDAdroid 将完整的 IDA Pro 逆向工程环境、ida-mcp 工具链和 AI Agent 集成到 Android 设备上，通过 proot 运行 Linux rootfs，实现无需 root 的移动端逆向分析体验。

## 核心功能

### 1. IDA 图形界面
通过 proot 内启动 X/VNC server + IDA Pro，内置 VNC 客户端直接连接，在手机上操作 IDA 图形界面。

### 2. proot 终端
在同一 rootfs 中提供交互式 shell，支持完整的 Linux 命令行工具链。

### 3. AI Agent 聊天
Agent 页面通过本地 `pi --mode rpc` 管理 proot 内的 Agent 进程，不在 Android 侧重复实现 LLM API 和工具循环，支持：
- **流式对话** — JSONL RPC 事件驱动的文本、思考和工具时间线
- **可靠状态** — 请求 ID 关联、响应超时、EOF/坏 JSON 失败闭合、取消和重连
- **Session 管理** — 创建、切换、删除、重命名、启动/停止，历史持久化与旧格式迁移
- **模型与推理控制** — Provider/Model、thinking、自动/手动 compact、steer/follow-up 队列
- **附件与工作区** — 私有草稿、图片/文件附件、SAF 工作区浏览、导入/导出和保存回复
- **深度索引模式** — CodeGraph + ECC + codebase-memory 联动分析
- **可诊断错误** — 当前阶段、等待时长、stderr 摘要、重试和停止入口

### 4. proot / proroot 运行时
arm64 设备优先探测随 APK 分发的 `proroot`；能力探测失败或 ABI 不匹配时自动回退到带 `--kill-on-exit` 的 `proot`。两条路径都在启动前修复 rootfs 的 `etc/resolv.conf`，并保留 `/tmp`、`/proc`、工作区等绑定。

## 技术架构

```
Android App (Kotlin / Jetpack Compose / Material 3)
├── Agent UI
│   ├── AgentScreen — 会话、消息时间线、搜索、工作区和工具面板
│   ├── AgentComposer — 附件、模型、队列、停止和发送
│   └── AgentMarkdown / AgentToolTimeline — 回复与工具渲染
├── PI RPC
│   ├── PiAgentManager — 生命周期、Session、状态机和事件归并
│   ├── PiRpcTransport — JSONL 请求关联、超时、失败闭合
│   └── PiConfigManager — 运行时配置物化和凭据隔离
├── proot / proroot runtime — rootfs 启动、DNS 修复和能力回退
├── proot rootfs (完整 Linux 环境)
│   ├── IDA Pro 9.3
│   ├── ida-mcp (MCP server for IDA)
│   ├── jadx / python / npm
│   └── .idadroid/ (配置、脚本、日志)
├── terminal-emulator (Termux 终端模拟器)
├── terminal-view (终端 UI 组件)
└── VNC 客户端 (推荐 AVNC)
```

### 项目结构

| 模块 | 说明 |
|------|------|
| `app/` | 主应用：UI、Agent 管理、proot 运行时、配置 |
| `terminal-emulator/` | Termux 终端模拟器库 |
| `terminal-view/` | 终端 View 组件 |
| `plan/` | 设计文档和实现计划 |
| `docs/` | 技术文档 |

### 关键源文件

| 文件 | 职责 |
|------|------|
| `AgentScreen.kt` | Agent 主界面与响应式消息时间线 |
| `PiAgentManager.kt` | PI 生命周期、RPC 事件分发、Session 与工作区状态 |
| `PiRpcTransport.kt` | JSONL RPC 传输、请求关联、超时和进程失败闭合 |
| `PiConfigManager.kt` | PI 配置读写、运行时物化、系统提示词和凭据隔离 |
| `ProrootSupport.kt` | arm64 proroot 完整性/能力探测与 proot 回退 |
| `RootfsConfigurator.kt` | resolv.conf、hosts、tmp 和 rootfs 启动配置 |
| `AiConfigEditor.kt` | 可视化 AI Provider/Model 配置 UI |
| `AiConfigTools.kt` | 配置导入导出、API 连接测试、模型列表拉取 |
| `EndpointCompleter.kt` | API 端点 URL 自动补全 |
| `AgentModelCatalog.kt` | models.json 解析与模型目录 |
| `IdaProotRuntime.kt` | proot 进程启动与参数管理 |

## 构建

### 环境要求

- Android Studio (AGP 9.0+)
- JDK 17
- Kotlin 2.3.21
- Android SDK 36 (compileSdk)
- NDK 29.0.14206865
- minSdk 26 (Android 8.0)
- targetSdk 28

### 构建步骤

```bash
# Debug build
./gradlew assembleDebug

# Release build (需要配置签名)
# 在 local.properties 中设置:
# IDADROID_RELEASE_STORE_FILE=...
# IDADROID_RELEASE_STORE_PASSWORD=...
# IDADROID_RELEASE_KEY_ALIAS=...
# IDADROID_RELEASE_KEY_PASSWORD=...
./gradlew assembleRelease
```

## 使用

1. **导入 rootfs**：首次启动需要导入预构建的 IDA rootfs 镜像
2. **配置 AI Provider**：在设置页选择 Provider，填入 API Key，添加模型
3. **启动 Agent Session**：创建新会话，选择模型，开始对话
4. **逆向分析**：Agent 可通过 mcpc 调用 ida-mcp 操作 IDA，也可使用 jadx 等工具
5. **文件传输**：通过 idadroid-file 桥在 Android 主机和容器之间传输文件

## AI Provider 配置

支持的开箱即用 Provider：

| Provider | 环境变量 | 常用模型 |
|----------|----------|----------|
| OpenAI | `OPENAI_API_KEY` | gpt-4o, o1, o3 |
| Anthropic Claude | `ANTHROPIC_API_KEY` | claude-sonnet-4, claude-opus-4 |
| DeepSeek | `DEEPSEEK_API_KEY` | deepseek-chat, deepseek-reasoner |
| Google Gemini | `GOOGLE_API_KEY` | gemini-2.5-flash, gemini-2.5-pro |
| OpenRouter | `OPENROUTER_API_KEY` | 300+ 聚合模型 |
| Moonshot KIMI | `MOONSHOT_API_KEY` | moonshot-v1-128k, kimi-thinking |
| 阿里通义千问 | `DASHSCOPE_API_KEY` | qwen-max, qwen-plus |
| 火山引擎豆包 | `ARK_API_KEY` | doubao-1-5-pro |
| 百度千帆 | `BAIDU_API_KEY` | ernie-4.0 |
| 腾讯混元 | `HUNYUAN_API_KEY` | hunyuan-turbos |
| SiliconFlow | `SILICONFLOW_API_KEY` | DeepSeek-V3, Qwen2.5-72B |
| Mistral AI | `MISTRAL_API_KEY` | mistral-large, codestral |
| Groq | `GROQ_API_KEY` | llama-3.3-70b (超低延迟) |
| xAI Grok | `XAI_API_KEY` | grok-3 |
| Together AI | `TOGETHER_API_KEY` | Llama-3.3-70B, Qwen2.5-72B |
| 自定义 | — | 手动填写 Base URL |

## 文档

- [架构文档](docs/architecture.md) — 系统架构、模块设计、数据流
- [开发指南](docs/development.md) — 环境配置、开发流程、常见问题
- [项目审计](docs/project-audit.md) — 问题分析与优化记录
- [计划文档](plan/README.md) — 架构设计、实现路线、风险评估
- [深度索引模式](docs/deep-index-mode.md) — CodeGraph + ECC + Memory 工具链

## 许可证

本项目仅供合法的逆向工程研究和学习使用。
