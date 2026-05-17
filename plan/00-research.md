# 00 - 相关仓库调研

## 调研缓存

| 仓库/目录 | 缓存位置 | 用途 |
| --- | --- | --- |
| `wsdx233/r2droid` | `.cache/r2droid` | proot 导入/安装/运行、终端、Compose/Material3 UI 风格 |
| `gujjwal00/avnc` | `.cache/avnc` | VNC client、LibVNCClient JNI、手势、虚拟键、剪贴板、TLS/encoding |
| `wsdx233/BoxedAgent` | `.cache/BoxedAgent` | pi `--mode rpc` 后端 runtime、session 生命周期、附件/文件语义 |
| `/workspace/BoxedAgentAndroid` | `.cache/BoxedAgentAndroid` | Android 原生 agent chat UI、session/tools/files/terminal 操作 |

## r2droid 结论

重点文件：

- `.cache/r2droid/app/src/main/java/top/wsdx233/r2droid/util/ProotInstaller.kt`
- `.cache/r2droid/app/src/main/java/top/wsdx233/r2droid/util/R2Runtime.kt`
- `.cache/r2droid/app/src/main/java/top/wsdx233/r2droid/activity/TerminalActivity.kt`
- `.cache/r2droid/app/src/main/java/top/wsdx233/r2droid/feature/prootsetup/ProotSetupScreen.kt`
- `.cache/r2droid/terminal-emulator/`
- `.cache/r2droid/terminal-view/`

可复用/参考点：

1. **proot runtime 目录结构**  
   r2droid 使用 `context.filesDir/proot`，其中有：
   - `bin/proot`
   - `ubuntu/` rootfs
   - `tmp/` host 临时目录
   - `.setup-complete`、`.rootfs-extracted`、`.setup-stages/*` marker

2. **proot 启动参数**  
   `R2Runtime.buildProotPrefix()` 使用：
   - `proot -L --link2symlink --kill-on-exit --root-id -r <rootfs>`
   - 绑定 `/dev`、`/proc`、`/sys`、`/storage`、`/sdcard`、App files/cache
   - `HOME=/root`、`PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`
   - `TMPDIR=/tmp`、`TERM=<term>`

3. **rootfs 解包安全**  
   `ProotInstaller.extractRootfsArchive()` 处理：
   - `.tar`、`.tar.gz/.tgz`、`.tar.xz/.txz`
   - tar path strip
   - canonical path 防穿越
   - symlink/hardlink 处理
   - chmod 修复 owner 可读写/目录可执行权限

4. **rootfs 配置**  
   解包后写入：
   - `etc/resolv.conf`
   - apt/dpkg proot 兼容配置
   - `/tmp`、`/var/tmp` 权限
   - fake `/proc/sys/crypto/fips_enabled`，规避部分 crypto 库崩溃

5. **终端实现选择**  
   r2droid 同时有 Compose 内嵌终端和独立 `TerminalActivity`。独立 Activity 更稳，能避开 Compose/IME/insets 问题。IDAdroid 建议采用独立 Activity。

对 IDAdroid 的调整：

- r2droid 的 rootfs 是下载 Ubuntu 后自动编译 radare2；IDAdroid 应改成 **用户选择本地 rootfs archive → App 解包 → 验证 IDA/pi/ida-mcp/mcpc/VNC 依赖**。
- 保留 marker、进度、日志、retry/reinstall 机制。
- proot command builder 可直接按 r2droid 模式重写为 `IdaProotRuntime`。

## AVNC 结论

重点文件：

- `.cache/avnc/app/src/main/java/com/gaurav/avnc/Architecture.kt`
- `.cache/avnc/app/src/main/java/com/gaurav/avnc/ui/vnc/VncActivity.kt`
- `.cache/avnc/app/src/main/java/com/gaurav/avnc/viewmodel/VncViewModel.kt`
- `.cache/avnc/app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt`
- `.cache/avnc/app/src/main/java/com/gaurav/avnc/model/ServerProfile.kt`
- `.cache/avnc/app/CMakeLists.txt`

