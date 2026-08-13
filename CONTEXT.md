# Loom Agent

Loom Agent 是一个在本地工作区中与用户协作完成编码任务的 Agent。这里统一记录其交互与运行领域中的核心语言。

## Language

**Session**:
绑定一个工作区的持久对话，承载跨多次用户请求延续的模式、Conversation History、Session Working Memory 和 Plan 集合。Plan 作为 Session 的独立字段持久化，不进入 Conversation History，也不要求独立物理 Catalog。
_Avoid_: Run、Task

**Conversation History**:
Session 中用户消息、模型消息和已取得持久结果的 Tool Call/Tool Result 的 append-only 事实来源。记录绑定准确的 Run 与 Attempt，并在 Run 的安全边界持续持久化；AgentCheckpoint 只引用其准确 sequence 与摘要，不复制完整历史。它不承载 Run 状态机、Interrupted Tool Call 或权限快照。旧的 Conversation Ledger 独立子系统及其 compaction/generation 设计已被舍弃，不因恢复机制重新引入；当前代码中残留的 `ledger*` 字段名不构成新的领域概念。
_Avoid_: Conversation Ledger、AgentCheckpoint、Mutable History、Working Memory、Execution State

**Session Working Memory**:
跨多个 Run 继承的 Session 级记忆事实。一个 Run 从明确的 Session baseline 启动，并把该 Run 的 Working Memory Overlay 保存在 AgentCheckpoint；只有正常完成的根 Run 才把 overlay 投影到 Session Working Memory。暂停或等待中的 Run 继续由 AgentCheckpoint 持有 overlay，失败、停止、冲突、偏离或被放弃的 Run 不投影；其已持久事实仍保留在 Conversation History，checkpoint 不再复制另一份可独立修改的 Session memory。
_Avoid_: Conversation History、Working Memory Overlay、TaskCheckpoint

**Session Resume**:
恢复一个既有 Session 的对话连续性。Runtime 加载该 Session 的持久上下文，但不接管其中未完成的 Run，也不重放其 Tool Call；恢复后的下一次用户请求创建新的根 Run，并按新 Run 规则重新冻结模式、能力与授权快照。
_Avoid_: Run Recovery、Attempt、Tool Replay

**New Session**:
用户通过 `/new` 创建的独立对话。Runtime 分配新的 Session 身份，初始化空的对话历史、工作记忆、checkpoint、Plan 历史和 Current Plan，并继承当前 Session 的 Build Mode 或 Plan Mode；该模式初始化不是旧 Session 的 Mode Transition。该控制事件不创建 Run、不调用模型，也不修改或删除此前 Session，旧 Session 仍可显式恢复。产品不提供 `/reset` 或其兼容别名。
_Avoid_: Reset、New Plan、Clear History

**Run**:
Session 内对一次用户请求的逻辑处理过程；它在启动时冻结 Session 当前模式为 Run Mode Snapshot，身份跨进程故障恢复保持不变，可以先后由多个 Attempt 推进。后续用户请求属于新的 Run，但可以继承同一 Session 的对话上下文。
_Avoid_: Session

**Run Recovery**:
Runtime 在原 Attempt 异常丢失或经过 Run Suspension 后，由用户从 Recovery Required 状态显式选择、从持久安全边界语义继续一个未终止 Run 的产品操作。它保留原 `runId`、任务目标和冻结的 Run 范围状态，创建新的 Attempt 接管执行，不创建新用户请求或新 Run。它不重新连接、恢复程序计数器或自动重放原 Attempt 中未取得持久结果的 Tool Call；这类调用作为 Interrupted Tool Call 暴露，并在继续前重新观察可验证的当前状态。终态 Run 不可恢复，Session Resume 只检测并展示 Recovery Required，不会隐式执行该操作。
_Avoid_: Session Resume、New Run、Terminal Retry、Tool Replay、Process Resume

**Attempt**:
一个 Runtime 进程对某个逻辑 Run 的一次独占推进周期。Run 初次执行创建首个 Attempt；异常中断后的 Run Recovery 创建新 Attempt，但不改变 Run 身份。Attempt 身份用于所有权、故障边界和审计，并记录该次实际使用的模型、provider 与 Runtime 身份；它不是新的任务身份，也不重新授予或扩大 Run 权限，同一 Run 同一时刻最多有一个有效 Attempt。
_Avoid_: Run、Execution、Retry Count、Permission Grant

**Attempt Lease**:
Runtime 对未终态 Run 的持久独占写入权。初次执行或 Run Recovery 只有取得新的 fenced ownership 后才能创建并推进 Attempt；仍健康的 owner 不可被强制接管，已释放或过期的 owner 不能再写入 Run、AgentCheckpoint、Conversation History 或启动 Tool。Attempt Lease 只证明谁有权推进 Run，不能证明旧 Attempt 的 Tool 是否启动、完成或产生 Effect。
_Avoid_: Process-local Lock、Session Lock、Tool Receipt、Permission Grant

**Recovery Compatibility**:
Runtime 在创建恢复 Attempt 前，对持久 Run 状态与当前执行环境进行的 fail-closed 契约校验。checkpoint schema 与 Workspace 身份必须可由当前 Runtime 原生接受；Run Mode、Permission Policy 与 Grants、Execution Profile、Skill Catalog 和 Active Skill Snapshot 保持冻结；Tool 的输入 schema、Effect 边界及恢复相关契约必须兼容。模型与 provider 可以变化并按 Attempt 审计，Runtime 也可以升级，但不读取旧 schema、不执行 migration 或兼容 fallback。缺失或不兼容的 Tool 只能从恢复 Run 的能力中移除，不能由同名但语义不兼容的实现替换；恢复能力只能保持或缩小，不能加入新发现的 Tool 或 Skill。
_Avoid_: Binary Replay、Schema Migration、Compatibility Fallback、Capability Expansion

