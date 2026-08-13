# Architecture Decision Records

This directory is an append-only log of architectural forks. It is not the current product description.

**Current truth:** [`CONTEXT.md`](../../CONTEXT.md) and [`docs/spec/`](../spec/).

Read this index when reversing or adding a fork. Do not glob the ADR folder for ordinary implementation work. Do not add an ADR unless the glossary or an accepted spec would have to contradict itself; update those documents instead. Wire encodings, parsers, and field lists belong in the spec.

## Accepted heads

Grouped by the living document that now carries the decision.

### Plan Mode — [`docs/spec/plan-mode.md`](../spec/plan-mode.md)

| ADR | Decision |
|-----|----------|
| [0001](0001-model-plan-mode-as-a-session-mode.md) | Plan Mode is a persistent Session mode |
| [0002](0002-plan-mode-outranks-tool-approval.md) | Plan constraints outrank Tool Approval |
| [0004](0004-compose-plan-mode-as-a-restrictive-permission-layer.md) | Plan Mode is a restrictive permission layer |
| [0005](0005-reserve-mode-transitions-for-explicit-user-controls.md) | Mode Transition is an explicit user control |
| [0006](0006-model-plan-as-a-versioned-session-artifact.md) | Plan is a versioned Session Artifact |
| [0007](0007-keep-one-current-plan-per-session.md) | One Current Plan per Session |
| [0008](0008-require-an-explicit-plan-submission-action.md) | Plan Submission is an explicit action |
| [0009](0009-keep-mode-transition-separate-from-run-start.md) | Mode Transition does not start a Run |
| [0010](0010-bind-plans-to-build-runs-only-through-explicit-handoff.md) | Handoff is explicit |
| [0011](0011-block-plan-handoff-when-the-plan-basis-is-stale.md) | Stale Plan cannot be handed off |
| [0012](0012-capture-plan-basis-from-runtime-evidence.md) | Plan Basis comes from Runtime evidence |
| [0018](0018-allow-controlled-external-reads-in-plan-mode.md) | Controlled External Reads are allowed |
| [0019](0019-make-plan-submission-a-terminal-protocol-action.md) | Submission terminates the Plan Run |
| [0020](0020-fix-the-plan-target-at-run-start.md) | Plan Target is fixed at Run start |
| [0021](0021-store-plans-as-versioned-markdown-documents.md) | Plans are versioned Markdown documents |
| [0022](0022-keep-only-complete-plan-revisions.md) | Only complete revisions are kept |
| [0023](0023-bind-handoffs-to-plan-decisions-not-literal-steps.md) | Handoff binds decisions, not steps |
| [0024](0024-keep-plan-revisions-linear.md) | Revisions are linear |
| [0025](0025-use-explicit-mode-and-plan-cli-commands.md) | Explicit mode and Plan CLI commands |
| [0026](0026-replace-reset-with-new-session.md) | `/new` replaces reset |
| [0027](0027-propagate-plan-restrictions-and-evidence-through-delegates.md) | Delegates inherit Plan restrictions and evidence |
| [0028](0028-project-effective-tools-without-trusting-the-catalog.md) | Catalog is not the trust boundary |
| [0029](0029-freeze-collaboration-mode-at-run-start.md) | Collaboration mode is frozen at Run start |
| [0030](0030-revalidate-plan-basis-before-submission.md) | Revalidate Plan Basis before Submission |
| [0031](0031-embed-plans-in-agent-session.md) | Plans live in AgentSession |
| [0033](0033-layer-plan-shell-policy-from-general-sandboxing.md) | Plan policy layers on shared sandboxing |
| [0034](0034-capture-plan-evidence-as-revalidatable-receipts.md) | Evidence is captured as revalidatable receipts |
| [0035](0035-reject-plan-submission-after-evidence-drift.md) | Same-Run Evidence Drift rejects Submission |
| [0036](0036-inherit-and-refresh-plan-basis-across-revisions.md) | Basis is inherited and refreshed across revisions |
| [0038](0038-define-plan-submission-wire-contract.md) | Dedicated `<plan_submission>` protocol action |
| [0039](0039-define-plan-deviation-wire-contract.md) | Dedicated `<plan_deviation>` protocol action |

Wire encodings for 0038 and 0039 live in the Plan Mode spec.

### Effects — [`CONTEXT.md`](../../CONTEXT.md)

| ADR | Decision |
|-----|----------|
| [0013](0013-separate-call-effects-from-approval.md) | Call effects are separate from approval |
| [0014](0014-authorize-call-effects-before-execution.md) | Authorize effects before execution |
| [0015](0015-bound-call-effects-by-runtime-capabilities.md) | Effects are bounded by runtime capabilities |
| [0032](0032-model-effects-as-a-composable-profile.md) | Effects are a composable profile |

### Shell and permissions — [`docs/spec/shell-permissions.md`](../spec/shell-permissions.md)

