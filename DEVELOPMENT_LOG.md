# 从零构建 Java AI Coding Agent —— Loom Agent 开发日志

## 一、本次开发概述

本次开发的目标是从零构建一个生产级的后端 AI Coding Agent。项目基于 Java 21 + Spring Boot 3.4.3 + DeepSeek API，采用 DDD 六边形架构，实现了完整的自主编程助手能力。

最终完成的核心内容包括：

- **Agent 执行引擎**：16 节点的 ReAct 循环，从 Skill 加载到最终回答的完整流程控制
- **17 个内置工具**：涵盖文件读写、代码搜索、Shell 沙箱、Git 操作、结构化 Diff、后台任务等
- **MCP 协议支持**：可扩展 Playwright 浏览器自动化、Exa 网络搜索等外部工具
- **层级子 Agent 系统**：主 Agent 可派生 explorer/reviewer/editor 子 Agent，支持并发与串行
- **安全审批体系**：四级权限模型 + SHA-256 指纹防篡改 + 审批过期机制
- **ConversationLedger 系统**：自建的提示词前缀稳定检测与增量复用引擎，解决 DeepSeek Prompt Cache 命中率不稳定问题
- **上下文管理**：三级恢复链（reactive compact → 大窗口模型 → 深层摘要 → 用户介入）
- **模型韧性**：Resilience4j 重试 + 熔断 + 回退模型切换
- **长期记忆**：基于 SQLite-vec 的向量搜索记忆系统，含自动提取、embedding、归档全链路
- **撤销机制**：Git ghost snapshot，支持一键回滚
- **全链路 Trace 与回放**：每一步可追溯、可回放
- **Skills 系统**：三级渐进式加载，Agent 可自行创建项目级 Skill

项目从初始化到功能完整交付历时 21 天，共 154 次提交，最终代码量约 7 万行（649 个文件），覆盖 6 个 Maven 模块。

---

## 二、开发背景与问题

### 为什么做这个项目

AI Coding Agent 是当时热门但门槛较高的方向。市面上的方案大多基于 Python/Node.js 生态，Java 生态中缺少一个生产级的、面向自主编程任务的后端 Agent 实现。选择从零构建的主要动机是深入理解 Agent 架构的每一层决策，而不仅仅是调用现有的 Agent 框架。

### 要解决的核心问题

1. **Agent 如何稳定执行多步任务**：单次模型调用无法完成复杂编程任务，需要设计可靠的 ReAct 循环机制
2. **如何在安全前提下赋予 Agent 写能力**：Agent 需要修改代码，但必须限制在沙箱内，高危操作需要人工确认
3. **如何处理越来越大的上下文**：多轮对话会导致上下文不断膨胀，超出模型窗口限制
4. **如何让 Agent 记住之前的工作**：跨会话的知识积累和检索
5. **如何让模型调用的提示词缓存真正生效**：DeepSeek 的 Prompt Cache 命中率不稳定，需要自建检测和复用机制

---

## 三、主要开发内容

### 3.1 Agent 执行引擎：从最小闭环到 16 节点

**最初版本**（6/20）只有最基本的 6 节点 ReAct 循环：Start → RenderPrompt → ModelCall → Decision → ToolDispatch → Observation。Agent 读取用户问题，调用 DeepSeek 生成 action JSON，执行工具，将观察结果反馈给模型，直到输出 final 答案。

**演进到 16 节点**的过程是逐步添加控制点：

- 添加了 `PlannerNode`，让 Agent 先制定计划再执行（6/22）
- 添加了 `ReplanGuardNode` + `ReplanNode`，在观察结果与预期不符时自动重规划（6/22）
- 添加了 `ApprovalGateNode`，在工具执行前评估权限等级（6/21）
- 添加了 `InstructionGateNode`，对模型输出进行安全校验（7/1）
- 添加了 `SkillBootstrapNode`，在任务开始前加载相关 Skill（6/30）
- 添加了 `MemoryRecallNode`，在提示词构建时注入相关长期记忆（7/9）
- 添加了 `UserInputGateNode`，在上下文溢出时等待用户输入后继续（6/25）

每个节点都是独立的类，通过 `AgentNode` 接口统一：声明 `name` 和 `inputKeys`，通过 `doApply` 方法操作 `AgentContext` 并返回下一个节点名称。`DefaultAgentLoopService` 只负责驱动流转和发送 SSE 事件，不承载业务逻辑。

