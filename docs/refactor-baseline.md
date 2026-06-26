# 重构基线文档（Phase 1 保护网）

> 基线日期：2026-06-25
> 基线测试：Maven 全量测试 **79 通过，0 失败**（`mvn clean test`）。
> 本阶段目标：在修改生产结构前，固定当前系统的关键行为、事件顺序和失败语义。本阶段**不拆分类、不修改 HTTP API、不删除构造器**，只补测试、测试夹具和基线文档。

---

## 0. 契约原则（最重要）

**旧构造器签名本身不是长期契约**。后续重构允许删除/合并/改写 `DefaultAgentLoopService`、`SubAgentCoordinator`、`ContextWindowManager` 的任一构造器，**只要它们产生的业务行为不变量被契约测试守住即可**。

唯一被保护的是**外部可观察的业务行为**，不是：
- 私有方法名与内部节点顺序；
- 构造器参数个数或顺序；
- 字段数量与命名。

因此 §2 的 `AgentRuntimeTestFixture` 是测试与生产构造器之间的**唯一耦合点**：Phase 2 改构造器时，只需调整 Fixture，契约测试本身不动。

---

## 1. 目标类指标（基线快照 — Phase 7 完成后）

| 类 | 路径 | 行数 | 字段数 | 构造器数 | 备注 |
|---|---|---|---|---|---|
| `DefaultAgentLoopService` | `Loom_Agent-domain/.../agent/service/DefaultAgentLoopService.java` | **157** | **4** | **1**（package-private） | 只通过 `AgentLoopFactory` 创建 |
| `SubAgentCoordinator` | `Loom_Agent-domain/.../agent/service/SubAgentCoordinator.java` | **48** | **4** | **1**（5 参数） | |
| `ContextWindowManager` | `Loom_Agent-domain/.../agent/service/ContextWindowManager.java` | **163** | 2 | **2**（3/4 参数） | `noop()` 已删除 |
| `ResilientModelGateway` | `Loom_Agent-infrastructure/.../gateway/ResilientModelGateway.java` | 96 | — | — | Phase 6 已拆分 |
| `AgentCodeController` | `Loom_Agent-trigger/.../http/AgentCodeController.java` | 140 | — | — | Phase 6 已收缩 |

### Phase 7 清理摘要

| 操作 | 详情 |
|---|---|
| 删除废弃构造器 | `DefaultAgentLoopService` 8 个 + `SubAgentCoordinator` 4 个 + `ModelCallNode` 2 个 + `ToolDispatchNode` 2 个 + `RenderPromptNode` 1 个 |
| 删除 Legacy 实现 | `LegacyChildServiceFactory`、`legacyAssembly()`、nullable 回退逻辑 |
| 删除 `noop()` | `ContextWindowManager.noop()` 移除；测试改用真实 3 参数构造器 + InMemory 实现 |
| 降级逻辑内聚 | `fallbackDeepSummary()` 逻辑由 `DeepSummaryStrategy` 内部 catch 分支承担；`ContextWindowComponents` 始终提供非 null 的 `DeepSummaryStrategy` |
| 防御性编程 | 所有活跃构造器添加 `Objects.requireNonNull()`，不再用 null 隐式创建默认实现 |
| 测试迁移 | `AgentRuntimeTestFixture` 改为通过 `AgentLoopFactory` 和 5 参数 `SubAgentCoordinator` 构造器创建实例 |
| ArchUnit 防回归 | 新增 9 条架构规则，覆盖构造器数量/参数上限/依赖方向/package-private 可见性 |

---

## 2. 生产与测试构造调用点

### 生产调用点（`src/main`）

| 目标类 | 调用点文件 | 使用的构造器 |
|---|---|---|
| `DefaultAgentLoopService` | `AiRuntimeConfig#agentLoopService`（`Loom_Agent-app/.../config/AiRuntimeConfig.java:206`） | 最全 14 参数（Spring 注入全部依赖） |
| `DefaultAgentLoopService` | `SubAgentCoordinator#runChild`（`SubAgentCoordinator.java:224`） | 14 参数（子 Agent 服务，`subAgentCoordinator=null`） |
| `SubAgentCoordinator` | `AiRuntimeConfig#subAgentCoordinator`（`AiRuntimeConfig.java:237`） | 最全 13 参数 |
| `ContextWindowManager` | `AiRuntimeConfig#contextWindowManager`（`AiRuntimeConfig.java:149`） | 4 参数（含 `deepSummaryService`） |