**AgentCheckpoint**:
Run 在持久安全边界保存的不可变、可版本化恢复快照，也是产品唯一的 checkpoint 类型。它保存 Run/Attempt 身份、执行状态、冻结契约、Working Memory Overlay、预算、Interrupted Tool Call、Ambiguity Review 及准确的 Conversation History anchor；它不复制完整 Conversation History 或 Session Working Memory。现有 `AgentCheckpoint` 直接改变为该 schema，不新增同义的 `RunCheckpoint`；`TaskCheckpoint` 被删除，旧 schema 不兼容读取或迁移。
_Avoid_: TaskCheckpoint、RunCheckpoint、Conversation History Copy、Session Snapshot

**Working Memory Overlay**:
当前 Run 相对于启动时 Session Working Memory baseline 产生的 Run 范围记忆变化。它由 AgentCheckpoint 唯一持久化，用于同 Run 的 Attempt 恢复；只有根 Run 正常完成时才按明确顺序投影回 Session Working Memory，其他终止结果丢弃 overlay 的跨 Run 投影但不删除 Conversation History 事实，不能通过 checkpoint/history 双向复制隐式合并。
_Avoid_: Session Working Memory、Conversation History、TaskCheckpoint

**Interrupted Tool Call**:
原 Attempt 已持久记录进入执行窗口、但在 Attempt 异常丢失前没有持久 Tool Result 的调用。该状态只证明调用处于可能执行的歧义窗口，不证明适配器实际启动、完成、失败或产生了何种 Effect；Run Recovery 不自动再次调用它，而是保留其脱敏身份，重新观察可验证的 Repository State，并向 Agent 与用户明确暴露无法验证的 Shell、MCP 或外部结果。它不是 `FAILED`、`PARTIAL` 或可安全重试的同义词，也不改变后续新 Tool Call 的 Permission Decision。
_Avoid_: Failed Tool Call、Partial Success、Automatic Retry、Exactly-once Receipt

**Interrupted Delegate Call**:
父 Run 已创建 Delegate Run、但父级 Conversation History 尚未取得持久 Delegate Result 时，原 Attempt 异常丢失或暂停所留下的中断事实。根 Run 是唯一恢复单位；旧 Delegate 的 Attempt 不重连、不恢复，其子 Run 以 `INTERRUPTED_WITH_PARENT` 终止。Delegate 已产生但无法确认的 Repository State 或外部 Effect 进入根 Run 的 Ambiguity Review；根 Run 继续后可以重新规划并创建新的 Delegate Run，但不能拼接旧 Delegate 的内部推理、程序位置或进程状态。
_Avoid_: Delegate Recovery、Independent Recovery Required、Delegate Result、Automatic Redispatch

**Recovery Required**:
Session 存在失去有效 Attempt 的未终态根 Run 时进入的阻塞控制状态。Runtime 自动检测并展示原任务、最后持久安全边界、Interrupted Tool Call、已知 Repository State 变化和中断时间，但不调用模型或工具；在用户显式选择可用的 Run Recovery 或 Run Abandonment 前，该 Session 不得接受普通用户请求或创建新的根 Run。Full Access Run 及其他不满足 Recovery Compatibility 的 Run 只允许检查和 Run Abandonment，不提供降级恢复。用户可以切换或创建其他 Session 继续工作。
_Avoid_: Automatic Recovery、Background Resume、Non-blocking Warning、New Run

**Recovery Blocked**:
Recovery Required 中因 Full Access、最新 AgentCheckpoint 或 History anchor 损坏/缺失、Workspace 身份不匹配、schema 不受支持或冻结契约不兼容而无法创建恢复 Attempt 的 fail-closed 状态。Runtime 展示准确原因并只允许检查与 Run Abandonment；不能把损坏当作不存在、静默回退到更旧 checkpoint、迁移旧 schema，或用缩小 Execution Profile 规避边界。
_Avoid_: Recovery Required、Older-checkpoint Fallback、Automatic Repair、Recover in Sandbox

**Run Abandonment**:
用户在活动 Run 或 Recovery Required 状态下显式停止并放弃未终态 Run 的终止控制事件。它不调用模型或工具，不回滚、删除或宣称解决已经发生的 Repository State 或外部 Effect，并保留 Interrupted Tool Call、修改与审计记录；被放弃的 Run 进入不可恢复终态，此后用户的普通请求创建新的 Run。
_Avoid_: Run Recovery、Rollback、Delete Run、Mark Tool Failed

**Run Suspension**:
用户在活动 Run 中显式选择“暂停并退出”产生的可恢复控制事件。Runtime 请求停止当前 Attempt 及其进程树并持久化最后安全边界；Run 保持非终态，无法取得确定结果的在途调用成为 Interrupted Tool Call，Session 下次打开时进入 Recovery Required。活动 Run 的普通退出或首次 `Ctrl-C` 必须让用户在 Run Suspension 与 Run Abandonment 之间明确选择，不能含糊地把退出等同于失败、放弃或自动恢复。Full Access Run 不可在进程退出后恢复，因此不提供 Run Suspension；其退出选择只能是返回活动 Run 或明确 Run Abandonment。
_Avoid_: Run Abandonment、Automatic Recovery、Terminal Failure、Background Process

**Ambiguity Review**:
Run Recovery 已完成允许的安全观察、但仍无法用可信证据确定某个 Interrupted Tool Call 结果时进入的阻塞控制状态。Runtime 保留并展示未知事实，不把它改写为成功、失败或 Partial Success；用户可以补充外部事实、选择 Run Abandonment，或选择 Continue with Ambiguity。该状态不授予、撤销或覆盖任何 Tool 权限。
_Avoid_: Tool Approval、Permission Decision、Automatic Retry、Mark Failed