### 3.2 工具系统与安全模型

工具系统从最初的 3 个只读工具，扩展到了 17 个内置工具，每个工具都有明确的安全边界。

**权限分级**经历了两次迭代。第一版使用简单的 permission level，第二版（6/29）重构为贴近 Claude Code 的双层安全决策模型。最终的四级权限为：

| 级别 | 行为 |
|------|------|
| `READ_ONLY` | 自动放行 |
| `WRITE_CONFIRM` | 暂停等待审批（含 SHA-256 内容指纹） |
| `HIGH_RISK_CONFIRM` | 高危审批，需明确确认 |
| `HIGH_RISK_DENY` | 直接拦截 |

其中审批指纹防篡改是一个重要的安全细节：审批时生成 SHA-256 指纹，执行前重新校验，不一致时返回 `approval_stale`，防止审批通过后被前端或其他进程修改了文件内容。

**Shell 沙箱**（6/21 开始，6/29 重构）不使用系统 shell，而是将命令拆成 `ProcessBuilder` 参数。命令按注册表分桶归类（readOnly/write/highRisk/deny），禁止管道、重定向和后台执行。后来新增了 `shell_task` 工具（7/1），支持长时间后台任务，有独立的 yield 超时、状态监控和日志读取接口。

**Prompt Injection 防护**（6/29）采用三层隔离：工具输出被标记为不可信 Observation，只用于代码证据而非执行指令；输出中包含 `TOOL_OUTPUT_SAFETY_NOTE` 标签提醒模型不要把工具输出当作指令；敏感文件（.env、私钥等）禁止读取和搜索。

### 3.3 ConversationLedger 系统（C1-C10 + D2）

这是整个项目中投入最大的功能模块，从 7/3 的 C1 到 D2 生产开关启用，共经历了 12 个阶段的系统性构建。

**要解决的问题**：DeepSeek 的 Prompt Caching 行为不够稳定。在多次模型调用中，如果提示词的前缀部分（系统提示、工具定义等）发生变化，缓存就会失效。需要一套机制来保证前缀稳定、检测变化、管理缓存的生成周期。

**核心设计**：

- **StablePrefix**（C1-C3）：将系统提示词中不会变化的部分（工具定义、Skill 描述、角色指令等）构建为稳定前缀，通过 MD5 指纹检测变化。`StablePrefix.fingerprint()` 是判断是否需要切换 generation 的唯一依据。

- **ConversationLedger**（C4-C6）：记录每一轮对话的完整消息（assistant 输出、tool result、系统事件等），采用 append-only 模式，支持事件键幂等。系统事件包括计划快照、预算快照、TODO 提醒、解析错误、重规划和用户输入。

- **Shadow 模式与生产启用**（C7-C9R）：先在 shadow 模式下运行，生成 `CanonicalSnapshot` 结构化快照，验证消息级前缀的 append-only 检测、LCP/messageLcp 区分、语义覆盖（Skills/Tools 指向 StablePrefix）等，确认与真实请求路径兼容后再启用生产模式。

- **压缩与 watermark**（C10）：引入 high/low watermark 机制，当 ledger 消息量达到 high watermark 时触发压缩，压缩到 low watermark 以下。使用 generation 状态机管理压缩周期，压缩时冻结基线。

- **D2 开关**：最终启用生产 ledger 开关，同时保留回滚路径。

整个过程最关键的体会是：**在第三方 API 的不可控行为上自建可控层，投入很大但收益明确**。DeepSeek 的缓存行为随着模型版本更新而变化，而 StablePrefix 的指纹机制给了我们一个独立于提供商的稳定检测手段。

### 3.4 上下文管理：三级恢复链

上下文膨胀是 Agent 开发中的核心难题。每次工具调用的输出都会追加到消息历史中，很快就会超出模型的上下文窗口。

**Reactive Compact**（6/23）：压缩历史消息，保留最近的 N 条关键条目，丢弃中间的工具输出细节。

**大窗口模型切换**（6/25）：当 reactive compact 不够时，自动切换到支持更大上下文的模型变体。

**深层次摘要**（6/25）：对历史进行分段摘要，逐段调用模型生成压缩版本，最终汇总。