### 测试调用点（`src/test`）

| 目标类 | 调用点文件 | 出现次数 | 现状 |
|---|---|---|---|
| `DefaultAgentLoopService` | `DefaultAgentLoopServiceTest.java` | **13** | 直接调用 5/6/9/10/12/13/14 参数构造器，散落各测试方法 |
| `SubAgentCoordinator` | `DefaultAgentLoopServiceTest.java` | 3 | 直接调用 9/13 参数构造器 |
| `ContextWindowManager` | `DefaultAgentLoopServiceTest.java` | 5 | 直接调用 3 参数构造器 |

> **迁移目标**：§2 完成后，上述测试调用点改为 `fixture()...buildAgentLoop()` / `buildSubAgentCoordinator()`，使 Phase 2 修改生产构造器时只需调整 Fixture。

---

## 3. 节点图（当前 `AgentNodeNames`）

```
start → planner → render_prompt → model_call → decision
                                                          ├─ approval_gate → tool_dispatch → observation → replan_guard → replan → (回 model_call)
                                                          ├─ tool_dispatch → observation → ...
                                                          ├─ sub_agent_dispatch → observation → ...
                                                          ├─ user_input_gate（暂停，等待用户输入）
                                                          ├─ replan → model_call（循环）
                                                          └─ final_answer → done（终态）
fail → done（终态，错误/超时/预算）
```

节点名常量（`AgentNodeNames`，共 14 个）：`start`, `planner`, `render_prompt`, `model_call`, `decision`, `approval_gate`, `tool_dispatch`, `sub_agent_dispatch`, `observation`, `replan_guard`, `replan`, `final_answer`, `user_input_gate`, `fail`。

> **不变量**：契约测试**不锁死完整内部节点顺序**，避免未来新增节点时测试无意义失败。只锁外部可观察事件顺序（见 §6）。

---

## 4. 8 个 HTTP 端点（`AgentCodeController`，`@RequestMapping("/api/v1/agent/code")`）

| # | 方法 | 路径 | 请求 | 响应/SSE 事件 |
|---|---|---|---|---|
| 1 | POST | `/ask/stream` | `AgentAskRequest`（question/message/workspace/maxSteps/includeTrace，`@Size`/`@Min`/`@Max` 校验） | SSE：`meta`/`plan_updated`/`replan_started`/`context_compacted`/`resume_started`/`sub_agent_*`/`approval_required`/`user_input_required`/`policy_denied`/`answer`/`done`/`error`（`includeTrace=false` 过滤内部 `node_start`/`thought`/`tool_call`/`observation`）；空体→`error:invalid_request`；未启用→`error:agent_disabled` |
| 2 | POST | `/approvals/{approvalId}/decide/stream` | `AgentApprovalDecisionRequest`（decision `@NotBlank`，reason `@Size(max=500)`） | SSE：恢复流事件；`decision` 非 APPROVE/REJECT→`error:invalid_request` 且不调领域服务 |
| 3 | GET | `/approvals/{approvalId}` | — | `Response<AgentApprovalResponse>`；存在→`SUCCESS`；不存在/过期→`ILLEGAL_PARAMETER`（"审批不存在或已过期"） |
| 4 | POST | `/runs/{runId}/resume/stream` | — | SSE：`resumeRun`，**过滤 `checkpoint_saved` 事件**；runId 空→`error:invalid_request` |
| 5 | POST | `/runs/{runId}/input/stream` | `AgentUserInputRequest`（action `@NotBlank`，message `@Size(max=4000)`） | SSE：用户输入恢复流；action 非 CONTINUE/ABORT→`invalid_request`；CONTINUE 缺 message→`invalid_request` |
| 6 | GET | `/runs/{runId}/trace` | — | `Response<AgentTraceTimelineResponse>`；runId 空→`ILLEGAL_PARAMETER`；run 不存在→"未找到 run"；trace 空→"未找到 trace" |
| 7 | GET | `/runs/{runId}/replay` | `?includeChildren`（query） | `Response<AgentReplayResponse>`；默认 `includeChildren=true`；空 timeline→`ILLEGAL_PARAMETER`（"未找到可 replay 的 trace"） |
| 8 | POST | `/runs/{runId}/replay/stream` | `?includeChildren`（query）+ `AgentReplayStreamRequest`（body） | SSE：`replay_started` → `replay_event*` → `replay_done`；`costGenerated=false`；query 参数优先于 body；异常→`error:replay_failed` |