| ADR | Decision |
|-----|----------|
| [0040](0040-run-plan-shells-in-a-fail-closed-native-sandbox.md) | Plan shells run in a fail-closed native sandbox |
| [0041](0041-use-one-input-aware-permission-policy.md) | One input-aware permission policy |
| [0042](0042-keep-project-permission-rules-restrictive.md) | Project permission rules stay restrictive |
| [0043](0043-show-redacted-shell-commands-in-approval-prompts.md) | Approval prompts show redacted Shell commands |
| [0044](0044-bind-reusable-approvals-to-exact-input-and-execution-profile.md) | Reusable approvals bind exact input and profile |
| [0045](0045-match-permission-rules-on-normalized-tool-input.md) | Rules match normalized tool input |
| [0046](0046-enforce-an-unrelaxable-built-in-safety-floor.md) | Built-in safety floor cannot be relaxed |
| [0047](0047-use-semantic-shell-evidence-with-a-whole-repository-fallback.md) | Semantic Shell evidence with repository fallback |
| [0048](0048-isolate-plan-shell-home-and-opt-in-host-caches.md) | Isolate Plan Shell HOME; host caches are opt-in |
| [0049](0049-sandbox-shells-by-default-and-make-full-access-explicit.md) | Sandbox by default; Full Access is explicit |
| [0050](0050-make-full-access-launch-scoped.md) | Full Access is launch-scoped |
| [0051](0051-use-scoped-execution-grants-for-build-escalation.md) | Scoped Execution Grants for Build escalation |
| [0052](0052-require-execution-access-before-process-start.md) | Execution access is required before process start |
| [0053](0053-keep-sandboxed-shell-offline-in-the-initial-capability.md) | Sandboxed Shell stays offline initially |
| [0054](0054-limit-initial-execution-grants-to-regular-filesystem-paths.md) | Initial grants are regular filesystem paths |
| [0055](0055-ask-before-recognizable-sensitive-resource-reads.md) | Ask before recognizable sensitive reads |
| [0056](0056-supervise-shell-process-lifetime-without-heavy-resource-isolation.md) | Supervise process lifetime without heavy isolation |
| [0057](0057-treat-full-access-as-an-explicit-host-trust-boundary.md) | Full Access is an explicit host trust boundary |
| [0058](0058-freeze-an-atomically-validated-permission-policy-per-run.md) | Permission Policy is frozen per Run |

### Skills — [`docs/spec/agent-skills.md`](../spec/agent-skills.md)

| ADR | Decision |
|-----|----------|
| [0059](0059-treat-skill-activation-as-a-dedicated-control-action.md) | Skill Activation is a dedicated control action |

### Recovery — [`docs/spec/task-recovery.md`](../spec/task-recovery.md)

| ADR | Decision |
|-----|----------|
| [0061](0061-distinguish-session-resume-from-same-run-recovery-attempts.md) | Session Resume is not Run Recovery |
| [0062](0062-recover-semantically-without-replaying-interrupted-tool-calls.md) | Recover without replaying interrupted Tool Calls |
| [0063](0063-gate-an-interrupted-session-on-explicit-recover-or-abandon.md) | Interrupted Sessions gate on Recover or Abandon |
| [0064](0064-keep-recovery-ambiguity-separate-from-tool-permission.md) | Recovery ambiguity is not Tool permission |
| [0065](0065-recover-across-compatible-runtime-contracts-with-shrinking-capability.md) | Compatible contracts; capability may only shrink |
| [0066](0066-use-one-agent-checkpoint-over-authoritative-conversation-history.md) | One AgentCheckpoint; Conversation History stays authoritative |
| [0067](0067-distinguish-recoverable-suspension-from-terminal-abandonment.md) | Suspension is recoverable; abandonment is terminal |
| [0068](0068-recover-only-the-root-run-and-terminalize-in-flight-delegates.md) | Recover the root Run; terminalize in-flight Delegates |
| [0069](0069-keep-full-access-runs-nonrecoverable-after-process-exit.md) | Full Access Runs are not recoverable after process exit |

## Superseded

Abandoned paths are kept as tombstones under [`superseded/`](superseded/) so they are not rediscovered as current options.

| ADR | Replaced by |
|-----|-------------|
| [0003](superseded/0003-allow-disposable-validation-artifacts-in-plan-mode.md) | [0033](0033-layer-plan-shell-policy-from-general-sandboxing.md) |
| [0016](superseded/0016-run-plan-shells-in-a-disposable-workspace.md) | [0033](0033-layer-plan-shell-policy-from-general-sandboxing.md) |
| [0017](superseded/0017-scope-disposable-workspaces-to-a-plan-run.md) | [0033](0033-layer-plan-shell-policy-from-general-sandboxing.md) |
| [0037](superseded/0037-omit-shell-from-plan-v1.md) | [0040](0040-run-plan-shells-in-a-fail-closed-native-sandbox.md) |
| [0060](superseded/0060-persist-frozen-skill-and-authorization-for-run-continuation.md) | [0061](0061-distinguish-session-resume-from-same-run-recovery-attempts.md), [0065](0065-recover-across-compatible-runtime-contracts-with-shrinking-capability.md), [0066](0066-use-one-agent-checkpoint-over-authoritative-conversation-history.md), [0069](0069-keep-full-access-runs-nonrecoverable-after-process-exit.md) |
