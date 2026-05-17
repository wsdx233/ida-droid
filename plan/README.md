# IDAdroid 计划索引

IDAdroid 的目标是把用户已准备好的 IDA + ida-mcp + pi agent Linux rootfs 导入到 Android App 私有目录中，并提供三个入口：

1. **IDA 图形界面**：App 启动 proot 内的 X/VNC/IDA，并直接打开内置 VNC 客户端连接。
2. **proot 终端**：进入同一 rootfs 的交互式 shell。
3. **agent 聊天**：在 `~/pi_workspace` 中管理 pi session，支持附件，并让 agent 通过 `mcpc`/`ida-mcp` 操作 IDA MCP 做逆向。

## 已完成调研缓存

相关仓库已放到 `.cache/`：

- `.cache/r2droid`：proot 安装/运行、内置终端、Compose/Material3 UI 风格参考。
- `.cache/avnc`：Android VNC 客户端、LibVNCClient JNI、手势/键盘/VNC viewer 参考。
- `.cache/BoxedAgent`：pi `--mode rpc`、session/agent runtime、附件语义、WebSocket/API 设计参考。
- `.cache/BoxedAgentAndroid`：移动端 agent chat/session/files/terminal UI 与状态管理参考。

`.cache/` 已加入 `.gitignore`，只作为调研缓存，不建议提交。

## 文档结构

- [00-research.md](00-research.md)：相关仓库调研结论与可复用模块。
- [01-scope-and-requirements.md](01-scope-and-requirements.md)：产品范围、rootfs 合约、MVP/non-goals。
- [02-system-architecture.md](02-system-architecture.md)：总体架构、模块划分、数据流。
- [03-rootfs-import-proot.md](03-rootfs-import-proot.md)：rootfs 导入、验证、proot 运行细节。
- [04-vnc-ida-gui.md](04-vnc-ida-gui.md)：IDA GUI/VNC 启动、AVNC 集成、生命周期。
- [05-proot-terminal.md](05-proot-terminal.md)：终端入口、Termux terminal 集成方案。
- [06-agent-pi-chat.md](06-agent-pi-chat.md)：pi RPC、本地 session 管理、附件、IDA MCP 接入。
- [07-ui-ux.md](07-ui-ux.md)：移动端信息架构、主要页面与交互。
- [08-implementation-roadmap.md](08-implementation-roadmap.md)：分阶段实现计划与验收标准。
- [09-risks-and-decisions.md](09-risks-and-decisions.md)：关键决策、风险与待确认问题。

## 推荐总体路线

1. **先做可启动闭环**：导入 rootfs → 验证 proot shell → 打开终端。
2. **再做 IDA GUI 闭环**：proot 内启动 VNC server + IDA → 内置 VNC 连接。
3. **最后做 agent 闭环**：App 直接启动 `pi --mode rpc` → 聊天流式事件 → 附件 → `mcpc`/`ida-mcp` 逆向流程。
4. **补齐产品化**：session 管理、日志、崩溃恢复、设置页、导入/重装、测试矩阵。
