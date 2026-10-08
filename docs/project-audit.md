# IDAdroid 项目审计报告

> 本文对应 PI RPC、proot/proroot 和 Agent UI 重构后的代码。历史原生 HTTP/工具循环审计不再作为当前实现说明。

## 结论

当前 Agent 链路已经切换为 Android 管理的真实 `pi --mode rpc` 进程：

- `PiRpcTransport` 负责 JSONL 请求关联、独立 stderr drain、超时、EOF/坏 JSON 失败闭合和进程终止。
- `PiAgentManager` 负责 session generation、消息/工具事件、处理阶段、队列、compact、stats、工作区和状态持久化。
- `AgentScreen` 负责窄屏/宽屏布局、Session/Tools/Workspace/Configuration/Search 面板、流式滚动策略、草稿和错误恢复。
- arm64 设备在 payload SHA-256 和能力 probe 均通过时优先使用 proroot；其他 ABI、损坏 payload 或 probe 失败时回退到 proot。

## 关键不变量

### PI 通讯

1. 每个 pending 请求都有唯一 ID，响应的 `command` 必须与请求一致。
2. 超时、取消、stdout EOF、坏 JSON、进程退出和写入失败都会结束所有 pending 请求，不允许永久 loading。
3. session 切换递增 generation；旧进程的事件不能写入新会话。
4. `sendPrompt()` 等待 `prompt` RPC ACK；返回 `false` 或被取消时输入草稿和附件保留，只有返回 `true` 且会话仍未切换时 UI 才清空本次提交快照。
5. 合法的长工具调用不受任意整轮超时打断；连接、状态探测、普通命令和 compact 使用独立 deadline。

### 数据与凭据

- Session 索引采用临时文件 + rename 原子提交；读取/写入失败显式反馈。
- 旧 IDAdroid 历史迁移到 PI v3 tree 前先完整解析，失败时不创建部分输出且保留原文件。
- 用户配置和 PI runtime 分离；`auth.json` 等敏感文件不复制内容，诊断通过 `PiConfigManager.redact()`。
- 附件读取限制为 50 MiB，草稿附件字节只保存在应用私有目录，不进入 saved-state Bundle。

### Rootfs 运行时

- 每次 rootfs 导入、环境 revalidate 和运行时启动前修复 `etc/resolv.conf`；绝对或 dangling symlink 先用 `lstat` 删除。
- resolver 内容为 `8.8.8.8`、`8.8.4.4`，并启用 `use-vc timeout:2 attempts:2`。
- proroot 使用 APK `nativeLibraryDir` 中未修改的五个 arm64 `.so`，哈希不匹配或 probe 超时即回退；proot 路径保留 `--kill-on-exit`。
- proroot 上游目前没有 proot 等价的 descendant kill-on-exit 保证，诊断明确披露该限制。

## 验证覆盖

已覆盖的自动化检查：

- `./gradlew :app:compileDebugKotlin`
- `./gradlew :app:testDebugUnitTest`
- `python3 -u /tmp/idadroid-live/raw_pi_smoke.py`：真实 PI 0.73.1 JSONL handshake、流式消息和持久历史。
- 五个 proroot payload 与 `proroot-NOTICE.txt` 中的 SHA-256 一致。

永久测试位于 `app/src/test/java/dev/idadroid/agent/`：transport 覆盖乱序响应、坏 JSON、超时、EOF 和进程清理；migration 覆盖图片、工具结果、父链和失败回滚。

## 剩余风险

1. 当前可用设备为 x86_64 emulator，只能验证 proot 回退和 APK/UI 集成；arm64 proroot 真机执行仍需 arm64 设备。
2. proroot payload 的上游许可证限制修改/再分发，发布包必须保留原始二进制和 provenance notice。
3. Agent 仍依赖 rootfs 内安装的 PI、Node.js、Provider 配置和 API 凭据；rootfs 未 ready 或模型未配置时 UI 必须停留在可操作配置状态。

## 维护规则

- 新 RPC 命令必须提供响应错误、timeout、取消和状态收敛路径。
- UI 处理中字段必须同时覆盖成功、用户停止、进程失败、session 切换和 Activity 重建。
- 不在日志、通知或诊断中输出 prompt、附件内容、完整 stderr 或 API key。
- 修改 rootfs 启动参数时同时验证 DNS、绑定路径、工作目录和进程清理。
