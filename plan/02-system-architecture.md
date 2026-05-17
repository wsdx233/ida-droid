# 02 - 系统架构

## 总体架构

```text
Android App
├─ Compose App Shell / Navigation
│  ├─ Onboarding / Import
│  ├─ Home Dashboard
│  ├─ Agent Chat
│  ├─ Settings / Diagnostics
│  └─ launches Activity: VNC Viewer / Terminal
│
├─ EnvironmentManager
│  ├─ RootfsImporter
│  ├─ RootfsValidator
│  ├─ MetadataStore
│  └─ PiWorkspaceMaterializer
│
├─ ProotRuntime
│  ├─ proot binary installer
│  ├─ command builder / bind mounts
│  ├─ process launcher
│  └─ script runner
│
├─ ProcessSupervisorService (Foreground Service)
│  ├─ VNC/IDA process group
│  ├─ pi RPC runtimes
│  ├─ health probes
│  └─ logs / notifications
│
├─ VNC Subsystem
│  ├─ VncSessionManager
│  ├─ IDA/VNC startup script generator
│  └─ VNC Viewer Activity (AVNC-derived or external fallback)
│
├─ Terminal Subsystem
│  └─ ProotTerminalActivity (Termux terminal-view/emulator)
│
└─ Agent Subsystem
   ├─ PiAgentManager
   ├─ PiRpcRuntime
   ├─ SessionRepository
   ├─ MessageNormalizer
   ├─ AttachmentManager
   └─ IdaMcpStatus / prompt materializer
```

## 核心数据目录

Android host 侧建议：

```text
context.filesDir/
  proot/
    bin/proot
  envs/
    default/
      rootfs/                         # 解包后的 Linux rootfs
      tmp/                            # bind 到 /tmp 和 /var/tmp
      metadata.json
      .setup-complete
      logs/
  app-state/
    settings.json 或 DataStore
    sessions.db 或 sessions.json
context.cacheDir/
  imports/
  previews/
```

proot 内对应：

```text
/                                  -> context.filesDir/envs/default/rootfs
/root/ida-pro-9.3                  -> IDA
/root/pi_workspace                 -> agent 工作区
/root/pi_workspace/.upload         -> 附件
/root/pi_workspace/.pi-sessions    -> pi session files
/root/pi_workspace/.idadroid       -> App 生成的 pi config/logs/scripts
/tmp                               -> context.filesDir/envs/default/tmp
```

## 模块职责

### EnvironmentManager

负责“是否有可用环境”和“环境处于哪个阶段”。

职责：

- 安装/更新 proot 二进制。
- 导入 rootfs。
- 维护 metadata：rootfs 来源、导入时间、校验结果、rootfs arch、IDA path、pi path、VNC capability。
- 提供 `isReady()`、`needsImport()`、`validate()`。
- 创建 pi workspace 和默认配置。

### RootfsImporter

职责：

- 从 SAF URI 打开输入流。
- 根据文件名/魔数选择解压器。
- 解包到 staging 目录。
- 安全检查 tar entry。
- 处理 symlink/hardlink/chmod。
- 成功后原子替换 active rootfs。

### RootfsValidator

运行 proot 内验证脚本，输出结构化 JSON：

```json
{
  "ok": true,
  "arch": "aarch64",
  "home": "/root",
  "ida": { "exists": true, "path": "/root/ida-pro-9.3", "binary": "/root/ida-pro-9.3/ida" },
  "idaMcp": { "exists": true, "executable": true },
  "pi": { "exists": true, "version": "..." },
  "node": { "exists": true, "version": "v22..." },
  "mcpc": { "exists": true, "version": "..." },
  "vnc": { "mode": "xvfb-x11vnc", "server": "x11vnc", "xServer": "Xvfb", "wm": "openbox" },
  "warnings": []
}
```

### ProotRuntime

封装所有 proot 命令构建。

关键 API：

```kotlin
interface ProotRuntime {
    fun interactiveShellSpec(term: String = "xterm-256color", cwd: String = "/root"): TerminalLaunchSpec
    fun commandSpec(script: String, cwd: String = "/root", term: String = "dumb"): LaunchSpec
    fun workspaceCommandSpec(script: String): LaunchSpec
    fun run(script: String, timeoutMs: Long = 120_000): CommandResult
}
```

默认 proot 环境变量：

```text
HOME=/root
USER=root
LOGNAME=root
LANG=C.UTF-8
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
TERM=<term>
TMPDIR=/tmp
XDG_RUNTIME_DIR=/tmp/runtime-root
PI_SKIP_VERSION_CHECK=1
PI_TELEMETRY=0
```