**Continue with Ambiguity**:
用户在 Ambiguity Review 中明确允许 Run 在保留未知 Tool 结果的前提下继续的控制事件。它不调用或重试 Tool，不把用户陈述提升为可信 Tool Result，也不产生 Permission Grant；此后的每个新 Tool Call 仍统一通过既有 Effect、Execution Profile 与 Permission Policy 求值，`ALLOW` 调用不会仅因与 Interrupted Tool Call 相同或相似而增加恢复专属询问。
_Avoid_: Retry Approval、Permission Override、Mark Succeeded、Mark Failed

**Run Mode Snapshot**:
根 Run 启动时从 Session 当前 Build Mode 或 Plan Mode 复制的不可变模式值，决定该 Run 的 Prompt、Effective Tool Catalog 与 Call Effect 策略，并写入 Run 审计记录。Delegate Run 继承父 Run 的 Snapshot；Run 启动后的 Mode Transition 只影响后续 Run，不能改变进行中的 Run。
_Avoid_: Session Mode、Mode Transition、Dynamic Policy

**Permission Policy Snapshot**:
根 Run 启动前从 Runtime Built-in、User-local 与 Project Source 原子编译得到的不可变基础权限视图。任一来源存在语法或校验错误时，该 Run 在调用模型或工具前 fail closed，并报告来源与位置；不忽略错误规则，也不部分加载。磁盘上的规则变化只影响后续 Run，Delegate Run 继承根 Run 的 Snapshot；当前 Run 内用户批准产生的 Permission Grant 通过独立的 append-only overlay 立即参与后续 Tool Call 求值。
_Avoid_: Live Policy Reload、Partial Rule Set、Silent Fallback

**Tool Approval**:
用户对 Permission Decision 为 `ASK` 的工具调用是否放行的决定。用户通过 Approval Display 审查调用；是否请求 Tool Approval 与该调用的 Effect Profile 是两个独立维度，且任何批准都不能放宽 Plan Mode 或 Execution Profile 的约束。
_Avoid_: Plan Approval、Plan Acceptance

**Skill Activation**:
Runtime 控制面将一个已发现并校验的 Skill 指令内容纳入当前 Run 受控模型指令上下文的非终止状态转换；它可以来自用户显式选择或模型根据 Skill 描述提交的专用 `Skill Activation` 协议动作，但不是 Tool Call，不产生 Permission Decision，不请求 Tool Approval，也不消耗 Tool step。Runtime 只接受当前 Skill Catalog Snapshot 中的 Effective Skill Descriptor，按内容摘要解析并冻结完整 Active Skill Snapshot，再以独立、低于基础 Runtime 规则的 system-prompt 区段装配到下一次模型调用；普通 ToolResult 永远不提供这条提升路径。Activation 只在该 Run 内有效，Run 结束即丢弃，不写入 Session active state；每个新 Run 都基于当前请求和新的不可变 Skill Catalog Snapshot 重新匹配与装配，因此同一 Session 的后续请求不会自动携带此前 Skill。Skill 只影响模型如何完成任务，不能增加 Effective Tool Catalog、授予 Permission Grant 或 Execution Grant、放宽 Execution Profile、改变 Permission Policy，或预批准后续调用；模型依据 Skill 发起的每个 Tool Call 与普通 Tool Call 完全相同，独立通过既有 Runtime Gate，因此在 `ALLOW` 下直接执行、在 `ASK` 下请求批准、在 `DENY` 或能力越界时拒绝。
_Avoid_: Load-skill Tool、Trusted Tool Output、Session Skill State、Sticky Skill、Skill Approval、Tool Authorization、Permission Grant、Direct Tool Invocation

**Active Skill Snapshot**:
Skill Activation 成功时由 Runtime 从 Effective Skill Descriptor 对应内容原子生成的、仅属于当前 Run 的不可变指令快照，包含稳定名称、来源、完整指令正文、正文摘要，以及带规范化相对路径和内容摘要的不可变资源清单。正文必须在装配前整体通过大小和上下文预算校验，不能静默截断；来源发生漂移、读取不完整或预算不足时 Activation 原子失败，不把部分指令加入 prompt。它是受控的低优先级模型指令，不是权限、Tool Output、Session 状态或可执行代码；AgentCheckpoint 必须足以确定性恢复同一快照。
_Avoid_: Tool Result、Partial Skill、Live Skill File、Permission Grant、Executable Script

**Skill Invocation**:
为当前 Run 请求 Skill Activation 的产品入口。用户可以在请求中通过 `$skill-name` 显式选择一个或多个允许用户调用的 Skill，Runtime 在第一次模型调用前解析、去重并装配；模型也可以根据 Skill Catalog Snapshot 中允许模型调用的名称、描述与来源，在 Run 内提交专用 Skill Activation 动作隐式选择。两条路径必须汇聚到同一校验、快照和 prompt 装配流程，不产生不同权限；`disable-model-invocation` 与 `user-invocable` 只缩小对应入口。显式选择未知、无效、禁止用户调用或无法完整装配的 Skill 时在模型调用前明确失败。`/skills` 是列出当前 Workspace 有效 Skill、准确来源、调用方向与遮蔽/校验/兼容诊断的控制命令，不创建 Run；首版不把任意 `/skill-name` 注册为动态控制命令。
_Avoid_: Slash-command Skill、Model-parsed Explicit Selection、Implicit Permission、Different Activation Trust