可复用/参考点：

1. **分层清晰**
   - `VncActivity`：Viewer UI、toolbar、PiP、window/orientation。
   - `VncViewModel`：连接状态、Framebuffer、剪贴板、输入事件。
   - `VncClient`：JNI wrapper，封装 LibVNCClient 的 connect/process/input/clipboard。
   - `FrameView`/OpenGL renderer：显示 framebuffer。
   - `InputHandler`/`VirtualKeys`：触屏、键盘和虚拟键。

2. **直接启动连接**  
   AVNC 通过 `ServerProfile(host, port, password, securityType, viewMode, gestureStyle...)` 创建 `VncActivity` Intent。IDAdroid 可以隐藏 AVNC 的 Home/Profile 管理，只构造 localhost profile：
   - host：`127.0.0.1`
   - port：由 `VncSessionManager` 分配，默认 `5901`
   - security：MVP 可用本地 `localhost` + no password，后续支持随机密码

3. **native 构建要求**
   - 依赖 CMake/NDK。
   - 需要 AVNC submodules：`libvncserver`、`libjpeg-turbo`、`wolfssl`。
   - `VncClient` 在 companion object 中 `System.loadLibrary("native-vnc")`。

4. **许可风险**
   AVNC 是 GPL-3.0-or-later（`COPYING.txt`）。如果直接复制/链接 AVNC 源码到 IDAdroid，整体分发需满足 GPL-3.0 兼容要求。若不希望受 GPL 约束，应仅作为行为参考，或使用外部 AVNC Intent 作为可选 fallback。

对 IDAdroid 的调整：

- 若接受 GPL：把 AVNC viewer 相关代码拆成 `:vnc-viewer` 模块，移除 Home/DB/SSH/Discovery，只保留 localhost viewer、输入、剪贴板、虚拟键。
- 若不接受 GPL：先实现“启动外部 AVNC/系统 VNC 客户端”的降级方案，后续寻找 permissive VNC viewer。

## BoxedAgent 结论

重点文件：

- `.cache/BoxedAgent/server/src/agent/agent-runtime.ts`
- `.cache/BoxedAgent/server/src/agent/agent-manager.ts`
- `.cache/BoxedAgent/server/src/agent/pi-config.ts`
- `.cache/BoxedAgent/server/src/routes/sessions.ts`
- `.cache/BoxedAgent/server/src/ws/terminal.ts`
- `.cache/BoxedAgent/server/src/docker/docker-service.ts`

可复用/参考点：

1. **pi RPC 启动方式**

```text
pi --mode rpc [--session <file> | --session-dir <dir>] [--provider ...] [--model ...] [--thinking ...]
```

运行时环境：

```text
PI_CODING_AGENT_DIR=/workspace/.boxedagent/pi-agent
PI_SKIP_VERSION_CHECK=1
PI_TELEMETRY=0
NODE_OPTIONS=--require <rpc-stdio-guard.cjs>
```

IDAdroid 对应建议：

```text
HOME=/root
WORKSPACE=/root/pi_workspace
PI_CODING_AGENT_DIR=/root/pi_workspace/.idadroid/pi-agent
PI_SKIP_VERSION_CHECK=1
PI_TELEMETRY=0
NODE_OPTIONS=--require /root/pi_workspace/.idadroid/pi-agent/rpc-stdio-guard.cjs
```

2. **RPC 协议形态**

App/后端向 pi stdin 写 JSON line：

```json
{"id":"req_1","type":"prompt","message":"...","images":[...]}
```

pi stdout 返回两类 JSON line：

- response：`{"type":"response","id":"req_1","success":true,"data":...}`
- event：`agent_start`、`turn_start`、`message_update`、`tool_execution_start/update/end`、`compaction_start/end`、`turn_end` 等

BoxedAgent 将事件再发布给 WebSocket；IDAdroid 可以在 Kotlin 内直接把 stdout 事件映射为 UI state。

3. **session 管理语义**

