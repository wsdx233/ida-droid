# 08 - 实现路线图

## Phase 0 - 项目骨架

目标：创建可构建的 Android 项目。

任务：

- 初始化 Gradle Android project。
- Kotlin + Compose + Material3。
- 引入 coroutines/serialization。
- 引入 `terminal-emulator`、`terminal-view` 模块。
- 建立包结构：

```text
app/src/main/java/.../idadroid/
  env/
  proot/
  terminal/
  vnc/
  agent/
  ui/
  data/
  util/
```

验收：

- `./gradlew :app:assembleDebug` 成功。
- 空 Home 页面可运行。

## Phase 1 - rootfs 导入闭环

目标：用户能导入 rootfs，App 能验证并标记 ready。

任务：

1. `ProotBinaryInstaller`
   - asset 复制 proot。
   - chmod。
   - ABI/存在性检查。

2. `RootfsImporter`
   - SAF URI 读取。
   - tar/gzip/xz 解包。
   - staging/atomic activate。
   - 进度/log Flow。

3. `RootfsConfigurator`
   - resolv.conf。
   - hosts。
   - tmp/var/tmp。
   - fake fips。

4. `RootfsValidator`
   - proot 内运行验证脚本。
   - 输出 JSON。
   - fatal/warning 分级。

5. UI
   - Welcome。
   - Import progress。
   - Validation result。
   - Home ready state。

验收：

- 能导入测试 rootfs。
- 验证能识别 IDA/pi/ida-mcp/VNC。
- 失败不会破坏已有 rootfs。

## Phase 2 - proot 终端

目标：终端可进入导入后的 rootfs。

任务：

- `IdaProotRuntime` command builder。
- `ProotTerminalActivity`。
- extra keys。
- copy/paste。
- Home 打开终端。
- 终端启动失败日志。

验收命令：

```bash
pwd
whoami
ls ~/ida-pro-9.3
pi --version
```

## Phase 3 - IDA GUI/VNC MVP

目标：一键启动 IDA GUI 并连接。

任务：

1. `VncSessionManager`
   - port allocator。
   - status state。
   - TCP ready probe。

2. start script materializer
   - `start-ida-vnc.sh`。
   - `Xtigervnc` 或 `Xvfb+x11vnc`。
   - WM。
   - IDA binary candidate。
   - logs。

3. `ProcessSupervisorService`
   - start/stop GUI process。
   - foreground notification。
   - stdout/stderr ring log。

4. VNC viewer
   - 短期：external VNC fallback。
   - 正式：集成 AVNC viewer module。

5. UI
   - Home GUI card。
   - Launch/Stop/Reconnect。
   - Diagnostics log。

验收：

- 点击 Launch IDA GUI 后 VNC 端口 ready。
- Viewer 看到 IDA 界面。
- Stop 后 VNC/IDA 进程清理。

## Phase 4 - pi RPC 最小聊天

目标：新建 session，发送 prompt，看到流式回复。

任务：

- [x] `PiWorkspaceMaterializer` 写入 `.pi` / `.idadroid/pi-agent`。
- [x] `rpc-stdio-guard.cjs` 写入。
- [x] `PiRpcRuntime.start()` 启动 proot 内 `pi --mode rpc`。
- [x] JSON line stdin/stdout。
- [x] pending response map。
- [x] event Flow。
- [x] `PiAgentManager.create/start/stop/prompt/abort` MVP。
- [x] `MessageNormalizer` 最小实现。
- [x] Chat UI：message list + composer。

验收：

- [ ] 真机新建 session。
- [ ] 发送“你好”。
- [ ] 流式显示 assistant 回复。
- [ ] abort 可停止当前 turn。
- [ ] session 停止/重启后可继续。

实现状态：代码已推进到 M3 MVP，可构建；尚需在实际 rootfs/真机上按上述验收跑一轮。

## Phase 5 - agent session 与 tool UI

目标：接近 BoxedAgentAndroid 的核心体验。

任务：

- [x] session list overlay（BoxedAgent Android 风格 bottom sheet）。
- [x] rename/delete/start/stop。
- [x] runtime status：idle/running/working/error。
- [x] thinking card（可展开/自动展开）。
- [x] tool call/result card（按 read/edit/write/bash 等分类基础还原）。
- [x] stderr/error card（错误与 raw log 仍保留在 state；主 UI 以 tool/error 展示为主）。
- [x] stats/context/model display（顶部 token/cost/context + model sheet）。
- [x] load historical messages（runtime `get_messages` + session file fallback）。