**Skill Resource Observation**:
模型通过普通 `read_skill_resource` Tool 对当前 Run 已激活 Skill 的配套文件进行的按需只读观察。Runtime 只接受 Active Skill Snapshot 已绑定资源清单中的规范化相对路径，拒绝绝对路径、路径穿越、越出 Skill 根目录的 symlink、内容漂移、未激活 Skill 和未纳入清单的文件；结果始终作为不可信 Tool 数据处理并遵循普通 Tool 的 Effect、Permission Policy、Execution Profile、trace 与大小限制，不能改变 Agent 规则。`references/`、`assets/` 与 `scripts/` 只是约定目录，`scripts/` 不赋予自动执行语义；执行任何脚本必须由独立的普通 Tool Call 完成。首版不提供 Skill 创建、安装、更新、复制或专用脚本执行 Tool。
_Avoid_: Skill Instruction、Implicit Execution、Arbitrary Host Read、Trusted Resource、Skill Script Runner

**Skill Script Execution**:
模型依据 Active Skill Snapshot 请求执行配套脚本时产生的普通 Shell Tool Call，而不是 Skill Activation 的组成部分。项目 Skill 脚本位于 Workspace 内，按当前 Run 的普通 Shell Permission Policy 与 Execution Profile 求值；用户 Skill 脚本位于 Workspace 外，只有匹配的既有 Execution Grant、用户批准的最小 Execution Request 或 Full Access 才能让进程访问。Tool Permission 的 `ALLOW` 不隐含工作区外文件访问，Skill Activation 也不自动挂载用户 Skill 根目录、复制可执行代码或授予 Host Resource；所有现有 sandbox、Effect、Plan Mode 和 Built-in Safety Floor 约束继续生效。
_Avoid_: Skill Runtime、Automatic Mount、Implicit Execution Grant、Activation-time Execution、Permission Bypass

**Skill Inheritance**:
同一根 Run 任务树内的 Delegate Skill 传播规则。Delegate Run 不重新扫描磁盘，而是继承根 Run 冻结的 Skill Catalog Snapshot 与创建 Delegate 时父级已有的 Active Skill Snapshot，使用户显式选择或父级采用的当前任务方法在委派后保持有效；Delegate 可以根据其子任务从继承 Catalog 中额外执行 Skill Activation，但新增快照只属于该 Delegate 及其后代，不反向修改父级。继承与新增 Skill 都不能扩大 Delegate 的 Effective Tool Catalog、Permission Policy 或 Execution Profile；根 Run 完成时整个任务树的 Skill 状态一起丢弃。
_Avoid_: Delegate Rediscovery、Session Skill State、Child-to-parent Activation、Capability Inheritance

**Skill Catalog Snapshot**:
Runtime 在根 Run 启动时从已配置 Skill 来源完成发现、校验和冲突处理后冻结的不可变元数据视图，至少保留稳定身份、名称、描述、来源、内容摘要与诊断，并作为该 Run 显式或隐式 Skill Activation 的唯一候选集合。它只用于模型发现、用户选择和确定性激活，不是授权边界；磁盘变化只影响后续 Run，Delegate Run 只能继承或进一步缩小根 Run 的 Snapshot。
_Avoid_: Live Skill Index、Session Skill State、Active Skill、Authorization Catalog

**Skill Source**:
Runtime 被明确配置为发现 Skill Package 的本地根目录及其信任来源。首版同时发现用户级 `~/.agents/skills`、`~/.claude/skills` 与项目级 `<workspace>/.agents/skills`、`<workspace>/.claude/skills`；`.agents/skills` 是 Loom 的规范来源，`.claude/skills` 仅提供零迁移的目录与开放格式兼容，不承诺 Claude 专属运行语义。未知扩展不能扩大 Loom 能力：加载过程不执行动态命令替换，`allowed-tools` 不产生授权，脚本只可由模型通过普通 Tool Call 执行；历史 `~/.loom-agent/skills` 不再支持。
_Avoid_: Full Claude Compatibility、Legacy Skill Path、Dynamic Instruction Source、Permission Rule Source

**Skill Package**:
Skill Source 下以目录组织的可移植行为包，必须包含符合 Agent Skills 开放格式的 `SKILL.md`，并可包含 `references/`、`assets/`、`scripts/` 等配套资源。首版严格要求合法且与目录身份一致的 `name` 和非空 `description`，支持标准 `license`、`compatibility`、`metadata`；YAML、名称、编码、大小、路径或资源清单校验失败时整个 Package 无效，不进行部分加载。为兼容 `.claude/skills`，Loom 额外尊重 `disable-model-invocation: true` 与 `user-invocable: false` 的调用方向限制；`allowed-tools`、`disallowed-tools`、`context`、`agent`、`model`、参数替换及动态命令注入等未支持扩展只产生确定性兼容诊断，不能改变权限、执行拓扑或加载结果。Skill body 中类似命令的文本仍是静态指令，Activation 不执行它。
_Avoid_: Partial Skill、Loose Frontmatter、Claude Runtime Extension、Loading-time Execution、Authority-bearing Metadata

**Effective Skill Descriptor**:
Skill Catalog Snapshot 对某个规范化 Skill Name 选出的唯一可激活描述符，保留名称、描述、内容摘要及准确 Skill Source。多个来源定义同名 Skill 时，Runtime 按 `user .agents > user .claude > project .agents > project .claude` 选择最高优先级版本；其余版本不进入激活候选集，但必须产生包含胜出与被遮蔽来源的确定性诊断，且目录展示、Activation trace 与审计始终保留胜出来源。项目 Skill 因此不能用同名定义覆盖用户 Skill。
_Avoid_: Source-erased Skill、Ambiguous Skill Name、Project-over-user Shadowing、Qualified Invocation

**Approval Display**:
Runtime 在请求 Tool Approval 时向当前交互用户展示的临时、已脱敏调用视图。Shell 调用必须展示规范化命令、子命令、工作区、Execution Profile、命中规则及理由，使用户能够理解授权对象；未脱敏输入不得因此进入 trace、Session 或其他持久 Artifact。
_Avoid_: Approval Audit、Argument Hash、Raw Persisted Input

