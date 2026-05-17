# 09 - 风险、关键决策与待确认问题

## 已建议的关键决策

### D1：用户自备 rootfs，不分发 IDA

决定：IDAdroid 只导入用户选择的 rootfs，不内置、不下载、不修改 IDA 授权。

原因：

- IDA 是商业软件，有授权限制。
- rootfs 可能包含用户自己的 license、插件、配置。
- App 分发体积和法律风险都不可接受。

影响：

- Onboarding 必须明确 rootfs 合约。
- 验证失败要可诊断。

### D2：App 直接管理 proot，不跑完整 BoxedAgent server

决定：agent 聊天使用 Kotlin 直接启动 `pi --mode rpc`，不在 Android 内启动 Fastify/BoxedAgent API。

原因：

- 更轻、更少依赖。
- Android 生命周期更可控。
- 单机 App 不需要 REST/WebSocket 中间层。

影响：

- 需要在 Kotlin 重写 BoxedAgent `AgentRuntime/AgentManager` 的核心逻辑。
- BoxedAgentAndroid API 层不能原样复用，但 UI/模型可参考。

### D3：终端用独立 Activity

决定：proot 终端采用 `TerminalActivity + Termux terminal-view/emulator`，不优先嵌入 Compose。

原因：

- r2droid/BoxedAgentAndroid 的经验都显示独立 Activity 对 IME/insets 更稳。

### D4：VNC 先明确许可路线

决定：若直接集成 AVNC 代码，需要接受 GPL-3.0-or-later 合规；否则只能把 AVNC 作为行为参考或外部 fallback。

原因：

- AVNC 源码是 GPL-3.0-or-later。
- VNC viewer 是本项目核心模块，许可影响整体发布。

建议：

- 如果 IDAdroid 计划开源并 GPL 兼容，集成 AVNC 是最快路线。
- 如果计划闭源或非 GPL，先做外部 VNC fallback，再找 permissive VNC 库。

### D5：MVP 不硬编码 mcpc 参数

决定：agent 先阅读 `IDA_MCP_MCPC_USAGE.md`，再按文档调用 `mcpc`/`ida-mcp`。

原因：

- ida-mcp/mcpc 版本可能随 rootfs 变化。
- 硬编码参数容易失效。

后续：可以在读取 usage doc 后生成模板或让用户配置。

## 主要风险

### R1：IDA 二进制架构兼容

风险：Android 设备多为 arm64，IDA Linux 版本可能是 x86_64。proot 本身不做 CPU 翻译。

缓解：

- rootfs 合约写清：若 IDA binary 架构不匹配，rootfs 必须自带 qemu/box64/FEX 等可用翻译层。
- 验证脚本检查 `uname -m` 和 `file ~/ida-pro-9.3/ida*`。
- UI 给出明确 warning。

### R2：GUI 依赖缺失导致黑屏/Qt xcb error

风险：IDA GUI 依赖 Qt/xcb/font/libGL，rootfs 未必完整。

缓解：

- 验证 VNC/X/WM 依赖。
- 启动脚本设置 `QT_QPA_PLATFORM=xcb`、`LIBGL_ALWAYS_SOFTWARE=1`、`NO_AT_BRIDGE=1`。
- 日志中突出 Qt/xcb 错误。
- 允许进入终端修复。

### R3：Android 后台杀进程

风险：VNC/IDA/pi 长时间运行，App 进入后台后被系统回收。

缓解：

- ForegroundService + notification。
- 请求忽略电池优化（可选，不强制）。
- 关键进程由 supervisor 管理。
- App 恢复时 probe 状态。

### R4：rootfs 很大，导入耗时/空间不足

风险：IDA rootfs 可达数 GB，解包失败会浪费时间和空间。

缓解：

- staging 目录，失败自动清理。
- 空间预检查。
- 流式解包，避免额外复制 archive。
- 进度和日志清晰。

### R5：tar 安全与权限问题

风险：恶意/异常 rootfs archive 可能 path traversal、奇怪 symlink、权限不可写。

缓解：

- canonical path 检查。
- hardlink 降级 symlink。
- chmod owner RWX。
- 只解包到 App 私有目录。

### R6：pi RPC 协议变化

风险：pi `--mode rpc` 命令/事件字段随版本变化。

缓解：

- 把协议集中在 `PiRpcProtocol`。
- 启动后 `get_state` 做 capability probe。
- unknown event 作为 raw log，不崩溃。
- 在 rootfs 合约中建议 pi 版本范围。

### R7：附件泄露/误发送

风险：用户把敏感文件作为附件，agent 可能上传到模型提供商。

缓解：

- 附件发送前显示路径/大小。
- 大文件提示确认。
- Settings 中说明模型提供商会接收文本/图片内容。
- 诊断包不包含附件内容。

### R8：AVNC 集成复杂度

风险：AVNC native submodules/CMake/NDK 集成耗时；与 Compose app 主题/Activity 栈冲突。

缓解：

- Phase 3 可先外部 VNC fallback。
- AVNC 单独 `:vnc-viewer` 模块。
- 保留最小 Viewer，删除 Home/DB/SSH 等。
- 先在单独 demo app 验证 native build。

## 待确认问题

1. **IDAdroid 最终许可证是什么？**  
   决定是否能直接集成 AVNC。

2. **目标设备 ABI 与 rootfs/IDA ABI 是什么？**  
   rootfs 是否已经包含可运行 IDA 的翻译层？

3. **rootfs archive 顶层结构固定吗？**  
   是直接包含 `/bin /usr /root`，还是外面包一层目录？是否需要用户选择 strip components？

4. **VNC server 是否保证已安装？**  
   如果没有，App 是否允许联网 apt install？当前计划默认不联网安装，只提示缺失。

5. **ida-mcp 的启动方式是什么？**  
   是 IDA 插件内启动、外部二进制 bridge，还是需要 `mcpc` 拉起？需要以 `IDA_MCP_MCPC_USAGE.md` 为准。

6. **是否需要多 rootfs/多项目？**  
   当前 MVP 只有 default environment。后续可以扩展多环境。

7. **pi 模型配置来源？**  
   是 rootfs 已配置，还是 App 提供 settings/models 编辑？MVP 可以只使用 rootfs 现有配置 + 简单编辑。

8. **是否需要导入目标二进制到 IDA/agent workspace？**  
   目前附件进入 `~/pi_workspace/.upload`；IDA 打开文件流程可后续细化。

## 建议优先验证的技术 spike

1. **proot + rootfs smoke test**  
   在目标 Android 设备上跑：`proot -r rootfs /bin/bash -lc 'pi --version'`。

2. **IDA GUI smoke test**  
   不写 App，先用手工命令确认 rootfs 内 VNC + IDA 可显示。

3. **pi RPC smoke test**  
   在 proot 内启动 `pi --mode rpc`，用简单 JSON line prompt 验证事件格式。

4. **AVNC module build spike**  
   把 AVNC native viewer 最小化集成到空 Android app，确认 NDK/CMake/submodules 可构建。

5. **附件路径 spike**  
   从 Android SAF 复制文件到 rootfs 内 `/root/pi_workspace/.upload`，proot shell 能读取。

## 发布前合规清单

- [ ] IDA/rootfs 免责声明。
- [ ] AVNC GPL 合规或替代方案。
- [ ] 第三方 license 页面。
- [ ] 不上传/收集用户 rootfs 内容。
- [ ] 诊断包脱敏。
- [ ] 删除环境会提示不可恢复。
- [ ] ForegroundService 通知说明用途。
