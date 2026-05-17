# 03 - rootfs 导入与 proot 运行计划

## 目标

把用户选择的 rootfs archive 安全解包到 App 私有目录，验证其具备 IDA/pi/VNC/ida-mcp 能力，并通过 proot 提供统一运行入口。

## 目录布局

建议使用可扩展的 env 目录，而不是直接把 rootfs 放在 `filesDir/proot/ubuntu`：

```text
filesDir/
  proot/
    bin/proot
  envs/
    default/
      rootfs/
      tmp/
      metadata.json
      import.log
      validate.log
      .rootfs-extracted
      .setup-complete
```

metadata 示例：

```json
{
  "schema": 1,
  "envId": "default",
  "sourceName": "ida-rootfs.tar.xz",
  "importedAt": "2026-05-14T00:00:00Z",
  "rootfsStripComponents": 0,
  "rootfsFormat": "tar.xz",
  "validation": {
    "ok": true,
    "idaHome": "/root/ida-pro-9.3",
    "piPath": "/usr/local/bin/pi",
    "vncMode": "xvfb-x11vnc"
  }
}
```

## 导入流程

```text
SelectRootfsUri
  -> PreflightStorageCheck
  -> PrepareStagingDir
  -> ExtractArchiveToStaging
  -> ConfigureRootfs
  -> InstallProotBinary
  -> ValidateInsideProot
  -> MaterializePiWorkspace
  -> AtomicActivate
  -> Ready
```

### 1. 选择 rootfs

使用 `ACTION_OPEN_DOCUMENT`：

- MIME 可放宽为 `*/*`。
- 文件名用于判断格式。
- 通过 `OpenableColumns.SIZE` 获取总大小（可能为空）。
- 支持：`.tar`、`.tar.gz`、`.tgz`、`.tar.xz`、`.txz`。

MVP 不建议先完整复制到 cache 再解包，因为 IDA rootfs 可能很大。优先从 SAF input stream **流式解包到 staging**。若后续要校验 sha256 或断点重试，再增加“复制到 cache archive”的模式。

### 2. 空间预检查

估算：

- archive size 可知时，提示至少保留 `archiveSize * 2.5` 的可用空间。
- archive size 不可知时，提示用户确认。

Android 可用空间：`filesDir.usableSpace`。

### 3. staging 解包

路径：

```text
filesDir/envs/.importing-default-<timestamp>/rootfs
```

解包完成并验证成功后，替换：

```text
filesDir/envs/default/rootfs
```

如果失败，只删除 staging，不破坏已有环境。

### 4. tar 安全策略

参考 r2droid `ProotInstaller.extractRootfsArchive()`：

- `entry.name.trim('/').removePrefix("./")`。
- 可配置 strip components：默认 0；若 archive 顶层是单一目录，可自动检测或让用户选择。
- `File(rootfsDir, normalized).canonicalPath` 必须以 `rootfsDir.canonicalPath` 开头。
- 拒绝绝对路径穿越、`../` 穿越。
- symlink：保留 link target，但 link 文件路径仍必须在 rootfs 内。
- hardlink：Android 上可按 r2droid 做成 symlink，降低失败概率。
- 普通文件：流式写入。
- chmod：保留低 9 位权限，并强制 owner 可读写；目录强制 owner 可执行。

### 5. rootfs 配置

解包后写入/修复：

```text
/etc/resolv.conf
/etc/hosts
/etc/apt/apt.conf.d/99proot-nosandbox
/etc/dpkg/dpkg.cfg.d/force-unsafe-io
/tmp
/var/tmp
/proc/sys/crypto/fips_enabled
```

其中 `tmp` 权限设为 `0777`。

### 6. proot 二进制

将 `proot` 放在 App asset 或 native lib 资源中，首次运行复制到：

```text
filesDir/proot/bin/proot
```

并 `chmod 0755`。

注意事项：

- 需确认 proot 二进制架构匹配设备 ABI。
- 若使用 asset 复制，Android 某些版本对可执行文件位置有限制，应在真机矩阵验证。
- proot 启动统一加 `--kill-on-exit`。

## proot command builder

基础命令：

```text
<proot> \
  -L \
  --link2symlink \
  --kill-on-exit \
  --root-id \
  -r <rootfs> \
  -b /dev \
  -b /proc \
  -b /sys \
  -b <hostTmp>:/tmp \
  -b <hostTmp>:/var/tmp \
  -b /storage \
  -b /sdcard \
  -w <cwd> \
  /usr/bin/env -i \
  HOME=/root \
  USER=root \
  LOGNAME=root \
  LANG=C.UTF-8 \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  TERM=<term> \
  TMPDIR=/tmp \
  <shell> -l -c <script>
```