**Effect Profile**:
Runtime 在执行工具前判定的、该调用在其 Execution Profile 中仍然可能产生的最大影响组合。它包含可并存的 `REPOSITORY_READ`、`DISPOSABLE_WRITE`、`REPOSITORY_MUTATION`、`EXTERNAL_READ`、`EXTERNAL_MUTATION` 状态影响集合，`NONE | PRESENT | UNKNOWN` 的 Outbound Disclosure 状态，以及分类是否完整；不包含风险分数或置信度。它由 Tool Capability Envelope、可信的 Call Effect Assessment 与 Runtime 强制约束共同确定，而不是对执行结果的预测。Plan Mode 分别校验每个影响，分类不完整或披露未知时 fail closed；Plan Shell 可以在 Execution Profile 限定的 Run-scoped Disposable State 中产生 `DISPOSABLE_WRITE`，但不能将其转化为 Repository Mutation。执行后的状态检测只用于审计和发现分类错误，不能追授权限。
_Avoid_: Single Effect、Risk Level、Approval Requirement、Confidence Score

**Tool Capability Envelope**:
工具在给定执行环境中可能产生的最大影响范围。它是调用级 Effect 分析的保守起点；只有可信的参数规则或 Runtime 强制的 Execution Profile 才能缩小该范围。
_Avoid_: Actual Effect、Approval Requirement、Tool Description

**Call Effect Assessment**:
工具集成在参数完成校验后，依据结构化参数与 Runtime 上下文给出的确定性 Effect 证据。它可以缩小 Tool Capability Envelope 并产生 Effect Profile，但不能依靠 Agent 声明、自然语言意图或不可靠的启发式推断；无法确定时保持最宽范围或将分类标记为不完整。
_Avoid_: Agent Intent、Command Prediction、Post-execution Audit

**Execution Profile**:
Runtime 为一次工具调用强制施加的能力边界，例如工作区可写范围、临时目录、网络与凭据访问范围。工作区权限只覆盖真实路径仍位于规范化 Workspace Root 内的对象，不能通过路径穿越或 symlink 获得仓库外权限；外部目标必须由适用的 Execution Grant 或 Host Resource Grant 明确加入。Effect Profile 以这些实际可执行能力为准；仅在 Execution Profile 能可靠阻止某类影响时，Runtime 才能从 Effect Profile 中排除该影响。
_Avoid_: Collaboration Mode、Approval Policy、Prompt Instruction

**Execution Grant**:
用户为普通根 Build Run 显式授予的有界文件系统能力，使一个经真实路径解析的普通文件或目录获得明确的只读或读写访问。它与 Permission Grant 分开求值，可以按单次、Session 或 Workspace 生效，但不能覆盖 socket、设备节点、FIFO 等特殊文件，不能授予 sandboxed Shell 网络、关闭 sandbox、启用 Full Access、放宽 Built-in Safety Floor，或由 Plan Mode、Delegate Run、Agent 和项目配置获得。
_Avoid_: Permission Grant、Full Access、Sandbox Fallback

**Execution Request**:
Agent 在普通根 Build Tool Call 中、进程启动前声明的最小额外文件系统需求。Runtime 只能用已有或用户新批准的 Execution Grant 满足它；未声明或未获授权的访问由 sandbox 拒绝并终止本次调用，不会自动扩权重试，后续继续必须使用新的 Tool Call。首版 sandboxed Shell 不接受网络、IPC 或特殊文件 Execution Request。
_Avoid_: Execution Grant、Post-failure Retry、Agent-granted Capability

**Full Access**:
用户在当前 Runtime launch 中为后续根 Build Run 显式选择的 `DANGER_FULL_ACCESS` Execution Profile 与默认 `ALLOW` Permission Action 组合。它允许普通工具调用和 Shell 命令在主机当前用户的文件系统与网络权限下静默执行，并继续求值工具 allowlist、显式 `ASK`/`DENY` 规则与 Built-in Safety Floor；但任意脚本、插件或二进制的内部行为不受 sandbox 约束，命令规则无法提供间接行为的主机安全保证。选择在 Run 启动时冻结，不写入 Session，Plan Mode、Delegate Run、Agent、项目配置和 Tool Approval 都不能启用或继承它。使用 Full Access 的 Run 在进程退出后不可 Run Recovery：Recovery Required 只允许检查与 Run Abandonment，不能在同一 Run 中恢复 Full Access 或切换为 sandboxed Execution Profile。
_Avoid_: Auto、Bypass Permission Evaluator、Plan Override

**Host Resource Grant**:
用户本地对 Execution Profile 作出的显式主机资源授权，使特定 Workspace 的 Shell 可以在不扩大其他能力的前提下只读访问一个明确的 artifact cache 或等价资源。它不能来自 Project Source，不包含凭据配置，也不允许写回真实主机缓存；缺少 Grant 时 Plan Shell 使用空的 Run-scoped HOME。
_Avoid_: Permission Grant、Project Rule、Home Directory Access

**Sensitive Resource**:
Runtime 依据共享的路径与文件语义分类器能够从 Tool Call 输入中识别出的、读取后可能向模型上下文披露秘密的文件，例如直接引用的真实 `.env` 文件、私钥和凭据文件。结构化文件工具与 Shell 共用该 best-effort 分类：可识别的 Repository State 敏感读取首次执行前显示披露警告并请求 Tool Approval，之后只有用户对精确规范化调用作出的 Session 或 Workspace Permission Grant 才能免除重复询问，Project Source 不能授权；明确的示例文件不属于该分类。普通 sandboxed Execution Profile 仍隐藏主机凭据，但 Runtime 不尝试通过逐文件 sandbox 规则发现脚本、插件和二进制内部的间接读取；Full Access 对这类内部行为同样不提供保证。
_Avoid_: Secret Scanner、All Configuration Files、Host Resource Grant

