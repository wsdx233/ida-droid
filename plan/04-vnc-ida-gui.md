# 04 - IDA GUI / VNC 方案

## 目标

用户点击“Launch IDA GUI”后，App 自动：

1. 启动 proot。
2. 在 rootfs 内启动 X server + VNC server + window manager。
3. 启动 IDA。
4. 用内置 VNC viewer 连接本机 `127.0.0.1:<port>`。

## VNC 集成路线

### 路线 A：集成 AVNC viewer（推荐，但需接受 GPL）

从 `.cache/avnc` 拆出：

- `VncActivity`
- `VncViewModel`
- `VncClient`
- `FrameView` / OpenGL renderer
- `InputHandler` / `VirtualKeys`
- 必要 model：`ServerProfile`、`LoginInfo`、`UserCredential`
- JNI/CMake：`native-vnc`、`libvncserver`、`libjpeg-turbo`、`wolfssl`

移除/不引入：

- Home/Profile DB
- SSH tunnel
- Zeroconf discovery
- Import/export server profiles
- AVNC settings UI（只保留 IDAdroid settings 中必要项）

IDAdroid 构造固定 localhost profile：

```kotlin
ServerProfile(
    name = "IDA",
    host = "127.0.0.1",
    port = vncPort,
    password = optionalPassword,
    securityType = 0,
    viewMode = ServerProfile.VIEW_MODE_NORMAL,
    gestureStyle = "auto",
    useRawEncoding = true
)
```

许可提醒：AVNC 是 GPL-3.0-or-later。若复制/链接其代码，IDAdroid 分发需满足 GPL-3.0 兼容要求。

### 路线 B：外部 VNC fallback

如果暂不处理 AVNC 集成：

- App 仍启动 proot 内 VNC/IDA。
- 通过 `vnc://127.0.0.1:<port>` Intent 调外部 VNC 客户端。
- 如果设备未安装客户端，提示安装 AVNC。

优点：MVP 更快；缺点：不满足“直接内置操作体验”。

## VNC server 启动策略

### 自动检测模式

启动脚本按优先级选择：

1. `Xtigervnc` 直接提供 X+VNC。
2. `vncserver` wrapper。
3. `Xvfb` + `x11vnc`。

检测：

```bash
command -v Xtigervnc
command -v vncserver
command -v Xvfb
command -v x11vnc
command -v openbox || command -v fluxbox || command -v xfce4-session
```

### 默认参数

- display：`:1`
- port：`5901`，冲突时 App 分配 `5902+`
- geometry：默认 `1280x800`，根据设备可配置
- depth：`24`
- localhost only：是
- password：MVP 可空；后续默认随机密码
- WM：优先 `openbox`，再 `fluxbox`，再 `xfce4-session`

## start-ida-vnc.sh 设计

由 App 生成到：

```text
/root/pi_workspace/.idadroid/scripts/start-ida-vnc.sh
```

脚本必须保持 foreground，便于 Android `Process` 管理生命周期。

示例结构：

```bash
#!/usr/bin/env bash
set -euo pipefail

export HOME=/root
export DISPLAY=:${IDADROID_DISPLAY:-1}
export VNC_PORT=${IDADROID_VNC_PORT:-5901}
export GEOMETRY=${IDADROID_GEOMETRY:-1280x800}
export DEPTH=${IDADROID_DEPTH:-24}
export XDG_RUNTIME_DIR=/tmp/runtime-root
export QT_QPA_PLATFORM=xcb
export LIBGL_ALWAYS_SOFTWARE=1
export NO_AT_BRIDGE=1

mkdir -p "$XDG_RUNTIME_DIR" /tmp/.X11-unix /root/pi_workspace/.idadroid/logs
chmod 700 "$XDG_RUNTIME_DIR" || true

log=/root/pi_workspace/.idadroid/logs/ida-vnc.log
: > "$log"

cleanup() {
  jobs -pr | xargs -r kill 2>/dev/null || true
  pkill -f "x11vnc.*$VNC_PORT" 2>/dev/null || true
  pkill -f "Xtigervnc.*:$IDADROID_DISPLAY" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

start_wm() {
  if command -v openbox >/dev/null 2>&1; then openbox >>"$log" 2>&1 & return; fi
  if command -v fluxbox >/dev/null 2>&1; then fluxbox >>"$log" 2>&1 & return; fi
  if command -v xfce4-session >/dev/null 2>&1; then xfce4-session >>"$log" 2>&1 & return; fi
}

start_ida() {
  cd /root/ida-pro-9.3
  for bin in ./ida ./ida64 ./idat ./idat64; do
    if [ -x "$bin" ]; then "$bin" >>"$log" 2>&1 & echo $! > /tmp/idadroid-ida.pid; return; fi
  done
  echo "No executable IDA binary found" >>"$log"
  return 1
}

if command -v Xtigervnc >/dev/null 2>&1; then
  Xtigervnc "$DISPLAY" -localhost -rfbport "$VNC_PORT" -geometry "$GEOMETRY" -depth "$DEPTH" -SecurityTypes None >>"$log" 2>&1 &
elif command -v vncserver >/dev/null 2>&1; then
  vncserver "$DISPLAY" -localhost yes -geometry "$GEOMETRY" -depth "$DEPTH" >>"$log" 2>&1 &
elif command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1; then
  Xvfb "$DISPLAY" -screen 0 "${GEOMETRY}x${DEPTH}" +extension GLX +render -noreset >>"$log" 2>&1 &
  sleep 1
  x11vnc -display "$DISPLAY" -localhost -nopw -rfbport "$VNC_PORT" -forever -shared >>"$log" 2>&1 &
else
  echo "No supported VNC/X server found" >>"$log"
  exit 127
fi

sleep 2
start_wm || true
start_ida

# 保持 foreground；任一关键后台进程退出时脚本退出，Android supervisor 可标记 error。
wait -n
```