**用户介入**（6/25）：三级策略全部耗尽后，根 Agent 进入 `WAITING_USER_INPUT` 状态，等待用户确认范围后继续。

恢复链的顺序和阶段写入 checkpoint，保证重新连接时不会重复执行已完成的阶段。子 Agent 不直接询问用户，恢复耗尽后返回 `CONTEXT_OVERFLOW` 给主 Agent。

### 3.5 长期记忆系统

长期记忆是从 7/8 开始实现的新模块，目标是让 Agent 能够跨会话积累和检索知识。

**架构设计**基于三个异步 Worker：

1. **MemoryExtractionWorker**：从完成的 Agent 运行中自动提取值得保存的知识片段。这是判断"什么值得记住"的核心逻辑。

2. **MemoryEmbeddingWorker**：将提取的文本片段通过 Embedding API（DashScope/OpenAI 兼容）转换为向量，存入 SQLite-vec 向量索引。

3. **MemoryArchiveWorker**：定期清理过期的 embedding job，维护索引健康。

**向量检索**使用 SQLite-vec 扩展，通过 `VecExtensionLoadingDataSource` 在 SQLite 连接时加载向量扩展。检索时通过 `MemorySearchService` 将用户查询转为向量后进行相似度搜索，再由 `MemorySelectionService` 对搜索结果进行相关性过滤和排序。

**Agent 集成**通过 `MemoryRecallNode` 在每次 RenderPrompt 时自动检索相关记忆，注入到提示词中。同时提供 `memory_save` 和 `memory_search` 两个工具，Agent 也可以主动调用。

这个模块经历了多次迭代（7/8 初始实现 → 7/9 调用功能实现 → 7/11 部分修复 → 两次架构优化），主要挑战在于向量检索的效果不理想。最初 MemoryEmbeddingWorker 承担了过多职责（324 行），后续重构将其拆分为更专注的组件，并优化了检索策略。最终将 embedding worker 缩减到约 188 行，抽取出独立的 MemoryExtractionWorker。

### 3.6 子 Agent 系统

子 Agent 系统让主 Agent 可以将复杂任务拆解为多个独立子任务并行执行。内置三种角色：

- **explorer**：只读代码探索，支持并发
- **reviewer**：代码审查，强制只读
- **editor**：文件编辑，默认单实例串行

子 Agent 拥有独立的 AgentContext、独立 runId、独立历史记录，只继承 workspace 和安全配置。通过 RoleToolRegistryFactory 为不同角色构造受限的工具注册表（explorer/reviewer 只允许 READ_ONLY）。子 Agent 的中间日志不会污染主 Agent 上下文，主 Agent 只收到 `sub_agent_summary` JSON。

并发控制通过 `SubAgentCoordinator` 管理，支持最大并发数、最大子 Agent 数和最大深度的配置。

### 3.7 Skills 系统

Skills 是 Agent 的行为扩展机制。采用三级渐进式加载体系（6/30）：

1. **系统级**：内置在 codebase 中的 Skill
2. **项目级**：工作区 `.skills/` 目录下的 Skill
3. **Agent 自创建**：通过 `create_skill` 工具在运行时创建

Skill 加载由 `SkillBootstrapNode` 在任务开始时执行，加载后的 Skill 描述和指令会被固化到 AgentContext 中，并通过快照保证同一次运行中 Skill 版本不变。

### 3.8 重构：从快速迭代到架构清理

项目中期（6/25-7/2）进行了密集的大规模重构，这是一个关键的转折点——从"快速堆功能"转向"建立可持续的架构"。

**主要重构项**：

1. **AgentContext 拆分**（7/2）：最初是一个 76 字段的上帝对象，重构为聚合根，组合 11 个专门的子状态对象（AgentRuntimeState、AgentBudgetState、AgentPromptState、AgentActionState 等）。每个节点通过 `NodeAccess` 只访问自己需要的状态，大幅降低耦合。

2. **ModelCallNode 拆分**（7/2）：将原来单个庞大的节点拆为 10 个协作类——ContextSimplifyStep、DeepSummaryStep、FormatReminderStep、ReactiveCompactStep、ModelCallBudgetCoordinator 等，每个类只负责一个明确的上下文处理步骤。

3. **AgentCodeController 拆分**（7/1）：从单一 Controller 拆为 6 个 Controller + 5 个 HTTP Service，按职责分离（执行、运行管理、审批、工作区、后台任务、记忆与技能）。