**SSE 事件名规则**：`event.name(event.getType().eventName())` + `data(AgentStreamEvent, APPLICATION_JSON)`。`AgentEventType.eventName()` 返回小写蛇形（如 `node_start`、`approval_required`）。

**`includeChildren` 优先级**（`includeChildren(queryValue, request)`，第 617 行）：query param 非 null → 用 query；否则 request body 非 null → 用 body；否则默认 `true`。

---

## 5. 默认依赖的真实行为（基线）

| 默认依赖 | 类 | 真实行为 |
|---|---|---|
| `InMemoryApprovalStore` | `PersistentApprovalStore` 实现 | `ConcurrentHashMap` 存储；`find` 校验 `expired(Instant.now())`，过期则删除并返回空；`consume` = `find` 后删除 |
| `InMemoryAgentRunRepository` | `AgentRunRepository` 实现 | 内存 Map；`save`/`find`/`findChildren` |
| `InMemoryAgentCheckpointRepository` | `AgentCheckpointRepository` 实现 | 内存；`latest(runId)` 返回最新 checkpoint |
| `InMemoryTraceRecorder` | `TraceRecorder` 实现 | `ConcurrentMap<runId, List<AgentTraceEvent>>`；`timeline(runId)` 按 `sequenceNo` 升序；`timelineByTraceId` 跨 run 聚合并按 `createdAt` 排序；`recordNodeStart/End/Stop/ModelUsage/ModelGatewayEvent` 全部追加事件 |
| `NoopAgentMetrics` | `AgentMetrics` 实现 | 所有方法空实现 |
| `DefaultBudgetGuard` | `BudgetGuard` 实现 | `budget.enabled=false`（默认）时 `checkBeforeModelCall` 恒 allowed；`enabled=true` 时按 `usedTokens + estimatedInputTokens + reservedOutputTokens > maxTotalTokens` 拦截；`maxTotalCost>0` 时额外做成本预估拦截 |

---

## 6. 后续重构必须保持的行为不变量

### 6.1 Agent Loop 事件契约（`AgentEventType`，共 20 个）

`META, NODE_START, PLAN_UPDATED, REPLAN_STARTED, CONTEXT_COMPACTED, CHECKPOINT_SAVED, RESUME_STARTED, SUB_AGENT_STARTED, SUB_AGENT_COMPLETED, SUB_AGENT_FAILED, SUB_AGENT_SUMMARY, THOUGHT, TOOL_CALL, APPROVAL_REQUIRED, USER_INPUT_REQUIRED, POLICY_DENIED, OBSERVATION, ANSWER, DONE, ERROR`

不变量：
1. **正常执行**：`ANSWER` 出现在 `DONE` 之前；`DONE` 后不再产生业务事件（持久化副作用 `CHECKPOINT_SAVED` 除外，由 `CheckpointAgentHook` 在 `AFTER_NODE` 触发）；`runId`/`requestId`/`conversationId` 在整个流中持续一致。
2. **审批暂停**：写操作（`WRITE_CONFIRM` 级别）在审批前**不执行**；暂停流以 `APPROVAL_REQUIRED` 结束（其后只允许 `CHECKPOINT_SAVED`，不产生 `DONE`）。
3. **审批恢复**：恢复流首先包含 `RESUME_STARTED`；`APPROVE` 执行原工具并产生 `TOOL_CALL`+`OBSERVATION`；`REJECT` 产生含 `approval_rejected` 的 observation，继续到 final answer。
4. **Checkpoint 恢复**：恢复 workspace/step/context recovery 状态；不安全工具（`unsafeResumeRequired` 或 `APPROVAL_GATE`/`TOOL_DISPATCH` 节点）**不重复执行**，转 `REPLAN`；缺少 checkpoint 返回稳定错误码 `checkpoint_not_found`。
5. **用户输入恢复**：等待输入时产生 `USER_INPUT_REQUIRED`；`CONTINUE` 必须携带非空 message（否则 `invalid_user_input`）；`ABORT` 保持 `CONTEXT_OVERFLOW` 终止语义（`ERROR` code=`context_length_exceeded` + `DONE` stopReason=`CONTEXT_OVERFLOW`）。
6. **错误/超时**：未知节点、总超时、模型错误最终进入 `ERROR`/`DONE` 终态；`Flux` 必须正常 `complete()`，不能悬挂。