BoxedAgent 的 session 行为可直接复刻到本地：

- create/start/stop/delete
- prompt/abort
- duplicate（复制配置，不复制历史）
- clone/fork（调用 pi RPC clone/fork，创建新的 session 记录并绑定新 session file）
- tree navigate（停掉 runtime 后改 session active node）
- model/thinking/auto-compaction/compact/stats

4. **附件语义**

BoxedAgent 约定：

- 上传目录：`/workspace/.upload`
- composer 插入：只插入 `@/workspace/.upload/file` 或相对文件引用，不额外加“请读取附件”等隐含提示。
- 发送前展开 `@file`：
  - 文本文件内联成 `<file name="/path">...</file>`。
  - 图片作为 RPC `images` payload，并在 prompt 中放空 `<file name="/path"></file>`。
  - 缺失/不可读引用保持普通文本，不阻塞发送。

IDAdroid 对应路径替换：

- proot 内：`/root/pi_workspace/.upload`
- UI 展示可显示 `~ /pi_workspace/.upload/...`，发送给 pi 时使用绝对路径。

5. **stdio guard**

BoxedAgent 通过 `NODE_OPTIONS=--require rpc-stdio-guard.cjs` 防止 pi RPC 大量 stdout/stderr 写入时出现 ENOBUFS/EPIPE 崩溃。IDAdroid 应复用该脚本思想，并在 materialize pi config 时写入。

## BoxedAgentAndroid 结论

重点文件：

- `.cache/BoxedAgentAndroid/app/src/main/java/com/boxedagent/android/ui/AppViewModel.kt`
- `.cache/BoxedAgentAndroid/app/src/main/java/com/boxedagent/android/ui/BoxedAgentApp.kt`
- `.cache/BoxedAgentAndroid/app/src/main/java/com/boxedagent/android/data/Models.kt`
- `.cache/BoxedAgentAndroid/app/src/main/java/com/boxedagent/android/data/MessageNormalizer.kt`
- `.cache/BoxedAgentAndroid/app/src/main/java/com/boxedagent/android/TerminalActivity.kt`
- `.cache/BoxedAgentAndroid/app/src/main/java/com/boxedagent/android/ui/terminal/RemoteTerminalView.kt`

可复用/参考点：

1. **移动端 agent UI**
   - Chat-first。
   - Boxes/Sessions/Tools 用全屏侧滑 overlay，而不是简单底部 Tab。
   - assistant text/thinking/tool cards 流式展示。
   - 最新 thinking/tool 自动展开，历史折叠。
   - token/cost/context stats 显示在 chat 顶部并可横向滚动。

2. **附件与文件 UI**
   - Android SAF 选择文件。
   - 图片附件生成 preview，发送时作为 image payload。
   - 普通文件复制到 workspace `.upload`，composer 插入 `@path`。

3. **终端经验**
   BoxedAgentAndroid 的 `RemoteTerminalView` 是“远端 WebSocket terminal”版；IDAdroid 的 proot terminal 是本地 PTY，更适合直接使用 r2droid 的 `TerminalActivity` + Termux `TerminalSession`。但其手写 Activity/insets/extra keys 处理值得参考。

4. **不建议原样复用 API 层**
   BoxedAgentAndroid 连接的是远端 REST/WebSocket 服务；IDAdroid 推荐在 App 内用 Kotlin 直接管理 proot process 和 pi RPC，不再引入本地 HTTP server。

## 综合建议

- **Android 基础**：Kotlin + Jetpack Compose + Material3 + Activity/ForegroundService 混合。
- **proot/terminal**：以 r2droid 为主参考。
- **VNC**：以 AVNC 为主参考，但先处理 GPL 许可决策。
- **agent**：以 BoxedAgent 的 pi RPC runtime 语义为主参考，以 BoxedAgentAndroid 的 UI/消息模型为主参考。
- **避免过度引入服务端**：不要在 Android 内再跑完整 BoxedAgent/Fastify；直接连接 `pi --mode rpc` 更轻、更稳。
