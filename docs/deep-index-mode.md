# 深度索引模式 — 设计文档

## 背景与动机

IDAdroid 的 Agent 运行在 proot 容器内，依赖 `mcpc` 调用 ida-mcp 进行逆向分析。但在面对大型代码库或复杂挑战时，Agent 缺乏结构化的代码理解能力——它只能用 `grep`/`find` 逐文件搜索，既消耗上下文预算，又难以建立调用关系、依赖图等全局视图。

深度索引模式的目标是补齐这一短板。它将三个开源项目的核心能力整合成一条统一工具链，Agent 一键开启后即可获得符号图、架构图、安全审计和持久记忆四类结构化分析能力。

## 整合的三个开源项目

选择这三个项目是因为它们各自覆盖了代码理解的一个正交维度，组合后形成完整的分析闭环。

CodeGraph（codegraph-ai/codegraph）是一个跨语言的代码语义图工具，通过 tree-sitter 解析 37 种语言，构建函数、类、导入、调用链的关系图，并通过 MCP 暴露 45 个工具。它的核心价值是结构化查询——Agent 可以问"谁调用了这个函数"或"这个改动会影响哪些文件"，而不是盲目 grep。原项目是 Rust 二进制，体积大且依赖 ONNX 模型做语义搜索。在 Android proot 容器里直接运行不现实，因此我用 ctags + ripgrep/grep 重新实现了它的结构化查询层，保留了符号搜索、调用者/被调用者、依赖图、影响分析等核心工具，舍弃了需要嵌入模型的语义搜索。

ECC（affaan-m/ECC）是一个工程能力框架，包含 AgentShield 安全扫描、Codemaps 架构图生成、Codebase Onboarding 上手分析等命令。它的设计理念是"token-lean"——每个输出都精简到 1000 token 以内，专为 AI 上下文消费优化。我提取了三个最有价值的命令：`codemap`（生成 architecture/backend/frontend/data/dependencies 五张架构图）、`security`（扫描硬编码密钥、过宽权限、可执行 hook 等安全面）、`onboard`（四阶段代码库上手：侦察→架构映射→约定检测→上手文档）。

codebase-memory-mcp 是一个代码库语义记忆 MCP 服务器，让 Agent 能跨会话持久化代码洞察。原项目依赖向量数据库做语义检索。我用 JSON 文件 + 关键词评分实现了等价的记忆层：`memory-store` 存储洞察，`memory-search` 按关键词检索，`memory-context` 返回与某文件相关的记忆。虽然检索精度不如向量搜索，但零依赖、即开即用，符合移动端约束。

## 架构设计

整个工具链分为三层：Android 侧的状态管理、容器侧的脚本实现、Agent 侧的系统提示词增强。

Android 侧的 `DeepIndexToolChain` 负责管理开关状态。它暴露一个 `StateFlow<DeepIndexStatus>` 供 UI 观察，并提供 `setEnabled`/`isEnabled` 方法供 `PiAgentManager` 调用。开关状态不持久化——每次启动 App 默认关闭，用户需要时手动开启。这个设计是有意的：深度索引会改变 Agent 的行为模式（优先用结构化工具而非 grep），不应该默认强制开启。

容器侧的 `deep-index.sh` 是工具链的核心。它由 `DeepIndexScriptBuilder` 在 Kotlin 中生成（用 `$DOLLAR` 转义避免字符串模板冲突），在 `PiWorkspaceMaterializer.materialize` 时写入容器的 `.idadroid/scripts/` 目录并 chmod 为可执行。脚本完全自包含，只依赖标准 Unix 工具（grep、sed、awk、find、ctags 如果可用、ripgrep 如果可用），在 proot 容器内直接运行。

Agent 侧的增强通过 `APPEND_SYSTEM.md` 实现。`PiWorkspaceMaterializer` 在生成系统提示词时加入了深度索引模式的说明文档，告诉 Agent 当模式开启时应该优先使用 `deep-index` 命令而非原始 grep/find。`DeepIndexToolChain.systemPromptFragment()` 还提供了一个额外的提示词片段，可在运行时动态注入。

## 工具链命令

`deep-index.sh` 提供以下子命令，按来源项目分组。

CodeGraph 风格的结构化查询：`index` 构建 TSV 格式的符号索引（符号名、类型、文件、行号）；`symbols` 按名称搜索符号；`callers`/`callees` 查询调用关系；`deps` 分析文件间依赖；`impact` 分析某符号的变更影响范围；`entry-points` 找出程序入口。

ECC 风格的工程能力：`codemap` 生成 token-lean 的架构图（architecture/backend/frontend/data/dependencies 五张）；`security` 执行 AgentShield 风格的安全审计（扫描硬编码密钥、过宽文件权限、可执行 hook、可疑 MCP 配置）；`onboard` 执行四阶段代码库上手分析。

codebase-memory-mcp 风格的持久记忆：`memory-store` 存储 key-value 形式的代码洞察；`memory-search` 按关键词检索存储的记忆；`memory-list` 列出所有记忆；`memory-context` 返回与指定文件相关的记忆。

索引管理：`status` 显示索引新鲜度和覆盖率。

## UI 集成

`AgentComposer` 的“更多”菜单提供深度索引开关。关闭时提示点击开启 CodeGraph / ECC / codebase-memory 联合工具链；开启时显示已启用状态。切换调用 `PiAgentManager.enableDeepIndexMode()` / `disableDeepIndexMode()`，并在 Agent 状态栏反馈当前模式。

## 设计取舍

有几个决策值得说明。

第一，为什么用 shell 脚本而不是原生 Kotlin 实现？因为这些工具需要分析容器内的代码文件，而文件系统访问在 proot 容器内更直接。如果用 Kotlin 实现，每次查询都要跨 proot 边界读取文件，性能和复杂度都不划算。shell 脚本在容器内原生运行，零跨边界开销。

第二，为什么不用 CodeGraph 的原版 Rust 二进制？主要是体积和依赖。CodeGraph 的完整二进制加 ONNX 模型超过 100MB，且需要 glibc 版本匹配。在 Android 的 proot 容器里部署这样的二进制不现实。ctags + ripgrep 的组合在大多数 Linux 发行版里都有预装，体积小、启动快，虽然功能不如 CodeGraph 完整，但覆盖了 80% 的结构化查询需求。

第三，为什么记忆层用 JSON 而不是 SQLite 或向量数据库？移动端约束下，依赖越少越好。JSON 文件可读、可手动编辑、零依赖。当记忆条目超过几百条时检索性能会下降，但在这个场景下（单次 CTF 挑战的代码库）完全够用。如果未来需要扩展，可以平滑迁移到 SQLite。

## 已知局限

这个实现有几个诚实的局限。结构化查询的精度依赖 ctags 的语言支持——对于冷门语言或二进制文件，符号提取会失败，此时脚本会回退到 grep。安全扫描是启发式的，会产生误报和漏报，不能替代专业工具。记忆检索是关键词匹配，不是语义搜索，无法理解同义词或概念关联。这些局限都在系统提示词中向 Agent 坦诚说明，避免它过度信任工具输出。