4. **SSE 生命周期统一**（7/2）：抽取共享的 SSE 响应内核，统一 Chat 和 Agent 的 SSE 处理逻辑。

5. **领域服务拆分**（7/1-7/2）：将 `domain/agent/service` 按职责拆为 9 个子包（budget、context、conversation、execution、ledger、memory、prompt、skill、undo）。

6. **MySQL→SQLite 迁移**（6/30）：彻底移除 MySQL 和 Redis 依赖，切换到 SQLite。这个决策综合了三个因素：简化部署（无需额外的数据库服务）、产品定位为单用户本地工具（SQLite 的并发限制不是问题）、sqlite-vec 提供了比 MySQL 更好的向量搜索能力。

7. **配置拆分**（6/30）：将 800+ 行的 `AiRuntimeConfig` 拆分为 `AgentLoopAutoConfig`、`ToolAutoConfig`、`MemoryAutoConfig`、`PersistenceAutoConfig`、`MetricsAutoConfig` 等专门的配置类。

所有这些重构都用 ArchUnit 防回归规则（`ArchitectureRegressionTest`）锁定了架构约束，防止后续开发破坏分层依赖。

### 3.9 Bug 修复：实践中暴露的问题

7/7 是 Bug 修复最密集的一天，这些 Bug 都是在实际使用中暴露的，而非测试发现：

- **Replan/Todo 循环耗步**：Agent 在重规划时反复创建相似的 TODO，每次创建都消耗步数预算，导致很快耗尽。修复方案是在重规划时复用已有的 TODO 结构，仅更新差异部分。

- **TODO 近似重复创建**：Agent 在连续步骤中创建内容高度相似的 TODO，通过添加相似度检测来合并近似重复项。

- **Undo Busy 与删除复活竞态**：撤销操作进行中时，会话删除被触发，导致资源清理状态冲突。引入了 SUSPENDED 暂停态作为中间状态来解决。

- **Plan 未同步截停最终回答**：Agent 在最终回答前没有检查 Plan 是否已同步更新，导致回答中包含过时的计划信息。修复是在 FinalAnswerNode 前强制同步 Plan 状态。

### 3.10 提示缓存与 Token 优化（7/2-7/4）

在 ConversationLedger 架构之上，进一步构建了提示缓存的可观测性和优化：

- 按调用类型观察 hit/miss token，避免 Micrometer 标签基数失控（7/2）
- 实现与网络发送解耦的 payload 摘要、脱敏和前缀差异算法（7/2）
- PromptCacheDiagnostics 接入 DeepSeek 最终 payload 构造路径（7/2）
- 新增 Token 总量和缓存命中率 API（7/4）
- 统一大型工具结果持久化引用协议，用 `persisted-output` XML 标签替代旧的 `context_artifact` 和 `compacted_tool_result`（7/4）

### 3.11 多种 AI 提供商支持（7/10）

最初项目只对接 DeepSeek API，使用自定义的 `ResilientModelGateway` 直接处理 `/chat/completions` SSE 流。7/10 新增了 OpenCode Go 作为第二个提供商，动机是**成本考虑**。

为了实现这个能力，引入了 `SpringAiModelGateway` 作为基于 Spring AI 框架的统一网关层，同时保留了原有的自定义网关。通过 `ModelProviderConfig` 和 `LOOM_AI_PROVIDER` 环境变量在两种提供商之间切换。

---

## 四、关键技术实现

### 4.1 Agent 循环的状态管理

Agent 执行过程中的所有状态都集中在一个 `AgentContext` 中传递。重构后，`AgentContext` 作为聚合根组合 11 个子状态对象：

- `AgentIdentity`：runId、conversationId、userId 等身份信息
- `AgentRunDefinition`：问题、workspace、步预算等定义
- `AgentRuntimeState`：当前节点、已用步数、segment 计数
- `AgentBudgetState`：Token 预算、消耗追踪
- `AgentPromptState`：当前提示词、StablePrefix、Ledger
- `AgentActionState`：当前决策、工具调用、工具结果
- `AgentApprovalState`：待审批操作
- `AgentEnvironmentState`：工具注册表、模型网关
- `AgentSkillState`：已加载的 Skill
- `AgentTraceState`：Trace 事件列表
- `AgentRecoveryState`：上下文恢复状态

