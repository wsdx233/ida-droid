# 05 - proot 终端方案

## 目标

提供一个稳定、手机友好的终端入口，进入同一 IDA rootfs，方便用户手工调试、运行脚本、安装缺失依赖、验证 IDA/MCP/pi 状态。

## 技术选择

参考 r2droid：

- `terminal-emulator` 模块
- `terminal-view` 模块
- `TerminalActivity` 独立 Activity
- `TerminalSession` 本地进程 PTY

不建议 MVP 把终端直接嵌入 Compose 页面，原因：

- IME/insets 容易和 Compose 布局冲突。
- TerminalView 本身是 Android View，独立 Activity 更容易管理焦点、键盘、旋转。
- r2droid/BoxedAgentAndroid 都显示独立 Activity 更稳。

## Activity 结构

```text
ProotTerminalActivity
├─ status/toolbar row
│  ├─ close
│  ├─ cwd/status
│  ├─ A-/A+
│  └─ reconnect/new shell
├─ TerminalView
└─ ExtraKeysBar
   ├─ ESC / / / - / HOME / ↑ / END / PGUP
   └─ TAB / CTRL / ALT / ← / ↓ / → / PGDN
```

MVP 可先复用 r2droid 的 xml layout 或用纯 View 动态构造，避免 Compose 嵌套。

## 启动 shell

`ProotRuntime.interactiveShellSpec()` 返回：

```kotlin
data class TerminalLaunchSpec(
    val executable: String,
    val workingDirectory: String,
    val args: Array<String>?,
    val environment: Array<String>
)
```

示例 proot 内执行：

```text
/bin/bash -l
```

默认 cwd：

- 设置项：`/root` 或 `/root/pi_workspace`。
- 推荐默认 `/root/pi_workspace`，因为 agent 工作区在这里。

环境变量：

```text
HOME=/root
WORKSPACE=/root/pi_workspace
PI_CODING_AGENT_DIR=/root/pi_workspace/.idadroid/pi-agent
PI_SKIP_VERSION_CHECK=1
PI_TELEMETRY=0
TERM=xterm-256color
LANG=C.UTF-8
```

## Extra keys

保留 r2droid/BoxedAgentAndroid 经验：

第一行：

```text
ESC   /   -   HOME   ↑   END   PGUP
```

第二行：

```text
TAB   CTRL   ALT   ←   ↓   →   PGDN
```

CTRL/ALT 是一次性 modifier：按下后高亮，下一个键/字符消费后自动复位。

## 复制/粘贴

MVP：

- 长按进入 TerminalView 自带选择/复制模式。
- Android 剪贴板粘贴按钮或系统菜单。
- `TerminalSessionClient.onCopyTextToClipboard` / `onPasteTextFromClipboard`。

后续：

- 顶部 toolbar 放 Paste。
- 复制当前屏幕/全部 transcript。

## 字号与布局

- 默认字号 14sp 或按设备宽度估算。
- toolbar 提供 A-/A+。
- 旋转时重新 bind layout，但保留 `TerminalSession`。
- `windowSoftInputMode=adjustResize` 或参考 BoxedAgentAndroid 的手动 insets；需要真机验证。

## 终端生命周期

MVP 简化：

- 每打开一次 TerminalActivity 创建一个 shell session。
- 用户关闭 Activity 时结束 shell。
- 旋转不结束 shell。

后续：

- 支持多个可恢复 terminal session。
- 终端进程进入 ForegroundService 管理。
- App 崩溃后恢复/清理孤儿 proot process。

## 快捷命令

可以在终端页或 Diagnostics 页提供快捷命令，一键写入/执行：

```bash
# 环境验证
/root/pi_workspace/.idadroid/scripts/validate.sh

# 查看 IDA/VNC 日志
tail -f /root/pi_workspace/.idadroid/logs/ida-vnc.log

# 查看 pi 版本
pi --version

# 查看 usage doc
sed -n '1,200p' /root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md

# 检查进程
ps aux | grep -E 'ida|vnc|Xvfb|Xtigervnc|pi|mcpc'
```

## 与 agent/VNC 的关系

- 终端、VNC、agent 使用同一 rootfs 和 workspace。
- 终端可用于修复 GUI/agent 缺失依赖。
- 终端启动不应自动杀掉正在运行的 VNC/agent。
- 终端中用户手动 kill 进程后，App 的状态需要通过 probe 更新。

## 错误处理

| 场景 | 行为 |
| --- | --- |
| 环境未导入 | Terminal 按钮 disabled；提示先导入 rootfs |
| proot binary 缺失 | 尝试重新复制 asset；失败显示诊断 |
| shell 不存在 | fallback `/bin/sh` |
| TerminalSession exit | 显示 exit code，回车关闭或按钮重连 |
| App 后台被杀 | 下次启动 probe 并清理失效状态 |

## 实现步骤

1. 引入 `terminal-emulator`、`terminal-view` 模块。
2. 实现 `IdaProotRuntime.buildInteractiveLaunch()`。
3. 实现 `ProotTerminalActivity`。
4. 加 extra keys、copy/paste、字号调整。
5. Home Dashboard 增加 Terminal 按钮。
6. 在 Diagnostics 中增加“复制终端启动命令/环境变量”。
