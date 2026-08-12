# Loom Agent

Loom Agent 是一个在本地工作区中与用户协作完成编码任务的 Agent。这里统一记录其交互与运行领域中的核心语言。

## Language

**Session**:
绑定一个工作区的持久对话，承载跨多次用户请求延续的模式、对话上下文、记忆和 Plan 集合。Plan 作为 Session 的独立字段持久化，不进入 conversation ledger，也不要求独立物理 Catalog。
_Avoid_: Run、Task

**New Session**:
用户通过 `/new` 创建的独立对话。Runtime 分配新的 Session 身份，初始化空的对话历史、工作记忆、checkpoint、Plan 历史和 Current Plan，并继承当前 Session 的 Build Mode 或 Plan Mode；该模式初始化不是旧 Session 的 Mode Transition。该控制事件不创建 Run、不调用模型，也不修改或删除此前 Session，旧 Session 仍可显式恢复。产品不提供 `/reset` 或其兼容别名。
_Avoid_: Reset、New Plan、Clear History

**Run**:
Session 内对一次用户请求的处理过程；它在启动时冻结 Session 当前模式为 Run Mode Snapshot，后续请求属于新的 Run，但可以继承同一 Session 的对话上下文。
_Avoid_: Session

**Run Mode Snapshot**:
根 Run 启动时从 Session 当前 Build Mode 或 Plan Mode 复制的不可变模式值，决定该 Run 的 Prompt、Effective Tool Catalog 与 Call Effect 策略，并写入 Run 审计记录。Delegate Run 继承父 Run 的 Snapshot；Run 启动后的 Mode Transition 只影响后续 Run，不能改变进行中的 Run。
_Avoid_: Session Mode、Mode Transition、Dynamic Policy

**Permission Policy Snapshot**:
根 Run 启动前从 Runtime Built-in、User-local 与 Project Source 原子编译得到的不可变基础权限视图。任一来源存在语法或校验错误时，该 Run 在调用模型或工具前 fail closed，并报告来源与位置；不忽略错误规则，也不部分加载。磁盘上的规则变化只影响后续 Run，Delegate Run 继承根 Run 的 Snapshot；当前 Run 内用户批准产生的 Permission Grant 通过独立的 append-only overlay 立即参与后续 Tool Call 求值。
_Avoid_: Live Policy Reload、Partial Rule Set、Silent Fallback

**Tool Approval**:
用户对 Permission Decision 为 `ASK` 的工具调用是否放行的决定。用户通过 Approval Display 审查调用；是否请求 Tool Approval 与该调用的 Effect Profile 是两个独立维度，且任何批准都不能放宽 Plan Mode 或 Execution Profile 的约束。
_Avoid_: Plan Approval、Plan Acceptance

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
用户在当前 Runtime launch 中为后续根 Build Run 显式选择的 `DANGER_FULL_ACCESS` Execution Profile 与默认 `ALLOW` Permission Action 组合。它允许普通工具调用和 Shell 命令在主机当前用户的文件系统与网络权限下静默执行，并继续求值工具 allowlist、显式 `ASK`/`DENY` 规则与 Built-in Safety Floor；但任意脚本、插件或二进制的内部行为不受 sandbox 约束，命令规则无法提供间接行为的主机安全保证。选择在 Run 启动时冻结，不写入 Session，Plan Mode、Delegate Run、Agent、项目配置和 Tool Approval 都不能启用或继承它。
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
Plan Mode 中由 Agent 明确提交的、具有独立身份与修订历史的持久 Session Artifact。它由 Runtime 管理的结构化元数据与 Agent 提交的完整 Plan Document 组成，作为 AgentSession 中独立于 conversation ledger 的字段集合持久化；不设置 Draft 状态，也不是可执行步骤图或工作流状态机。一个 Session 可以保存多个 Plan；普通答疑、澄清和阶段性分析不是 Plan。Plan 的产出会完成当前 Run，但不会授权执行，也不会使 Run 进入等待审批状态。
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