每个节点通过 `NodeAccess` 声明自己需要的 key，只访问对应的子状态，避免了所有节点都能看到所有字段的耦合问题。

### 4.2 StablePrefix 指纹机制

`StablePrefixBuilder` 负责将系统提示词中不会随对话变化的部分（系统角色描述、工具定义、Skill 指令等）序列化为一个稳定字符串，然后通过 MD5 计算指纹。

当指纹变化时（比如工具集合变了、Skill 加载不同了），ConversationLedger 切换到新的 generation，旧 generation 的消息被冻结为基线，新消息从新的 StablePrefix 开始追加。

```text
StablePrefix = serialize([
    system_role,
    tool_schemas(order_by_name),
    skill_descriptions,
    format_instructions,
    ...
])

generation = MD5(StablePrefix)
```

### 4.3 上下文双轨系统

7/7 的上下文压缩重构将 `DynamicText` 和 `ConversationLedger` 合并为统一的双轨系统：

- **Ledger 轨**：结构化的消息序列，用于缓存前缀检测
- **DynamicText 轨**：面向模型输入的渲染文本，包含了上下文压缩后的实际内容

两个轨道在 `RenderPromptNode` 中协同工作：Ledger 保证前缀稳定性，DynamicText 承载压缩后的上下文。这种设计确保了缓存策略的正确性不受上下文压缩的影响。

### 4.4 分步预算与无进展保护

Agent 执行分为多个 segment，每个 segment 有 `maxSteps` 步预算。单段步数耗尽时自动进入下一段（保存 checkpoint、重规划后继续）。全局通过 `maxTotalSteps`、`maxSegments`、`totalTimeoutMs` 三重保险丝保护。

`ProgressGuard` 在每次 Observation 后评估进展：
- 相同工具 + 相同输入连续重复 → `NO_PROGRESS/repeated_action`
- 相同工具 + 相同错误连续重复 → `NO_PROGRESS/repeated_failure`
- `todo_write` 成功、写工具成功、测试命令成功、计划版本变化 → 重置计数器

### 4.5 模型韧性策略

- **首 Token 超时保护**：在收到第一个 token 前设置较短的超时
- **智能重试**：仅首 Token 前重试；429/500/503 重试；400/401/402/422 不重试
- **用途驱动的错误恢复**：根据不同调用目的（planning、execution、summary）采用不同的失败策略
- **熔断与回退**：连续失败触发断路器，自动切换到备用模型
- **线程中断正确处理**：被中断后恢复中断状态，禁止无意义重试

---

## 五、技术决策与方案取舍

### 5.1 自建 ConversationLedger vs. 直接使用 Prompt Caching API

**选择**：自建 StablePrefix 指纹 + ConversationLedger + generation 状态机

**原因**：DeepSeek 的 Prompt Caching 命中率不稳定，缓存行为随着模型版本更新而变化。自建系统给了我们对缓存行为的完全控制——我们可以精确知道什么时候缓存会命中、什么时候会失效，并且可以在不同 AI 提供商之间保持一致的语义。

**代价**：C1-C10 的 12 阶段构建投入了大量时间，且增加了系统复杂度。

**风险**：如果 DeepSeek 后续大幅改进原生 Prompt Caching，自建系统的相对价值会降低。但目前的设计允许我们通过 D2 开关在生产中启用，也保留了回滚路径，风险可控。

### 5.2 MySQL→SQLite 迁移

**选择**：从 MySQL + Redis 迁移到纯 SQLite

**优势**：零依赖部署（不需要单独的数据库服务）、适合单用户场景、sqlite-vec 提供了原生向量搜索能力

**缺点**：无法水平扩展多实例、并发写入能力有限

**评估**：对于当前产品定位（单用户本地工具），这个权衡是正确的。如果未来需要支持多用户高并发，需要重新评估。

### 5.3 Java + Spring Boot vs. Python 生态

**选择**：Java + Spring Boot

**优势**：类型安全、成熟的 DDD 实践、强大的 DI/IoC、丰富的企业级组件（Micrometer、Resilience4j、MyBatis）

**缺点**：AI/LLM 生态不如 Python 丰富，需要手动处理很多 Python 生态中已有现成库的功能