**Plan Shell**:
Plan Mode 中由 `run_shell` 提供、但只能在 fail-closed Execution Profile 内运行的 Shell 能力。它可以读取 Repository State、使用 Run 范围的 Disposable State，并通过 Host Resource Grant 只读访问明确授权的 artifact cache，但不能修改 Repository State、读取未授权的主机私有数据、访问网络或改变外部业务状态；命令文本分类只参与审批分流，不能单独证明 Effect 安全。
_Avoid_: Unrestricted Shell、Read-only Command、Safe Command

**Shell Process Supervisor**:
Runtime 对每次 Shell Tool Call 及其完整进程树施加的生命周期与输出边界。所有后代进程只在该调用内存活，并在调用完成、取消或超时时统一终止；stdout 与 stderr 被持续排空到有界缓冲，超限内容截断；同一 Run 的并发 Shell 数受限。它不承诺严格的 CPU、内存或 PID 配额，也不支持用后台进程跨 Tool Call 承载长期服务。
_Avoid_: OS Sandbox、Job Manager、Resource Quota

**Effective Tool Catalog**:
Runtime 根据当前 Run 的基础权限、协作模式与工具能力生成并提供给模型的工具集合。Plan Mode 不展示所有调用都必然越界的工具；存在合法调用的动态工具仍可展示并逐调用执行 Effect Profile 预检。该 Catalog 用于表达能力和减少误调用，不是授权边界，任何未展示或伪造的调用仍必须通过 Runtime Gate。
_Avoid_: Tool Allowlist、Authorization Decision、Prompt-only Guard

**Permission Policy**:
基础 Session 权限对每次具体 Tool Call 统一求值的策略，由一个默认 Permission Action 与具有不同授权能力的 Permission Rule Source 共同组成；`ask`、`auto`、`never` 只是分别选择 `ASK`、`ALLOW`、`DENY` 默认动作的产品预设。Plan Mode 与 Build Mode、内置工具与外部工具共用同一策略，模式差异由其之前的 Effect 与 Execution Profile 授权边界表达。
_Avoid_: Approval Requirement、Tool-level Approval Flag、Plan Permission

**Permission Decision**:
Permission Policy 对一次具体 Tool Call 求值得到的 `ALLOW`、`ASK` 或 `DENY` 结果及其理由。它决定调用是否直接继续、请求 Tool Approval 或被拒绝，但不能扩大 Effect、Execution Profile、工具 allowlist 或协作模式已经授予的能力。
_Avoid_: Effect Profile、Authorization Boundary、Risk Score

**Permission Rule**:
Permission Rule Source 拥有的输入感知规则，由目标工具、工具专属 matcher 与 `ALLOW`、`ASK` 或 `DENY` 动作组成。Matcher 只在规范化输入上求值；Shell matcher 对已完整解析的 executable units 使用 token 前缀，opaque 输入只能精确匹配；多个命中结果取最严格动作。
_Avoid_: Raw Command Glob、Tool-level Flag、Risk Level

**Permission Rule Source**:
一组 Permission Rule 的权限来源及其可授予范围。Runtime Built-in Source 定义不可被放宽的 Built-in Safety Floor；Project Source 随 Repository State 分发但只能收紧权限；User-local Source 可以为用户显式信任的调用扩权；Session Source 只承载有界生命周期内的临时决定。
_Avoid_: Rule Priority、Config File、Repository Trust State

**Built-in Safety Floor**:
Runtime Built-in Source 中任何其他 Permission Rule Source 都不能放宽的最低权限策略。它只自动允许能够完整证明为授权工作区内只读的调用，对可识别的高影响行为强制 `ASK`，并对可识别的灾难性调用执行不可覆盖的 `DENY`；Plan Mode 的硬授权边界仍在它之前求值。它不是系统调用安全边界：sandboxed Execution Profile 承担实际能力约束，而 Full Access 中脚本、插件和二进制的间接行为只能 best-effort 分类，不能保证被拦截。
_Avoid_: Risk Level、Default Action、Project Policy

**Permission Grant**:
用户通过 Tool Approval 对一个完整规范化 Tool Call 作出的可复用授权，范围可以是单次调用、当前 Session 或当前 Workspace 的 User-local Source。可复用 Grant 必须绑定原始 Execution Profile，只能在相同或更严格的边界内生效；Runtime 不从一次批准自动推导更宽的命令前缀。
_Avoid_: Permission Rule、Plan Approval、Unscoped Always Allow

**External Read**:
通过结构化工具从工作区之外查询或获取信息、但不以创建、更新、删除、发布或发送外部业务资源为目的的 Effect。Plan Mode 可以在基础 Session 权限与 Outbound Disclosure 约束内执行 External Read；服务提供方不可避免的日志或遥测不视为 Agent 请求的 External Mutation。
_Avoid_: External Mutation、Offline Operation、Risk-free Call

**External Mutation**:
创建、更新、删除、发布、发送或以其他方式改变用户可观察外部业务状态的 Effect，例如发送消息、创建工单、推送提交、发布包或修改云资源。Plan Mode 始终禁止 External Mutation，Tool Approval 不能覆盖该限制。
_Avoid_: External Read、Outbound Disclosure、Local Mutation

**Outbound Disclosure**:
工具调用向工作区之外发送查询内容、URL、代码、文件内容、凭据或其他数据的行为及其权限边界。它与 External Read、External Mutation 和 Permission Decision 分开判定；Plan Mode 不授予任何披露权限，只能使用基础 Session 策略已经允许的数据范围。
_Avoid_: External Mutation、Network Access、Tool Output