实际实现需要：

- 根据 rootfs 检测结果裁剪参数。
- 支持 VNC password。
- port ready 探测后再打开 viewer。
- 日志实时回传 UI。

## App 侧 VncSessionManager

状态：

```kotlin
enum class GuiStatus { Stopped, Starting, Running, Error }

data class GuiSessionState(
    val status: GuiStatus,
    val port: Int?,
    val display: Int?,
    val pid: Int?,
    val message: String,
    val startedAt: Long?
)
```

API：

```kotlin
suspend fun startGui(openViewer: Boolean = true): Result<GuiSessionState>
suspend fun stopGui(): Result<Unit>
suspend fun restartGui(): Result<GuiSessionState>
suspend fun connectViewer()
suspend fun probe(): GuiSessionState
```

启动流程：

```text
startGui
  -> ensure environment ready
  -> allocate port
  -> materialize start script
  -> ProcessSupervisorService.start("gui", proot script)
  -> waitUntilTcpOpen(127.0.0.1, port, timeout=30s)
  -> open VncActivity(profile)
```

## VNC viewer UX

MVP 控件：

- 关闭 Viewer（不一定停止 IDA）。
- Stop IDA GUI。
- Reconnect。
- Keyboard。
- Mouse/Touch mode。
- Ctrl/Alt/Tab/Esc virtual keys。
- Fit screen / 1:1 / zoom reset。

后续：

- 分辨率切换会重启 VNC server 或调用 server resize。
- 剪贴板同步。
- PiP。
- 横竖屏自适应。

## IDA MCP 状态关联

VNC/IDA GUI 与 MCP 是相关但不完全相同的状态：

- VNC ready：端口可连接。
- IDA process running：`pgrep`/pid file 存在。
- IDA UI ready：VNC 可见不等于 IDA 完全加载。
- ida-mcp ready：取决于 `ida-mcp`/IDA plugin 的实际工作方式。

MVP 不强行判断 MCP ready，只提供：

- usage doc 存在。
- `ida-mcp` executable 存在。
- `mcpc` command 存在。
- 可选 probe 脚本由用户/usage doc 定义。

Agent prompt 应说明：如果 MCP 调用失败，先提示用户确认 IDA GUI 已打开并加载目标二进制。

## 错误处理

常见错误与提示：

| 错误 | UI 提示 | 建议操作 |
| --- | --- | --- |
| VNC server 缺失 | rootfs 缺少 VNC/X 依赖 | 进入终端安装或换 rootfs |
| port 被占用 | 端口被占用，已尝试下一个 | 若仍失败，重启 App |
| IDA binary 找不到 | 未找到 ida/ida64/idat/idat64 | 检查 `/root/ida-pro-9.3` |
| Qt xcb error | IDA GUI 依赖缺失 | 查看 `ida-vnc.log`，补安装 xcb/font/libgl |
| 架构不匹配 | IDA binary 与设备 ABI 不匹配 | rootfs 需内置翻译层或换匹配版本 |
| VNC 可连但黑屏 | WM/X/IDA 启动异常 | 打开日志/终端排查 |

## 测试矩阵

- Android arm64 真机：小屏/平板。
- VNC server：Xtigervnc、Xvfb+x11vnc。
- IDA 二进制候选：`ida`、`ida64`。
- 横竖屏切换。
- App 切后台 5 分钟后回到 viewer。
- 停止 GUI 后确认 proot 子进程清理。