这个选择主要基于个人技术栈偏好和架构控制欲——从头构建能深入理解每一层决策。

### 5.4 临时方案与技术债务

- **审批状态存储**：最初使用内存存储，服务重启后待审批操作失效。后续迁移到 SQLite 持久化。
- **DynamicText 与 ConversationLedger**：最初是两个独立系统，7/7 合并为统一的双轨系统，减少了概念重复。
- **长期记忆的 worker 架构**：从单一的大 worker 迭代为多 worker 协作模式，当前版本仍有优化空间（检索精度、embedding 批处理等）。

---

## 六、开发过程中遇到的问题

### 6.1 Prompt Cache 命中率不稳定

**问题**：在多轮 Agent 对话中，DeepSeek 的 Prompt Cache 经常意外失效，导致每次调用都重新计费整个提示词。

**定位**：通过 `PromptCacheDiagnostics` 对比连续调用的 payload，发现即使前缀内容没变，DeepSeek 有时也会判定为 cache miss。推测与 DeepSeek 内部的缓存策略有关——可能是时间衰减、哈希冲突或负载均衡导致请求路由到不同的缓存节点。

**解决**：自建 ConversationLedger 系统，不依赖 DeepSeek 的缓存行为，而是自己管理前缀稳定性和 generation 周期。在 shadow 模式下运行验证后启用。

### 6.2 向量检索效果不理想

**问题**：长期记忆的向量搜索结果相关性低，Agent 检索到的记忆经常与当前任务无关。

**定位**：检查了 embedding 质量和检索参数。初步定位为两个原因：(1) embedding 模型对代码相关文本的语义理解不够精准；(2) 检索时的相似度阈值和结果排序策略需要优化。

**解决**：多次迭代优化 `MemorySelectionService` 的检索策略，加入了额外相关性过滤逻辑；优化 `MemoryEmbeddingWorker` 的职责拆分，让 embedding 调用更专注。

### 6.3 Agent Loop 中的竞态和循环问题

这些问题都是在实际编码任务中暴露的：

- **Replan/Todo 循环**：Agent 在 plan→replan 循环中反复创建 TODO 消耗步数。根因是 TODO 的创建逻辑没有考虑"这是重规划，大部分 TODO 应该复用"的场景。
- **Undo/Delete 竞态**：并发场景下撤销和删除同时操作同一会话。根因是状态机缺少中间态（SUSPENDED）来协调并发操作。
- **Plan 不同步**：最终回答读取的是旧版本的 Plan。根因是 Plan 更新和 FinalAnswer 之间缺少同步点。

### 6.4 编译错误修复

有几个提交是纯粹的编译修复（如 `@ConfigurationProperties` 重复定义导致启动失败），这些通常发生在多模块重构后依赖关系没有及时同步的场景。解决方式是加强了 CI 的编译检查。

---

## 七、我的收获与反思

### 从代码和提交中可以确认的技术收获

1. **节点化流程引擎的设计模式**：将 Agent 循环拆为独立节点比集中式的大循环更容易扩展和测试。新增功能只需要新增节点，不需要修改核心循环逻辑。

2. **聚合根模式在 Agent 上下文中的应用**：将 76 字段的上帝对象拆为 11 个子状态对象，是 DDD 聚合根模式在 Agent 架构中的一次实践。关键在于定义好子对象的边界——不是按技术维度（数据库字段）拆分，而是按语义内聚性（预算、审批、提示词等）。

3. **StablePrefix 指纹作为缓存边界检测的通用方法**：不依赖具体 AI 提供商的缓存机制，而是自己定义"前缀是否变化"的标准。这种方法可以跨提供商复用。

4. **双轨上下文管理**：将结构化消息序列（用于缓存）和渲染后文本（用于模型输入）分离，让缓存策略与压缩策略解耦。

5. **ArchUnit 防回归**：大规模重构后必须用架构测试锁定分层依赖，否则后续迭代会很快退化。

6. **异步 Worker 模式的正确使用**：Memory 系统的三个 Worker（Extraction → Embedding → Archive）形成了清晰的流水线，每个 Worker 职责单一、可独立调优。

### 需要由我确认的主观收获

以下是一些开发过程中的观察，但具体感受需要你来补充：