**Build Mode**:
Session 的常规协作模式，Agent 可以分析、回答并在原有权限范围内实施修改。普通 Build Shell 在离线的 Workspace-write Execution Profile 中运行；只有用户显式选择 Full Access 时，根 Build Run 才使用主机当前用户的文件系统与网络权限。进入 Build Mode 不要求存在 Plan，也不表示必须执行某个 Plan。
_Avoid_: Default Mode、Execute Mode、Code Mode、Build Agent

**Plan Mode**:
Session 的一种持续协作模式，Agent 在其中探索问题并提出方案，但不实施方案。它跨 Run 保持有效，直到用户发起显式 Mode Transition；它依据每次工具调用的 Effect Profile 保持 Repository State 与外部业务状态不变，但可以通过结构化工具执行基础 Session 权限允许的 External Read，并通过 Plan Shell 使用 Run 范围的 Disposable State。Plan Shell 只有在 Runtime 能强制施加 fail-closed Execution Profile 时才可见和执行，隔离不可用时不得退化为普通 Shell；Tool Approval 不能覆盖该边界。Plan Mode 只会收紧原有权限，不能授予权限或被任何 Tool Approval 覆盖。
_Avoid_: Planning Phase、Planner Agent、Plan-and-Execute

**Plan**:
Plan Mode 中由 Agent 明确提交的、具有独立身份与修订历史的持久 Session Artifact。它由 Runtime 管理的结构化元数据与 Agent 提交的完整 Plan Document 组成，作为 AgentSession 中独立于 Conversation History 的字段集合持久化；不设置 Draft 状态，也不是可执行步骤图或工作流状态机。一个 Session 可以保存多个 Plan；普通答疑、澄清和阶段性分析不是 Plan。Plan 的产出会完成当前 Run，但不会授权执行，也不会使 Run 进入等待审批状态。
_Avoid_: Assistant Message、Execution Contract、Tool Approval、TODO List

**Plan Document**:
Plan revision 中面向用户与 Build Run 的完整内容快照，包括标题、Markdown 正文和 Agent 额外声明的依赖。正文可以描述步骤、文件、验证与风险，但 Runtime 不解析其中的 checkbox、步骤状态或依赖关系；Plan Handoff 传递准确 revision 的完整文档。关键产品或架构选择尚未解决时不能提交 Plan Document，阶段性内容继续保留为对话消息。
_Avoid_: Execution Graph、Task State、Runtime Metadata

**Current Plan**:
Session 中当前被选中的唯一 Plan 身份及其最新 revision；未显式开始 New Plan 的 Plan Run 与未显式指定 Plan 身份的 Plan Handoff 都以它为目标。Session 最多有一个 Current Plan，历史 Plan 与旧 revision 不因此被删除。
_Avoid_: Latest Message、All Active Plans

**Select Plan**:
用户通过 `/plan select <plan-id>` 将某个既有 Plan 身份的最新 revision 设为 Current Plan 的显式控制事件。它不创建 Run、不调用模型，也不能选择旧 revision 作为可修订或可 Handoff 的 head；旧 revision 只供查看与审计。
_Avoid_: Plan Handoff、Plan Submission、Revision Branch

**Plan Target**:
Plan Run 启动时由 Runtime 固定的提交目标，取值为 `NEW` 或某个准确的 Plan 身份与最新 revision。Plan Submission 只能创建该目标指定的新 Plan 或在该 revision 上线性追加下一修订；Run 期间 Target 不随 Current Plan 或其他并发 Run 的变化而改变，目标 revision 已变化时不得覆盖提交。
_Avoid_: Current Plan、Plan Handoff、Agent Intent

**New Plan**:
用户通过 `/plan new` 显式开始一项独立规划的选择。它不删除历史 Plan，也不调用模型；它使后续 Plan Run 的 Plan Target 为 `NEW`，而不是修订此前的 Current Plan。Agent 不能仅根据对话内容自行触发 New Plan。
_Avoid_: Plan Revision、Mode Transition、Plan Submission

**Plan Submission**:
Agent 在 Plan Mode 中明确提交完整 Plan 的协议级终止动作；Runtime 重新校验该 Run 已固定的 Plan Target 与 Plan Basis 后，原子地创建 Plan 或产生新的 Plan 修订，分配 Plan 身份与 revision，并完成当前 Run。Agent 只提交 Plan Document，不能指定目标或 revision；普通最终回答、澄清问题、阶段性结论与 Tool Call 都不会改变任何 Plan。校验失败时不发生 Plan Submission，而是以 Plan Conflict 终止 Run。
_Avoid_: Final Answer、Assistant Message、Tool Call、Mode Transition

**Plan Conflict**:
Plan Submission 持久化前发现 Plan Target 不再是最新 revision、相关 Evidence Receipt 已无法针对当前 Repository State 重新验证，或当前 Run 已发生 Evidence Drift 时产生的 Run 终止结果。Runtime 不保存候选 revision、不改变 Current Plan，也不在同一 Run 中自动重试；结果必须指出冲突的 revision 或 Evidence，后续 Plan Run 重新调查。
_Avoid_: Stale Plan、Plan Deviation、Automatic Retry

**Plan Handoff**:
用户在 Build Mode 中通过 `/plan handoff [plan-id]` 明确要求按某个 Plan 工作，并将命令启动的新 Run 绑定到启动时确定的 Plan 身份与修订。绑定约束 Plan 中的目标、范围、架构决策与验证要求，但不把 Markdown 步骤变成执行状态机；Agent 可以调整不改变这些约束的实施细节。普通 Build Run 不绑定 Current Plan，已绑定的 Run 也不会随 Plan 后续修订而改变；Stale Plan 不能进行 Plan Handoff，Plan Mode 中的该命令也不会自动切换模式。
_Avoid_: Mode Transition、Plan Submission、Implicit Plan Context

