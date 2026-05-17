# 01 - 产品范围与需求细化

## 产品定位

IDAdroid 是一个 Android 原生应用，用于在手机/平板上运行用户自备的 IDA Pro Linux rootfs，并提供：

- IDA 图形界面访问。
- proot Linux 终端。
- 基于 pi 的 agent 聊天，结合 IDA MCP 做逆向辅助。

IDAdroid 不分发 IDA，不内置 IDA license，不绕过 IDA 授权。用户必须自行准备合法 rootfs。

## 首次安装主流程

1. 用户首次打开 App。
2. App 检测当前没有可用环境，进入 onboarding。
3. 用户通过 Android 文件选择器选择 rootfs archive。
4. App 将 archive 解包到私有目录。
5. App 运行环境验证脚本，确认 IDA、ida-mcp、pi、可选 mcpc/VNC 依赖存在。
6. App 自动创建 `~/pi_workspace`、`.upload`、`.pi-sessions`、pi 配置目录。
7. App 显示 Home：
   - Launch IDA GUI
   - Proot Terminal
   - Agent Chat
   - Settings/Diagnostics

## rootfs 合约

### 必须存在

rootfs 进入 proot 后，以 root 用户 HOME 为 `/root`，必须满足：

```text
/root/ida-pro-9.3/                         # IDA 安装目录
/root/ida-pro-9.3/ida-mcp                  # IDA MCP 二进制/入口，需可执行
/root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md    # mcpc + ida-mcp 使用说明
pi                                          # pi agent 命令在 PATH 中可用
node                                        # pi 运行所需 Node.js，建议 >= 22
```

### 强烈建议存在

```text
mcpc                                        # agent 可通过 mcpc 调用 ida-mcp
bash                                        # 交互 shell
python3, git, rg, file                      # agent 常用工具
```

### GUI/VNC 依赖：至少一组

方案 A：TigerVNC/TightVNC 类：

```text
Xtigervnc 或 vncserver
openbox/fluxbox/xfce4-session 等轻量 WM
```

方案 B：Xvfb + x11vnc：

```text
Xvfb
x11vnc
openbox/fluxbox 等轻量 WM
```

IDA Qt GUI 还通常需要 rootfs 内已安装字体、xcb、xkb、GL/software rendering 依赖。若 IDA 本身是 x86_64 而设备是 arm64，rootfs 还必须自带可用的 x86_64 兼容/翻译层（例如 qemu-user/box64/FEX 等之一）。App 只负责启动和诊断，不负责解决 IDA 二进制架构兼容问题。

### App 自动创建/管理

导入后 App 在 proot 内创建：

```text
/root/pi_workspace/
/root/pi_workspace/.upload/
/root/pi_workspace/.pi-sessions/
/root/pi_workspace/.pi/
/root/pi_workspace/.idadroid/pi-agent/
/root/pi_workspace/.idadroid/logs/
```

pi 运行时使用：

```text
WORKSPACE=/root/pi_workspace
PI_CODING_AGENT_DIR=/root/pi_workspace/.idadroid/pi-agent
session dir=/root/pi_workspace/.pi-sessions
```

## 功能需求

### 1. 环境导入与管理

MVP：

- 支持从 SAF 选择 `.tar`、`.tar.gz/.tgz`、`.tar.xz/.txz`。
- 解包时显示阶段、进度、当前文件/日志。
- 防 tar path traversal。
- 处理 symlink、hardlink、文件权限。
- 写入环境 metadata 和 ready marker。
- 验证脚本输出结构化结果。
- 支持失败后重试、重新导入、删除环境。

后续：

- 支持 `.tar.zst`。
- 支持导入目录树。
- 支持多个环境切换。
- 支持导出诊断包。

### 2. IDA GUI/VNC

MVP：

- “Launch IDA GUI” 自动启动 proot 内 VNC server 和 IDA。
- 使用内置 VNC viewer 连接 `127.0.0.1:<port>`。
- 如果 VNC/IDA 已运行，直接复用并连接。
- 提供 Stop/Restart GUI。
- 提供常用虚拟键和键盘唤起。

后续：

- VNC 分辨率/缩放/DPI 设置。
- 剪贴板同步。
- VNC 密码/随机 token。
- 多显示/多 IDA 实例。
- “只启动 VNC，不启动 IDA”的调试模式。

### 3. proot 终端

MVP：

- 独立 `TerminalActivity`，进入同一 rootfs。
- 默认工作目录 `/root` 或 `/root/pi_workspace` 可配置。
- 支持 ESC/TAB/CTRL/ALT/方向键/PGUP/PGDN。
- 支持复制/粘贴。
- App 从后台恢复时终端不轻易被销毁。

后续：

- 多终端 session。
- 保存终端 transcript。
- 一键运行诊断脚本、启动脚本。

### 4. agent 聊天

MVP：

- 本地管理 pi RPC process，而不是连接远端 BoxedAgent API。
- 在 `~/pi_workspace` 创建 session。
- 支持 session 列表、创建、启动、停止、删除、重命名。
- 支持发送消息、流式响应、thinking、tool call/result 卡片。
- 支持 abort。
- 支持附件：文件复制到 `.upload`，composer 插入 `@path`。
- 支持图片作为 pi RPC image payload。
- 自动写入 IDA/ida-mcp/mcpc 使用提示到 agent 上下文。

后续：

- fork/clone/session tree。
- 模型切换、thinking level、auto compaction、manual compact。
- 文件浏览器、下载/预览。
- pi config editor（settings/models/system/append system/AGENTS）。

### 5. IDA MCP 集成

MVP：

- 验证 `~/ida-pro-9.3/ida-mcp` 和 `IDA_MCP_MCPC_USAGE.md`。
- agent workspace 中生成 `AGENTS.md` 或 `APPEND_SYSTEM.md`，提示 agent：
  - IDA 路径。
  - MCP 使用说明路径。
  - 先读 usage 文档，再通过 `mcpc` 调用 `ida-mcp`。
  - 如果 IDA GUI/MCP 未运行，应提示用户启动 IDA GUI。
- UI 提供 MCP 状态卡片：IDA/VNC/ida-mcp/mcpc 是否可用。

后续：

- 一键启动/重启 ida-mcp bridge。
- 解析 usage 文档后生成可执行的 mcpc 命令模板。
- MCP 调用日志可视化。

## 非目标（当前不做）

- 不内置/下载/破解 IDA。
- 不自动从网络安装完整 rootfs/IDA。
- 不承诺所有 Android 设备都能跑 IDA GUI；rootfs 架构和图形依赖由用户准备。
- 不做 Docker/BoxedAgent 后端移植。
- MVP 不做多人/远程访问。

## MVP 验收场景

1. 导入用户 rootfs 后，App 显示环境 ready。
2. 点击 Terminal，能进入 proot，运行：

```bash
pwd
ls ~/ida-pro-9.3
pi --version
```

3. 点击 Launch IDA GUI，VNC viewer 打开并看到 IDA 界面。
4. 点击 Agent Chat，新建 session，发送：

```text
请检查当前 IDA MCP 使用说明，并确认如何通过 mcpc 调用 ida-mcp。
```

agent 能读取 `~/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md` 并给出可执行步骤。
5. 上传一个二进制/图片/文本附件后，agent 能通过 `@/root/pi_workspace/.upload/...` 读取。