默认 bind：

- `/dev`
- `/proc`
- `/sys`
- Android App files/cache
- `/storage`、`/sdcard`、`/mnt`（存在时）
- host tmp → `/tmp`、`/var/tmp`

### ProcessSupervisorService

需要 Foreground Service，因为 VNC/IDA/pi 可能长时间运行。

职责：

- 启动/停止/重启 VNC+IDA foreground process。
- 启动/停止 pi RPC process。
- App 进入后台时保持关键进程。
- 提供通知：`IDA GUI running`、`Agent session working`。
- 收集 stdout/stderr 到 ring buffer log。
- 进程异常退出后更新 UI 状态。

注意：

- 启动脚本必须 foreground wait，不要让 proot shell 启动后台进程后退出。
- proot 使用 `--kill-on-exit`，停止 supervisor process 时尽量清理子进程。

### VNC Subsystem

职责：

- 分配 localhost 端口（默认 5901，冲突时递增）。
- 生成并运行 VNC/IDA 启动脚本。
- 探测 VNC 端口 ready。
- 构造 VNC viewer intent/profile。
- 维护 GUI 状态：stopped/starting/running/error。

### Terminal Subsystem

职责：

- 独立 Activity 承载 `TerminalView`。
- 通过 `ProotRuntime.interactiveShellSpec()` 创建 `TerminalSession`。
- 提供 extra keys、copy/paste、字号、IME/insets 处理。

### Agent Subsystem

#### PiAgentManager

本地版 BoxedAgent agent-manager：

- `createSession()`
- `startSession()`
- `stopSession()`
- `deleteSession()`
- `prompt()`
- `abort()`
- 后续：`clone()`、`fork()`、`treeNavigate()`、`compact()`、`setModel()`、`setThinking()`

#### PiRpcRuntime

封装一个正在运行的 `pi --mode rpc` process：

- stdin 写 JSON line。
- stdout 按行读取 JSON。
- response 按 `id` 分发。
- event 转换为 UI state。
- stderr 进入日志和 error card。
- timeout、abort、stop。

#### SessionRepository

MVP 可用 Room 或 JSON 文件。推荐 Room：

```text
AgentSessionEntity(
  id,
  name,
  status,
  cwd,
  provider,
  model,
  thinkingLevel,
  autoCompactionEnabled,
  sessionFile,
  createdAt,
  updatedAt,
  lastActiveAt,
  error
)
```

消息历史不必由 App 完整持久化，优先读取 pi session file；UI 可缓存 normalized messages。

#### AttachmentManager

职责：

- 从 SAF 读取用户选择文件。
- 写入 host path：`rootfs/root/pi_workspace/.upload/<safe-name>`。
- 去重命名：`name`, `name-1`, `name-2`。
- composer 插入 proot path：`@/root/pi_workspace/.upload/<safe-name>`。
- 发送前展开文件引用。

#### IdaMcpStatus

职责：

- 检查 IDA/VNC/ida-mcp/mcpc 是否存在/运行。
- 生成 agent prompt 上下文。
- 后续可管理 ida-mcp bridge process。

## App 启动状态机

```text
NoEnvironment
  -> Importing
  -> Validating
  -> Ready
  -> RunningGui / RunningAgent / TerminalOpen
  -> ErrorRecoverable
  -> Reimporting
```

## 推荐技术栈

- Kotlin 2.x
- Android Gradle Plugin 8.x
- minSdk 26（可讨论降到 24；BoxedAgentAndroid 用 26，r2droid 用 24）
- targetSdk 35/36（采用 SAF，避免依赖 legacy external storage）
- Jetpack Compose + Material3
- Coroutines/Flow
- Room 或 kotlinx.serialization JSON store
- Okio/Apache Commons Compress/XZ for Java（rootfs 解包）
- terminal-emulator/terminal-view 模块
- VNC：AVNC 派生模块或外部 AVNC fallback

## 不引入本地 HTTP server 的理由

BoxedAgent 的 REST/WebSocket 层服务于 Docker/浏览器/远程多客户端。IDAdroid 是单机本地 App，直接管理 proot 和 pi RPC 更简单：

- 少跑一个 Node/Fastify server。
- 少一层认证/网络/WebSocket。
- 更容易和 Android 生命周期/ForegroundService 对齐。
- 仍然保留 BoxedAgent 的 session/runtime 语义。
