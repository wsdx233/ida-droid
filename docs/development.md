# IDAdroid 开发指南

> 更新日期: 2026-07-14

## 环境要求

- Android Studio (Ladybug+)
- JDK 17
- Android SDK 35+ (compileSdk)
- Kotlin 2.3.21
- Gradle 9.0.1+ (AGP)

## 构建配置

### Debug 构建

```bash
./gradlew assembleDebug
```

### Release 构建

需要在 `local.properties` 或环境变量中配置签名：

```properties
IDADROID_RELEASE_STORE_FILE=/path/to/keystore
IDADROID_RELEASE_STORE_PASSWORD=***
IDADROID_RELEASE_KEY_ALIAS=***
IDADROID_RELEASE_KEY_PASSWORD=***
```

## 项目结构

详见 [架构文档](architecture.md)。

## PI Agent 开发指南

### 添加新的 Provider / Model

Provider 与模型由工作区私有的 `.idadroid/pi-agent/models.json` 管理，编辑入口是 Agent 配置页的可视化编辑器。`PiConfigManager.materialize()` 会把旧格式 `baseURL`/`envKey` 转成 PI 使用的 `baseUrl`/`api`/`apiKey`，并从 `env` 解析凭据；不要把密钥写入日志、诊断或提交到仓库。

```json
{
  "providers": {
    "my-provider": {
      "baseUrl": "https://api.example.com/v1",
      "api": "openai-completions",
      "envKey": "MY_PROVIDER_API_KEY"
    }
  }
}
```

PI 本身负责请求格式、重试、工具循环和 Provider 兼容性。Android 侧不应重新添加 HTTP 适配层；需要新增 RPC 能力时，先确认当前 PI 版本的命令/事件，再在 `PiAgentManager` 中加入带 timeout 的调用和状态更新。

### 修改 Agent RPC

`PiRpcTransport` 是单进程、单 session generation 的 JSONL 传输层。请求必须通过 `request(type, fields, timeoutMs)` 发送，不要直接写 stdin。事件在 `PiAgentManager.handleEvent()` 中按 generation 和 active session 过滤。

新增命令时必须同时考虑：

1. 成功响应和 `PiRpcCommandException` 的 UI 文案；
2. timeout、EOF、坏 JSON、进程退出时的失败闭合；
3. 是否影响 `turnActive`、`processingPhase`、队列或 session 持久化；
4. 用户取消后是否清除 pending 状态，而不是一直显示 loading。

发送入口 `sendPrompt()` 是同步 admission，返回 `false` 表示消息尚未被接受，UI 必须保留草稿。只有返回 `true` 后才允许清空草稿；附件 materialize 在 IO dispatcher 执行。

### 修改 System Prompt

默认追加提示词位于 `PiConfigManager.kt` 的 `defaultSystemAppendPrompt()`。用户可在 Agent 配置页修改 `APPEND_SYSTEM.md`。提示词应只描述 IDAdroid 工作区、IDA/MCP 路径和安全边界；工具定义由 PI/扩展提供，不要在 Android 侧复制一份。

### Session 与历史

`AgentSessionRepository` 负责 `.idadroid/agent-sessions.json` 的原子更新。PI session 文件在 `.idadroid/pi-sessions/`，启动时传入 `--session-dir` 和具体 `--session`。旧版本历史由 `PiSessionMigration` 转换为 PI v3 tree；转换失败时原文件必须保持不变。

### 调试

```kotlin
// Compose 通过 collectAsStateWithLifecycle 观察
piAgentManager.state.value
piAgentManager.state.value.messages
piAgentManager.state.value.activeStats
```

不要打印完整 RPC、stderr、配置文件或环境变量；它们可能包含 prompt、文件内容和凭据。UI 只显示 `PiConfigManager.redact()` 之后的错误和 stderr 摘要。

### Runtime / DNS

`IdaProotRuntime` 启动前会调用 `RootfsConfigurator.ensureResolverConfig()`。不要恢复 rootfs 内的绝对 `resolv.conf` symlink；配置器会先用 `lstat` 删除链接，再写入固定的 nameserver 与 `use-vc` 选项。arm64 设备优先使用完整性校验通过且 probe 成功的 proroot，其他情况使用带 `--kill-on-exit` 的 proot。

## UI 开发指南

### 主要页面

| 页面 | 文件 | 说明 |
|------|------|------|
| Home | `HomeScreen.kt` / `IdaDroidApp.kt` | 环境、IDA/VNC、Terminal、Agent 入口 |
| Agent Chat | `AgentScreen.kt` | 响应式聊天、Session、工具、工作区和配置面板 |
| Agent components | `AgentComposer.kt` / `AgentToolTimeline.kt` / `AgentMarkdown.kt` | 输入、队列、附件、工具和 Markdown 渲染 |
| AI Config | `AiConfigEditor.kt` / `AgentModelControls.kt` | Provider、Model、凭据和 PI 配置 |
| Settings | `SettingsScreen.kt` | 应用设置 |
| About | `AboutScreen.kt` | 版本与许可 |

### 添加新的 UI 状态字段

1. 在 `AgentModels.kt` 的 `AgentUiState` 中添加字段。
2. 在 `PiAgentManager` 的事件/命令路径中更新字段，并保证 generation 失效时不写回旧会话。
3. 在 `AgentScreen` 或拆出的 Composable 中通过 `collectAsStateWithLifecycle` 读取。
4. 如果字段代表处理中状态，同时更新停止、超时、EOF、失败和 settle 路径，避免一直 loading。

## 测试

### 单元测试

```bash
./gradlew test
```

现有测试：
- `PiMessageNormalizerTest`: PI 消息格式标准化
- `PiRpcTransportTest`: 乱序响应、坏 JSON、超时、EOF 和进程清理
- `PiSessionMigrationTest`: 原生历史迁移、工具/图片内容与失败回滚

### 添加测试

```kotlin
class MyTest {
    @Test
    fun testSomething() {
        // 测试代码
    }
}
```

测试文件放在 `app/src/test/java/dev/idadroid/` 下。

## 常见问题

### Q: 如何添加新的 API Key 环境变量？

在 Agent 配置页的 `env` 中添加合法的 shell 变量名，并在 `models.json` 的 Provider 中引用 `envKey`。不要在 Kotlin 源码或日志中硬编码密钥。

### Q: 如何修改 PI 命令超时？

在具体 `PiRpcTransport.request(..., timeoutMs = ...)` 调用处设置与命令语义匹配的 deadline。正常的工具执行不能依赖一个任意的整轮超时；连接、状态探测和 compact 使用不同上限。

### Q: 如何修改流式等待提示？

更新 `AgentUiState.processingPhase` 的事件映射和 `AgentProcessingStatus`；同时覆盖 `agent_start`、首个 delta、工具事件、settled、abort、EOF 和 timeout。

## 代码规范

- Kotlin 代码遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- 使用 `runCatchingSuspending`（非 `runCatching`）处理 suspend 函数的异常
- 使用 `Mutex` 保护共享可变状态
- 使用 `@Volatile` 标注跨协程访问的可变字段
- 注释使用中文，代码使用英文
