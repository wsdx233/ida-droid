# 07 - UI / UX 方案

## 设计原则

- **Chat-first，但不是 Chat-only**：用户多数时间在 agent 页面，但 VNC/终端是高频辅助入口。
- **移动端原生体验**：参考 BoxedAgentAndroid 的侧滑 panel 和 r2droid 的 Material3 风格，不做 WebView 包壳。
- **状态可见**：rootfs、VNC、IDA、agent、MCP 的状态必须一眼可见。
- **失败可诊断**：每个失败页面都要提供日志、终端、复制诊断信息。
- **不隐藏路径**：逆向/agent 用户需要看到真实 proot 路径。

## 首次启动 / Onboarding

### Screen: Welcome

内容：

- IDAdroid 简介。
- 提醒：App 不包含 IDA，需用户自备合法 rootfs。
- rootfs 合约摘要。
- 按钮：`选择 rootfs archive`。

### Screen: Import Progress

阶段展示：

```text
准备导入
解包 rootfs
配置 proot
验证 IDA/pi/VNC
创建 pi_workspace
完成
```

组件：

- Linear progress。
- 当前阶段标题。
- 当前文件/动作。
- 可展开 logs。
- Cancel（仅安全阶段可取消；取消后删除 staging）。

### Screen: Validation Result

成功：

- IDA：OK
- pi：OK
- ida-mcp：OK
- VNC：OK/Warning
- mcpc：OK/Warning

失败：

- Fatal items 红色。
- Warnings 黄色。
- 操作：重新选择 rootfs、打开日志、复制诊断。

## Home Dashboard

主页卡片：

```text
[Environment]
Ready · rootfs imported at ...
IDA: /root/ida-pro-9.3
pi: /usr/local/bin/pi
VNC: Xvfb+x11vnc

[Launch IDA GUI]
Stopped/Running · port 5901

[Proot Terminal]
Open shell

[Agent Chat]
N sessions · active: ...

[Diagnostics]
Validate / Logs / Settings
```

底部主导航建议：

- Home
- Chat
- Settings

VNC/Terminal 用 Activity 打开，不占底部主导航。

## Agent Chat 页面

参考 BoxedAgentAndroid：

```text
Top Bar
├─ session name
├─ model/thinking/context stats 横向滚动
├─ IDA/MCP 状态 chip
└─ buttons: Sessions / Tools / VNC / Terminal

Message List
├─ user bubble
├─ assistant markdown
├─ thinking card
├─ tool card
└─ system/error card

Composer
├─ attachment chips
├─ text input
└─ buttons: attach / send-stop / send mode
```

### Side overlays

不要在手机上做三栏常驻。使用全屏侧滑 overlay：

- Sessions overlay：session 列表、创建、启动/停止、重命名、删除。
- Tools overlay：Files、Pi Config、MCP Status、Logs。
- Settings overlay 可从顶部或 Home 进入。

### Session row

显示：

- name
- status：idle/running/working/stopped/error
- model/thinking
- last active
- error snippet（如有）

操作：

- select
- start/stop
- rename
- duplicate（后续）
- clone/fork/tree（后续）
- delete

## Composer 行为

### Idle session

- 输入为空：send disabled。
- 输入/附件非空：send 普通 prompt。

### Working session

- 输入为空：主按钮是 Stop。
- 输入/附件非空：点击 Send 弹出 mode：
  - Abort current & send now
  - Steer
  - Follow-up

### 附件

选择文件后：

- 文件复制到 `.upload`。
- composer 插入 `@/root/pi_workspace/.upload/name`。
- 下方显示 attachment chips。
- 删除 chip 时移除对应 composer ref（尽力）。

图片：

- chip 使用图片 icon。
- 可点击预览。
- 发送时作为 image payload。

## Message 渲染

### Assistant markdown

MVP：轻量 Markdown：

- paragraphs
- headings
- code blocks
- inline code/bold
- links 可复制

后续：Prism4j 高亮。

### Thinking card

- 默认折叠。
- 当前/最新 thinking 自动展开。
- 标题显示 token/时长（如果 pi stats 有）。

### Tool card

显示：

- tool name
- status：pending/running/done/error
- args summary
- result preview

展开后：

- args JSON pretty。
- result text。
- diff/command output 特化渲染（后续）。

### Error card

- stderr tail。
- 复制按钮。
- Open Terminal。
- Restart session。

## VNC Viewer UI

若集成 AVNC：

顶部/浮动 toolbar：

- Back
- Stop GUI
- Reconnect
- Keyboard
- Fit/Zoom
- Mouse mode
- More

底部虚拟键（可隐藏）：

```text
ESC TAB CTRL ALT ← ↓ ↑ → F1...F12（后续）
```

手势：

- 默认 auto。
- 设置中可选择 touchpad/touchscreen。

## Terminal UI

见 [05-proot-terminal.md](05-proot-terminal.md)。

Home/Chat 顶部提供快捷入口：

- Open Terminal
- Tail IDA log
- Run Validate

## Settings

分组：

### Environment

- Current rootfs status。
- Re-run validation。
- Reimport rootfs。
- Delete environment。
- Export diagnostic bundle。

### GUI/VNC

- VNC port base：5901。
- Geometry：设备自适应 / 1280x800 / 1920x1080 / custom。
- Color depth。
- VNC password：off/random/custom（后续）。
- Stop GUI on app exit：on/off。

### Agent

- Default provider/model/thinking。
- Edit `settings.json`。
- Edit `models.json`。
- Edit `AGENTS.md`。
- Auto compact。
- Max attachment size。

### Appearance

- Light/Dark/System。
- Language：System/中文/English。
- Terminal font size。

## Diagnostics 页面

卡片：

- Rootfs validation result。
- Process status：VNC/IDA/pi。
- Ports：5901 等。
- Recent logs：import/validate/ida-vnc/agent stderr。
- Buttons：
  - Copy diagnostics
  - Export logs
  - Open Terminal
  - Restart GUI
  - Restart active agent session

注意脱敏：

- 不复制 API keys。
- 不复制完整 `models.json` secrets。
- 不打包 IDA 文件。

## 空状态与错误文案

### No environment

```text
还没有导入 IDA rootfs。
请选择一个已安装 IDA Pro 9.3、ida-mcp 和 pi agent 的 Linux rootfs archive。
```

### GUI dependency missing

```text
rootfs 已可用于终端和 agent，但缺少 VNC/X 组件，暂时无法启动 IDA 图形界面。
请进入 proot 终端安装 Xtigervnc，或提供包含 Xvfb+x11vnc 的 rootfs。
```

### agent pi missing

```text
未找到 pi 命令。Agent Chat 不可用。
请确认 rootfs 中已安装 pi agent，且 pi 位于 PATH。
```

### MCP warning

```text
未找到 mcpc 或 ida-mcp 使用说明。Agent 仍可聊天，但无法可靠调用 IDA MCP。
```

## MVP UI 清单

- [ ] Welcome/import/validation。
- [ ] Home dashboard。
- [ ] Terminal Activity。
- [ ] VNC Viewer Activity 或 external fallback。
- [ ] Chat page with session list overlay。
- [ ] Message list + composer + attachments。
- [ ] Diagnostics logs。
- [ ] Settings minimal。