- 21 天从零完成一个完整的 Agent 系统，最大的推动力是什么？是块状时间的密集投入，还是之前已有的架构积累？
- ConversationLedger C1-C10 的 12 阶段逐层构建过程中，有没有某个阶段的设计被后来的阶段推翻重来？如果有，是哪个阶段的什么假设被修正了？
- 在实际使用中暴露的 Bug（如 Replan 循环、Undo 竞态），哪些是事前可以通过更好的设计来避免的？有没有学到了"下次一定要先考虑 X"的教训？

---

## 八、遗留问题与下一步计划

### 可从代码中确认的问题

1. **长期记忆的检索精度**：MemoryEmbeddingWorker 经历了多次迭代，当前版本仍标注为"优化"。检索策略和 embedding 模型的适配可能还需要继续调优。

2. **ApprovalGateNode 在最后优化中被大量简化**（从 38 行变更缩减大量逻辑），说明审批门控的职责可能部分迁移到了其他地方，需要确认简化后逻辑完整性。

3. **多个提交存在重复**（如 C10、C9R、D2 等都有两遍相同提交信息的 commit），可能是 rebase 或 cherry-pick 留下的，建议清理。

4. **测试覆盖率**：项目中存在测试文件，但几个核心 Bug 都是在实际使用中暴露的（而非测试发现），说明测试覆盖可能不够全面。

### 建议的下一步开发

1. **记忆系统继续优化**：增加 embedding 缓存、批量 embedding 处理、更精细的检索策略
2. **ConversationLedger 的压缩策略调优**：观察生产环境中的 watermark 触发频率，优化 high/low watermark 阈值
3. **增加端到端集成测试**：覆盖 Agent 完整执行流程，特别是 Replan、Undo、并发等边界场景
4. **子 Agent 的 editor 角色**：当前只支持单实例串行，未来可以探索基于文件锁的并行编辑
5. **MCP 工具生态扩展**：目前已有 Playwright 和 Exa，可以继续扩展更多外部工具

---

## 九、提交记录摘要

| 时间段 | 核心改动 | 影响范围 |
|--------|---------|---------|
| 6/20 | 项目初始化 + 最小只读 Agent 闭环（3 次提交） | 全模块搭建、Maven 配置、基本 Agent 循环 |
| 6/21 | 写操作 + 沙箱安全 + 审批系统（3 次提交） | 工具系统、安全模型、路径解析 |
| 6/22 | Plan-Execute-Replan + Checkpoint + 前端集成（2 次提交） | Agent 循环扩展、状态持久化 |
| 6/23 | 上下文工程 + 子 Agent + Trace/预算/韧性（4 次提交） | 上下文管理、多智能体、可观测性、模型韧性 |
| 6/25 | 功能修复 + 重构保护网 + 上下文恢复链（5 次提交） | Bug 修复、架构规划、恢复策略、模型错误处理 |
| 6/25-26 | Controller 拆分 + 领域服务拆分 + 构建器治理（8 次提交） | 大规模架构重构 |
| 6/29 | 工具安全加固 + Prompt Injection 防护 + Git/Shell 增强（5 次提交） | 安全模型、工具系统 |
| 6/29 | Undo 系统重构 + 工具权限重构 + MCP 工具（3 次提交） | 撤销机制、权限模型 |
| 6/30 | Skills v1 + 长期记忆方案 + MySQL→SQLite（4 次提交） | 技能系统、架构迁移 |
| 7/1 | Function Calling 升级 + 会话删除 + 后台 Shell + 记忆 Worker（6 次提交） | 工具协议、异步任务、记忆链路 |
| 7/1-2 | 最终重构：Controller/SSE/Context/ModelCall/文件工具（6 次提交） | 架构收尾、代码质量 |
| 7/2 | 确定性排序 + ConversationLedger C1-C10 启动（6 次提交） | 工具确定性、Ledger 系统开始 |
| 7/3 | C1-C10 全链路 + D2 生产开关（10 次提交） | Ledger 系统完成、生产部署 |
| 7/4 | 缓存诊断 + Token 接口 + 持久化协议（4 次提交） | 缓存可观测性、协议统一 |
| 7/7 | 上下文压缩双轨 + Agent Loop Bug 修复（8 次提交） | 上下文架构、循环稳定性 |
| 7/8-11 | 长期记忆系统 + API 切换 + 最终优化（9 次提交） | 记忆系统、多提供商、架构打磨 |