`/dev/random` 建议绑定 `/dev/urandom`，参考 r2droid。

`XDG_RUNTIME_DIR` 建议设为 `/tmp/runtime-root` 并在启动脚本里创建 `0700`。

## 验证脚本

导入后运行：

```bash
set -eu
HOME=/root
result_file=/tmp/idadroid-validate.json

check_cmd() { command -v "$1" 2>/dev/null || true; }
json_escape() { python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'; }

# 实际实现建议用 Kotlin 拼出 shell + Python JSON，避免手写 JSON 转义。
```

验证项：

| 项 | 必须 | 检查 |
| --- | --- | --- |
| HOME | 是 | `/root` 存在可写 |
| IDA dir | 是 | `/root/ida-pro-9.3` 是目录 |
| IDA binary | 建议 | `ida`、`ida64`、`idat`、`idat64` 候选至少一个存在 |
| ida-mcp | 是 | `/root/ida-pro-9.3/ida-mcp` 存在且可执行 |
| usage doc | 是 | `/root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md` 存在 |
| pi | 是 | `command -v pi`，`pi --version` |
| node | 是 | `command -v node`，版本建议 >=22 |
| mcpc | 建议 | `command -v mcpc` |
| VNC | GUI 必需 | `Xtigervnc/vncserver` 或 `Xvfb+x11vnc` |
| WM | GUI 建议 | `openbox`/`fluxbox`/`xfce4-session` |
| arch | 是 | `uname -m`、`file <ida binary>`，给出 mismatch warning |
| workspace | 是 | 可创建 `/root/pi_workspace` |

验证失败分级：

- **fatal**：rootfs 不可进入、IDA dir 缺失、pi 缺失。
- **gui-blocking**：VNC/X/WM 缺失，终端/agent 仍可用。
- **agent-warning**：mcpc 缺失或 usage doc 缺失，agent 可用但 IDA MCP 辅助不完整。

## Pi workspace materialize

验证通过后写入：

```text
/root/pi_workspace/.upload/
/root/pi_workspace/.pi-sessions/
/root/pi_workspace/.pi/SYSTEM.md
/root/pi_workspace/.pi/APPEND_SYSTEM.md
/root/pi_workspace/.idadroid/pi-agent/settings.json
/root/pi_workspace/.idadroid/pi-agent/models.json      # 如果用户配置了
/root/pi_workspace/.idadroid/pi-agent/AGENTS.md
/root/pi_workspace/.idadroid/pi-agent/rpc-stdio-guard.cjs
/root/pi_workspace/.idadroid/scripts/start-ida-vnc.sh
/root/pi_workspace/.idadroid/scripts/validate.sh
```

默认 `settings.json`：

```json
{
  "quietStartup": true,
  "enableInstallTelemetry": false,
  "sessionDir": "/root/pi_workspace/.pi-sessions",
  "compaction": { "enabled": true, "reserveTokens": 16384, "keepRecentTokens": 20000 },
  "retry": { "enabled": true, "maxRetries": 3, "baseDelayMs": 2000, "maxDelayMs": 60000 },
  "steeringMode": "one-at-a-time",
  "followUpMode": "one-at-a-time"
}
```

默认 agent 指令应说明：

- 工作目录是 `/root/pi_workspace`。
- IDA 在 `/root/ida-pro-9.3`。
- ida-mcp 在 `/root/ida-pro-9.3/ida-mcp`。
- 使用说明在 `/root/ida-pro-9.3/IDA_MCP_MCPC_USAGE.md`。
- 需要逆向时先读说明，再使用 `mcpc` 调用 IDA MCP。
- 附件在 `/root/pi_workspace/.upload`。

## 重新导入/升级策略

MVP：

- Settings → Reimport rootfs。
- 如果已有环境，先停止 VNC/agent/terminal。
- 新 rootfs 在 staging 验证成功后替换旧 rootfs。
- `~/pi_workspace` 是否保留由用户选择：
  - 默认保留（复制旧 `root/pi_workspace` 到新 rootfs）。
  - 可选择清空。

注意：如果 pi workspace 位于 rootfs 内，替换 rootfs 会影响 session 历史。后续可考虑把 workspace 放在 host 侧并 bind 到 `/root/pi_workspace`，这样重装 rootfs 不丢 session。但 MVP 简化为 rootfs 内 workspace。

## 日志与诊断

每次导入记录：

```text
filesDir/envs/default/import.log
filesDir/envs/default/validate.log
filesDir/envs/default/logs/proot-run.log
```

UI 提供：

- 复制诊断信息。
- 导出诊断包（不包含用户 IDA 文件和 API keys）。
- 重新运行验证。