### 6.2 SubAgentCoordinator 契约

`dispatch(AgentContext)` 不变量：
- `sub_agent_disabled` / `sub_agent_depth_exceeded`：功能/深度前置拦截；
- 非法 JSON / 缺 `tasks` 数组 → `sub_agent_tasks_required`；空 tasks → `sub_agent_tasks_required`（"至少需要一个子任务"）；缺 question → `sub_agent_task_question_required`；
- tasks 数 > `subAgentMaxChildren`（默认 6）→ `sub_agent_too_many_tasks`；
- Editor 角色 + (tasks>1 或 maxConcurrency>1) → `sub_agent_editor_parallel_denied`；
- 实际并发数 ≤ `subAgentMaxConcurrency`（Semaphore 控制）；
- 总超时取消未完成任务，返回 `sub_agent_timeout` 结果；
- **部分成功**：`anySucceeded` 为真时整体 `success=true`；**全部失败** → `success=false`, `errorCode=sub_agent_all_failed`；
- 聚合 observation 保持任务**原始顺序**；observation 超 `subAgentSummaryMaxChars`（默认 12000）→ 截断并 `truncated=true`；
- 子 Agent **不继承**父 Agent 的动态过程文本（`DynamicText`）；
- 只读角色（`EXPLORER`/`REVIEWER`）通过 `RoleToolRegistryFactory.ReadOnlyAgentTool` 包装，写工具被 `HIGH_RISK_DENY` + `sub_agent_read_only_violation` 拦截。

### 6.3 ContextWindowManager 契约

- `enabled=false` 时 `prepareToolResult`/`compact*` 不修改 context 与 tool result，返回 `none`；
- `prepareToolResult`：observation > `persistToolResultChars`（默认 12000）→ 持久化为 `TOOL_RESULT` artifact，observation 替换为 `[context_artifact]` 引用，`truncated=true`，设置 `artifactId/originalChars/retainedChars/sha256`；
- `compactBeforePrompt` 策略链：`snip`（超过 `maxDynamicEntries`，保留 user_task + 最近 tail）→ `micro`（只压缩**旧的、已有 artifact 的** tool result）→ 超 `autoCompactTokenLimit` 触发 `summary`；
- `reactiveCompact(context, targetTokens)`：保留 `reactiveKeepRecentEntries`（默认 5）条最近记录；生成 `TRANSCRIPT` artifact；策略 `reactive_summary`；
- `deepSummaryCompact`：deep summary 成功 → 策略 `deep_summary`；失败/不可用 → 降级 `deep_summary_deterministic`；
- `ContextCompactResult` 元数据正确：`compacted/before/afterEstimatedTokens/artifactCount/strategies/targetTokens/fitsTarget/retainedEntryCount/transcriptArtifactId`；
- artifact **只能被同一 root run 访问**（`findByArtifactIdAndRootRunId` 校验，`ContextRecallTool` 跨 root run 返回 `context_recall_not_found`）。

### 6.4 Controller / SSE 契约