**Plan Deviation**:
绑定 Plan 的 Build Run 发现继续工作必须实质改变目标、扩大范围、推翻架构决策或跳过关键验证时产生的终止报告。Agent 必须停止继续制造偏离，保留并如实报告已经发生的修改，且不能在 Build Mode 中改写 Plan；用户随后可以修订 Plan 后重新 Handoff，或显式发起不绑定 Plan 的 Build Run。
_Avoid_: Plan Revision、Tool Approval、Automatic Rollback

**Plan Basis**:
某个 Plan revision 形成时所依赖的 Plan Evidence 集合，用于判断该 revision 在后续 Plan Handoff 时是否仍然有效。新 Plan 的 Basis 从当前 Plan Run 捕获的 Receipt 开始；修订既有 Plan 时，候选 Basis 以上一 revision 的 Receipt 为基线，当前 Run 中相同 Evidence Key 的新 Receipt 替换继承的 Receipt，新 Key 追加，未重新观察的旧 Receipt 继续继承且 Agent 不能删除。跨 revision 替换不属于同一 Run 的 Evidence Drift。它以 Runtime 捕获的 Evidence Receipt 为下限，Agent 可以补充依赖但不能移除已捕获证据。Plan Submission 与 Plan Handoff 通过各 Receipt 的重新验证规则判断 Basis，而不是要求 Runtime 追踪进程实际打开的每个文件。
_Avoid_: Whole Workspace、Plan Content

**Plan Evidence**:
Plan Mode 探索过程中由根 Run 或其 Delegate 子 Run 通过成功且完整的观察调用获得的 Repository State 事实。每项证据由 Runtime 保存为 Evidence Receipt；结构化只读工具与受支持的 Plan Shell 命令可以产生精确证据，缺少 Shell Evidence Adapter 的成功 Shell 调用产生覆盖整个 Repository State 的粗粒度证据。成功的否定观察可以成为证据，失败、结果不完整或无法重新验证的观察不能贡献精确证据。同一 Plan Run 中后续 Receipt 不能覆盖此前 Receipt；相同 Evidence Key 观察到不同状态时产生 Evidence Drift。Runtime 负责将子 Run 证据归入根 Plan Run；Agent 或子 Agent 的文字陈述本身不是 Plan Evidence。
_Avoid_: Agent Claim、Conversation Note、Whole Workspace Snapshot

**Evidence Receipt**:
由可信工具适配器在模型可见输出被截断或脱敏之前产生的、不可由 Agent 伪造的 Plan Evidence 记录，包含稳定的 Evidence Key、工具语义、规范化观察范围、状态摘要、完整性和来源 Run，以及确定性的重新验证规则。Evidence Key 标识同一种语义观察，不包含状态摘要。`read_file` 记录文件范围，`list_files` 记录完整目录项，`search` 记录规范化查询及完整搜索结果；Plan Shell 只有在 Shell Evidence Adapter 能确定语义观察范围时才产生同类精确 Receipt，否则记录整个 Repository State 的摘要，因此仓库中的任何后续变化都会使对应证据失效。
_Avoid_: Tool Output、Agent-declared Dependency、Filesystem Access Log

**Shell Evidence Adapter**:
Runtime 为一种已完整解析的 Plan Shell 命令形式提供的可信语义 Adapter，将命令及其规范化参数映射为文件、目录、搜索、Git 状态或其他可确定重验的 Evidence Scope。它可以缩小 Shell Evidence 的失效范围；Runtime 不从 stdout 推断依赖，也不通过系统调用追踪任意进程实际读取的文件。
_Avoid_: Shell Output Parser、Filesystem Access Log、Agent-declared Scope

**Evidence Drift**:
同一根 Plan Run（包括其 Delegate Run）中，该 Run 自身对相同 Evidence Key 先后产生不同状态摘要，表明推理可能混合了不同版本的 Repository State。后一次读取不能替换或消除该 Run 的先前证据；Runtime 标记该 Run 已漂移，Plan Submission 必须以 Plan Conflict 终止。修订 Plan 时以当前 Run 的新 Receipt 替换上一 revision 继承的同 Key Receipt 不属于 Drift。Agent 仍可在当前 Run 中解释变化，但只有新的 Plan Run 可以在一致状态上提交 Plan。
_Avoid_: Stale Plan、Evidence Refresh、Plan Revision

**Delegate Run**:
根 Run 通过 delegate 发起的受限调查子 Run。它的有效权限是父 Run 有效权限与 Delegate 自身限制的交集，不能执行 Mode Transition、New Plan、Select Plan、Plan Handoff 或 Plan Submission；它只返回调查结果，其只读工具产生的 Evidence Receipt 保留子 Run 来源并归入根 Plan Run。
_Avoid_: Independent Session、Planner Agent、Permission Escalation

**Stale Plan**:
至少一个 Evidence Receipt 已无法针对当前 Repository State 重新验证的 Plan revision。Stale Plan 必须在 Plan Mode 中重新验证并产生新 revision，不能直接进行 Plan Handoff。
_Avoid_: Failed Plan、Rejected Plan

**Repository State**:
用户期望由仓库保存的持久状态，包括源码、配置、文档、依赖锁文件、既有用户文件，以及 Git 暂存区与历史。
_Avoid_: Workspace

**Mode Transition**:
用户通过 `/mode plan`、`/mode build`、显式 `--mode` 启动参数或未来等价的界面选择器改变 Session 协作模式的控制面事件。它不创建 Run、不调用模型，也不执行 Plan，只影响之后启动的 Run；普通对话内容、Agent 判断和工具调用都不能产生 Mode Transition。交互提示符必须显示当前模式。
_Avoid_: Prompt、Tool Call、Tool Approval