验收：

- agent 执行 bash/read/edit 等 tool 时 UI 能展示。
- 最新 tool/thinking 自动展开。
- 错误可复制日志。

## Phase 6 - 附件与文件引用

目标：用户能把附件发送给 agent。

任务：

- [x] SAF 多文件选择。
- [x] `AttachmentManager` 写入 `.upload`。
- [x] safe file name/去重。
- [x] composer 发送时自动追加 `@/root/pi_workspace/.upload/...`。
- [x] 发送前解析 `@file`。
- [x] 文本内联为 `<file>`。
- [x] 图片 base64 放 RPC images。
- [x] attachment chips/preview（composer chip + 文件浏览器 attach）。

验收：

- 文本附件可被 agent 读取总结。
- 图片附件以 image payload 发送。
- 缺失文件引用不会阻塞发送。

## Phase 7 - IDA MCP 工作流

目标：agent 能可靠引导/调用 IDA MCP。

任务：

- [x] 默认 `AGENTS.md`/`APPEND_SYSTEM.md` 写入 IDA/MCP 路径与规则。
- [x] MCP status card（Tools/IDA tab）。
- [ ] `Run MCP diagnostics` 脚本。
- [x] Chat/Tools 顶部 Launch IDA GUI shortcut。
- [x] 一键插入“读取 MCP 使用说明并连接当前 IDA”的 prompt。

验收：

- agent 能读取 `IDA_MCP_MCPC_USAGE.md`。
- agent 能给出符合文档的 `mcpc` 调用方式。
- IDA 未启动时 agent 能提示用户启动 GUI。

## Phase 8 - 高级 session 功能

目标：补齐 BoxedAgent 类似 session 能力。

任务：

- model list / set model。
- thinking level。
- auto/manual compact。
- duplicate session。
- clone/fork。
- session tree navigate。
- file browser。
- Pi config editor。

验收：

- 常用 session 操作无需进终端。
- session history 和 pi session file 不混乱。

## Phase 9 - 稳定性、安全与发布

任务：

- ForegroundService 通知与后台策略。
- 进程清理和 orphan probe。
- 诊断包脱敏。
- 大 rootfs 导入压力测试。
- 多设备测试。
- AVNC 许可合规文件。
- 隐私/免责声明。
- 崩溃日志页面。

验收：

- 长时间运行 IDA/VNC 不被轻易杀。
- App 重启后状态恢复正确。
- 删除环境能清理私有文件。
- release build 可用。

## 推荐里程碑

### M1：可导入 + 可终端

包含 Phase 0-2。

### M2：可启动 IDA GUI

包含 Phase 3。

### M3：可聊天 + 附件

包含 Phase 4-6。

### M4：IDA MCP 辅助逆向可用

包含 Phase 7。

### M5：接近 BoxedAgent 体验

包含 Phase 8-9。

## 首批实现文件建议

```text
app/src/main/java/.../env/EnvironmentManager.kt
app/src/main/java/.../env/RootfsImporter.kt
app/src/main/java/.../env/RootfsValidator.kt
app/src/main/java/.../proot/IdaProotRuntime.kt
app/src/main/java/.../terminal/ProotTerminalActivity.kt
app/src/main/java/.../vnc/VncSessionManager.kt
app/src/main/java/.../agent/PiRpcRuntime.kt
app/src/main/java/.../agent/PiAgentManager.kt
app/src/main/java/.../agent/AttachmentManager.kt
app/src/main/java/.../ui/IdaDroidApp.kt
```

## 测试策略

### 单元测试

- tar path normalization。
- safe filename。
- `@file` parser。
- message normalizer。
- pi RPC response/event routing。

### 仪器/真机测试

- SAF 导入。
- chmod/symlink。
- TerminalActivity IME。
- ForegroundService。
- VNC viewer。

### 手工验收脚本

导入后运行：

```bash
/root/pi_workspace/.idadroid/scripts/validate.sh
pi --version
ls -la /root/ida-pro-9.3
```

GUI：

```bash
/root/pi_workspace/.idadroid/scripts/start-ida-vnc.sh
```

Agent：

```bash
cd /root/pi_workspace
PI_CODING_AGENT_DIR=/root/pi_workspace/.idadroid/pi-agent pi --mode rpc --session-dir /root/pi_workspace/.pi-sessions
```