- `/ask/stream`：空请求 → `invalid_request`；未启用 → `agent_disabled`；`includeTrace=false` 过滤内部节点事件（`NODE_START`/`THOUGHT`/`TOOL_CALL`/`OBSERVATION`），仅保留 `shouldSend` 白名单；`includeTrace=true` 保留全部；
- 审批/恢复：`APPROVE`/`REJECT` 映射为 `ApprovalDecision` 枚举；非法 decision → `invalid_request` 且**不调用领域服务**；`resumeRun` 过滤 `CHECKPOINT_SAVED`；`CONTINUE`/`ABORT` 映射为 `UserInputAction`；
- 上游异常：只发送一次 `ERROR` 并完成连接（`fallbackError` → `agent_error`）；
- 查询端点：approval 存在/不存在；trace 成功/run 不存在/trace 为空；replay 默认含 child run；query 参数优先于 request body；
- replay SSE 顺序：`replay_started` → `replay_event*` → `replay_done`；`costGenerated=false`；异常 → `error:replay_failed`。

### 6.5 Gateway 契约（`ResilientModelGateway`）

- Budget preflight 拒绝（`budgetGuard != null && context != null` 且超预算）→ 返回 `BUDGET_EXCEEDED`，**delegate 不被调用**；
- fallback 模型预算不足 → 停止切换，返回 budget error；
- complete 降级后设置 `actualModel`（若空则设为 key.model）与 `fallbackReason`；
- stream 已输出 token（`tokenEmitted=true`）后**既不重试也不降级**；
- non-retryable 异常（`isNonRetryable`：401/402/422 或 `CONFIG_ERROR`/`INVALID_REQUEST`/`BAD_REQUEST`/`CONTEXT_OVERFLOW`/`MODEL_CALL_TIMEOUT`/`AUTHENTICATION_FAILED`/`INSUFFICIENT_BALANCE` 等）→ `releasePermission`，**不计入熔断失败**；
- complete 与 stream 使用**相同的错误分类（`isRetryable`）与 retry delay（`retryDelayMs`）规则**（策略语义一致，不要求内部实现一致）；
- deadline 不足（`!canWait`）→ 不等待下一次重试，返回 `MODEL_CALL_TIMEOUT`；
- Metrics 标签低基数：`loom_agent_model_call_total`/`_latency_seconds`/`_retry_total`/`_circuit_*` 仅用 `model`/`capability`/`status`/`error_code`/`state`，**禁止** `runId`/`traceId`/`requestId`/`conversationId`/`userId`/`workspace`/`question` 等高基数码。

---

## 7. 完成标准

- [x] 原有 79 个测试全部通过。（Phase 7 后为 160 个功能测试 + 9 个架构测试 = 169 个）
- [x] 新增契约测试全部通过且无固定时间等待（`Thread.sleep` 判断并发）。
- [x] Controller 8 个端点都有至少一个成功和一个失败场景。
- [x] `SubAgentCoordinator` 和 `ContextWindowManager` 有独立测试类。
- [x] 测试代码不再大面积直接调用 14 参数或 13 参数构造器（改走 Fixture）。
- [x] 未修改任何 HTTP、SSE 或领域接口。
- [x] 所有废弃构造器已删除。
- [x] God Class 职责拆分完成。
- [x] 形成后续阶段可直接使用的行为不变量清单（即本文 §6）。

### Phase 7 收口完成（2026-06-26）

- [x] 所有 `@Deprecated(forRemoval = true)` 构造器已删除
- [x] `LegacyChildServiceFactory`、`legacyAssembly()` 已删除
- [x] `ContextWindowManager.noop()` 已删除
- [x] `fallbackDeepSummary()` 逻辑已内聚到 `DeepSummaryStrategy`
- [x] 活跃构造器使用 `Objects.requireNonNull()`
- [x] ArchUnit 9 条架构规则全部通过
- [x] 文档已更新：`refactor-baseline.md`、`backend-architecture.md`
- [x] 除 `AgentLoopFactory` 外，不存在直接构造 `DefaultAgentLoopService` 的代码
- [x] `mvn clean verify` 全部通过（169 个测试）

---

## 8. 默认约定

- 本阶段继续使用项目现有 **JUnit 4** 风格（`org.junit.Test`），不同时升级测试框架。
- 新测试暂留在 `Loom_Agent-app` 模块，不同时调整 Maven 模块测试布局。
- 不追求覆盖率百分比，优先保护**恢复、并发、SSE、重试、压缩**等高风险路径。
- `spring-boot-starter-test`（test scope）已提供 MockMvc / Mockito / Jackson / JUnit4，无需新增依赖。
