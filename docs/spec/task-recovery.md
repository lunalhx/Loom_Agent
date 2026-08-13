## Problem Statement

Loom Agent currently exposes two different continuity behaviors under overlapping recovery language: reopening a Session starts a new root Run from persisted conversation context, while an internal continuation path can reopen an unfinished Run from AgentCheckpoint. The current persistence model also duplicates Conversation History and Working Memory across AgentSession, TaskCheckpoint, and AgentCheckpoint.

This makes interruption behavior difficult to explain and unsafe to extend. A process can disappear after a Tool may have produced Repository State or an external Effect but before a durable Tool Result exists. The next process cannot generally prove whether that Tool started or completed, especially for Shell and MCP calls. The current process-local execution guard does not prevent two processes from advancing the same unfinished Run, and recovery state corruption or persistence ordering can be mistaken for a missing checkpoint.

The product needs a recovery mechanism that preserves useful task continuity without claiming process continuation or exactly-once side effects, does not silently replay ambiguous Tools, does not regain authority, and has one clear source of truth for every durable state category.

## Solution

Separate Session Resume from Run Recovery.

Session Resume restores conversation continuity. When no unfinished root Run blocks the Session, the next user request creates a new Run with newly frozen Run state. Run Recovery instead preserves the identity of one unfinished logical Run and creates a new exclusive Attempt to continue it semantically from durable facts.

When Runtime detects that a non-terminal root Run has lost its valid Attempt, the Session enters Recovery Required. Detection performs no model or Tool work and blocks ordinary requests in that Session. The user must explicitly choose Recover or Run Abandonment. Recover creates a new Attempt only after ownership, checkpoint integrity, Workspace identity, frozen-contract compatibility, and recovery eligibility pass. Run Abandonment terminates the Run without rollback and preserves all durable facts and ambiguity.

Runtime does not automatically replay a Tool Call whose durable result is missing. Before adapter invocation, the active Attempt must persist a sanitized execution-window marker in the sole AgentCheckpoint type. A valid matching Tool Result becomes authoritative only when durably appended to Conversation History. A marker without a matching durable result becomes an Interrupted Tool Call. Recovery may make normally permitted safe observations, but unresolved outcomes enter Ambiguity Review. The user may supply facts, abandon the Run, or Continue with Ambiguity; none of these actions fabricates a trusted Tool Result or changes Tool permission. Every later Tool Call is a new call evaluated by the existing Effect, Execution Profile, and Permission Policy pipeline, including immediate execution when the result is `ALLOW`.

Conversation History is the append-only fact source for messages and durable Tool results. AgentCheckpoint is the only checkpoint type and stores Run/Attempt recovery state, frozen contracts, Working Memory Overlay, interruption state, budget, and an exact Conversation History anchor without copying full History or Session Working Memory. TaskCheckpoint is removed and no RunCheckpoint synonym is introduced. Only a normally completed root Run projects its Working Memory Overlay into Session Working Memory.

The recovered Run keeps its safety and semantic contracts frozen. Runtime, model, and provider may differ by Attempt and are audited, but capability can only remain equal or shrink. Full Access Runs remain nonrecoverable after process exit. Delegate Runs are not independent recovery units; the root Run owns the single recovery flow.

## User Stories

1. As a user reopening a Session, I want Loom Agent to distinguish conversation continuation from unfinished Run recovery, so that I know whether my next request creates a new Run or continues the interrupted one.
2. As a user whose Run was interrupted, I want the Session to show Recovery Required automatically, so that the unfinished task is not silently ignored.
3. As a user whose Session is in Recovery Required, I want no model or Tool work to start before I choose an action, so that recovery does not create hidden effects.
4. As a user whose Session is in Recovery Required, I want ordinary new requests blocked in that Session, so that two unfinished root Runs do not interleave one conversation and Workspace.
5. As a user who needs unrelated work while recovery is pending, I want to use another Session, so that resolving the interrupted Run is not bypassed inside its Session.
6. As a user choosing Recover, I want Loom Agent to preserve the existing `runId`, so that task identity, audit history, and prior effects remain attached to one logical Run.
7. As a user choosing Recover, I want Loom Agent to create a new Attempt, so that the new process ownership epoch is explicit and auditable.
8. As a user, I want at most one live Attempt to own a Run, so that two processes cannot both advance it or start duplicate Tools.
9. As a user, I want a healthy Attempt owner protected from forced takeover, so that opening another process cannot split the Run into competing writers.
10. As a user, I want an expired or released Attempt fenced from later writes, so that a stale process cannot corrupt recovered state.
11. As a user, I want a Tool prevented from starting when its execution-window marker cannot be durably saved, so that Runtime never executes an unrecorded ambiguous call.
12. As a user, I want a Tool Call with a durable result in Conversation History treated as completed even when the next AgentCheckpoint lags, so that recovery does not replay or relabel a known result.
13. As a user, I want a Tool Call that entered the execution window without a durable result shown as Interrupted Tool Call, so that uncertainty is explicit.
14. As a user, I want Interrupted Tool Calls never automatically replayed, so that Runtime does not blindly duplicate Repository State or external Effects.
15. As a user, I want recovery to re-observe verifiable current Repository State, so that semantic continuation is based on facts that survived the interruption.
16. As a user, I want unresolved Shell, MCP, external, and Delegate outcomes surfaced in Ambiguity Review, so that uncertainty is not rewritten as success or failure.
17. As a user in Ambiguity Review, I want to add relevant external facts, so that the continuing Run can consider information Runtime cannot observe.
18. As a user in Ambiguity Review, I want to abandon the Run, so that I can terminalize it without pretending its uncertain effects were resolved.
19. As a user in Ambiguity Review, I want to Continue with Ambiguity, so that work can proceed while the unknown outcome remains durable and visible.
20. As a user, I want Continue with Ambiguity to avoid retrying the old Tool or manufacturing a Tool Result, so that user consent is not confused with execution evidence.
21. As a user, I want every later Tool Call to use the existing permission pipeline, so that recovery does not create a second approval system.
22. As a user whose Permission Decision is `ALLOW`, I want a later new Tool Call to execute without a recovery-specific prompt, so that one permission model applies consistently.
23. As a user with a durable unanswered Tool Approval or user-input pause, I want the unresolved prompt re-presented after recovery, so that Runtime never assumes my answer.
24. As a user with an already durable Permission Grant, I want its original scope preserved, so that recovery neither loses nor expands an authorization I already made.
25. As a user choosing Run Abandonment, I want the Run to become terminal and unrecoverable, so that abandonment has an unambiguous meaning.
26. As a user choosing Run Abandonment, I want existing files, external Effects, Interrupted Tool Calls, and audit facts preserved, so that abandonment is not misrepresented as rollback.
27. As a user exiting an active recoverable Run, I want to choose between Run Suspension and Run Abandonment, so that exit does not ambiguously mean pause or permanent stop.
28. As a user choosing Run Suspension, I want the Attempt and its process tree stopped and the Run left non-terminal, so that a later explicit recovery remains possible.
29. As a user, I want unresolved in-flight work during suspension recorded as interrupted, so that stopping the process does not erase possible Effects.
30. As a Full Access user, I want a process-exited Run to be inspectable but not recoverable, so that launch-scoped host authority cannot silently return.
31. As a Full Access user attempting to exit an active Run, I want the choice limited to returning to the Run or abandoning it, so that the product does not promise an unrecoverable suspension.
32. As a user, I want a Full Access Run prevented from continuing as a sandboxed Attempt, so that one Run never mixes incompatible authority profiles.
33. As a user, I want recovery to require the same Workspace identity, so that an unfinished Run is not applied to a different workspace.
34. As a user, I want Repository State drift observed and surfaced without automatic rollback, so that my own or another process's changes are not destroyed.
35. As a Plan Mode user, I want existing Evidence Drift, conflict, and deviation rules preserved across recovery, so that a crash cannot erase a terminal safety result.
36. As a user, I want terminal Runs to remain immutable, so that success, failure, conflict, deviation, stop, or abandonment cannot later be reopened as recovery.
37. As a user, I want recovery to preserve the frozen Run Mode, Permission Policy and Grants, Execution Profile, Skill snapshots, Tool schema, Effect boundary, and recovery contract, so that semantics and authority do not drift between Attempts.
38. As a user, I want the actual Runtime, model, and provider recorded per Attempt, so that compatible changes remain auditable.
39. As a user, I want unavailable or incompatible Tools removed from a recovered Run rather than silently substituted, so that a same-name implementation cannot change semantics.
40. As a user, I want newly discovered Tools or Skills excluded from a recovered Run, so that recovery cannot expand capability.
41. As a user, I want obsolete checkpoint schemas rejected without migration or fallback, so that old state is not guessed into new semantics.
42. As a user, I want corrupt, missing, incompatible, or invalidly anchored latest recovery state reported as Recovery Blocked, so that storage damage is not mistaken for safe absence.
43. As a user in Recovery Blocked, I want inspection and Run Abandonment available, so that I can understand and close the Run without unsafe automatic repair.
44. As a user, I want Runtime never to silently select an older AgentCheckpoint, so that known later execution cannot disappear from recovery.
45. As a user, I want Conversation History to remain the authoritative record of messages and durable Tool Results, so that there is no competing transcript source.
46. As a user, I want AgentCheckpoint to be the only checkpoint type, so that recovery state is not duplicated across TaskCheckpoint and RunCheckpoint variants.
47. As a user, I want AgentCheckpoint to anchor rather than copy Conversation History, so that result ownership and crash ordering stay clear.
48. As a user, I want provisional Working Memory kept inside the unfinished Run, so that later Runs do not inherit unconfirmed deductions.
49. As a user, I want only a normally completed root Run to update Session Working Memory, so that failed, stopped, conflicted, deviated, or abandoned Runs do not pollute future context.
50. As a user whose root Run delegated work, I want already durable Delegate Results preserved, so that known child work is not lost.
51. As a user whose Delegate did not produce a durable parent-visible result, I want it shown as Interrupted Delegate Call, so that unknown child work is not treated as completed.
52. As a user, I want an interrupted child Run terminalized as `INTERRUPTED_WITH_PARENT`, so that it cannot create a nested independent recovery flow.
53. As a user, I want uncertain Delegate Effects included in the root Ambiguity Review, so that one recovery decision covers the task tree.
54. As a user, I want a recovered root Run allowed to create a new Delegate through ordinary permissions, so that semantic replanning remains possible without reconnecting the old child.
55. As a user, I want secret-bearing Tool inputs and results kept redacted in recovery artifacts, so that crash recovery does not weaken persistence security.
56. As a user, I want Loom Agent to describe the guarantee as non-replay rather than exactly-once execution, so that the product does not promise what Shell and external systems cannot guarantee.

## Implementation Decisions

- Preserve `Session`, `Run`, and `Attempt` as distinct identities. The existing Run is the logical recovery identity; do not introduce a duplicate `executionId`.
- Session Resume restores Session continuity and normally lets the next user request create a new root Run. It never silently takes ownership of an unfinished Run.
- Run Recovery is an explicit user control that keeps the original Run identity and creates a new Attempt.
- A Session with a lost Attempt on a non-terminal root Run enters blocking Recovery Required. Before explicit Recover, Runtime may inspect durable state but performs no model or Tool execution.
- Recovery control must check exclusive ownership, latest AgentCheckpoint integrity, Conversation History anchor validity, Workspace identity, frozen-contract compatibility, and Full Access eligibility before creating the new Attempt.
- Enforce one active writer with a durable fenced Attempt Lease. The exact lease duration, heartbeat cadence, and fencing representation are intentionally not decided by this Spec.
- Retain `AgentCheckpoint` as the sole immutable, versioned checkpoint type. Remove TaskCheckpoint and do not add RunCheckpoint as either a second type or synonym.
- Change AgentCheckpoint directly to the new schema without an old-schema reader, migration, fallback, or parallel persistence path.
- AgentCheckpoint owns Run/Attempt recovery state, frozen contracts, Working Memory Overlay, budget, Interrupted Tool Call, Ambiguity Review, and an exact Conversation History anchor. It does not copy full Conversation History or Session Working Memory.
- Conversation History is the append-only fact source for user/model messages and durable Tool Call/Tool Result facts, with accurate Run and Attempt identity.
- Session Working Memory is the cross-Run memory source. An unfinished Run keeps only a Working Memory Overlay in AgentCheckpoint. Only normal root completion projects that overlay into Session Working Memory; other terminal outcomes do not project it.
- Tool execution follows one durability order: persist a sanitized execution-window marker in AgentCheckpoint; invoke the adapter; append the sanitized Tool Result durably to Conversation History; then advance AgentCheckpoint to a History anchor containing that result.
- Adapter invocation is forbidden when the execution-window marker cannot be persisted.
- A valid matching Tool Result in durable Conversation History is authoritative when AgentCheckpoint lags. A marker without a matching durable result becomes Interrupted Tool Call.
- The execution-window marker and recovery evidence must stay redacted. A future adapter-specific reconciliation feature must provide a safe stable operation or resource identity rather than persisting raw secrets.
- Runtime never automatically invokes an Interrupted Tool Call again. It may perform newly authorized observations and create later new Tool Calls, but the old call remains an immutable interruption fact.
- Ambiguity Review is separate from Tool permission. Continue with Ambiguity neither retries a Tool nor grants authority; every later call passes through the existing Effect, Execution Profile, and Permission Policy gates.
- Preserve durable unresolved Tool Approval and user-input pauses as pending states and re-present them. Preserve already durable grants only within their original scope.
- Run Abandonment is terminal, performs no Tool or model work, preserves existing state/effects/audit facts, and performs no rollback.
- Run Suspension is available only for recoverable profiles. It stops the Attempt/process tree, leaves the Run non-terminal, and records unresolved calls as interrupted.
- Full Access remains launch-scoped and nonrecoverable after process exit. Its Recovery Required state is inspect/abandon-only, it cannot recover in sandbox, and active Full Access exit does not offer Run Suspension.
- Freeze Run Mode, Permission Policy and Grants, Execution Profile, Skill Catalog and Active Skill snapshots, allowed Tool schema, Effect boundary, and recovery contract across Attempts.
- Permit a different model, provider, and compatible Runtime for a new Attempt, recording the actual identities for audit.
- Recovery capability can remain equal or shrink. Missing or incompatible Tools are removed; same-name semantic drift cannot substitute an implementation; new Tools and Skills cannot join the Run.
- Require the same Workspace identity. Observe current Repository State and retain existing Plan Evidence drift/conflict/deviation behavior; recovery does not roll back Workspace changes.
- Terminal Runs are immutable and never enter Run Recovery.
- The root Run is the only recovery unit. Durable Delegate Results survive; a child without a durable parent-visible result becomes Interrupted Delegate Call and terminates as `INTERRUPTED_WITH_PARENT`; uncertain child Effects enter the root Ambiguity Review.
- Missing, corrupt, incompatible, or invalidly anchored latest recovery state enters Recovery Blocked. Runtime allows inspection and Run Abandonment but does not silently fall back to an older checkpoint, migrate state, or infer repair through a model.
- Keep physical record layout, record encoding, lease timing, CLI command spelling, UI copy, retention/garbage collection, and adapter-specific reconciliation contracts out of this decision set.

## Testing Decisions

- Use the existing CLI Session end-to-end boundary as the primary seam. Tests exercise product controls and user-visible recovery states while connecting real file-backed Session, Run, AgentCheckpoint, Conversation History, and trace persistence with controllable model and Tool adapters.
- Prefer external behavior assertions: Run and Attempt identity, visible control state, whether a Tool was or was not invoked, durable History facts, available user choices, effective authorization, terminal status, and state observed after reopening the Session. Do not bind broad E2E tests to private node sequencing or incidental serialized field order.
- Simulate interruption and persistence failure at durability boundaries through controllable test failures: before the execution-window marker, after the marker, after a Tool effect, after durable History result, and before the following AgentCheckpoint. Assert that no unrecorded Tool starts, known results are not replayed, and unknown results become Interrupted Tool Call.
- At the CLI Session seam, cover Recovery Required gating, explicit Recover, Run Abandonment, Run Suspension, Ambiguity Review, Continue with Ambiguity, pending approval/user-input re-presentation, Full Access inspect/abandon-only behavior, Workspace mismatch, capability shrink, Delegate interruption propagation, Working Memory projection, and terminal Run immutability.
- At the same seam, reopen the Session with compatible Runtime/model/provider changes and assert that frozen safety/semantic contracts remain unchanged while per-Attempt runtime identity is audited.
- Add narrow repository contract tests only where the E2E seam cannot deterministically prove the invariant: fenced Attempt Lease exclusion and stale-writer rejection, concurrent checkpoint allocation, corruption/incompatible-schema/invalid-anchor fail-closed behavior, and writer-level redaction.
- Use concurrency tests to prove that one healthy Attempt cannot be taken over and that an expired/released owner is fenced after a new Attempt starts. Do not test a particular TTL or token representation unless implementation planning later makes it part of the public repository contract.
- Use fault-injection tests rather than manually editing a completed Run back to running. A passing recovery suite must exercise real ordering failures and reopening behavior.
- Existing CLI Session E2E tests are prior art for Session resume, workspace validation, and durable artifact consistency. Existing durable Skill continuation tests are prior art for same-Run frozen-state restoration but must be strengthened beyond reconstructed fixtures. Existing snapshot contract, plan transaction recovery, and artifact leak tests are prior art for schema rejection, transactional recovery, and redaction.

## Out of Scope

- Exactly-once Tool or external Effect execution.
- Automatic replay of Interrupted Tool Calls.
- General effect journal or universal recovery capability taxonomy in v1.
- Universal Shell, MCP, or external-provider reconciliation.
- Automatic rollback, compensation, or Workspace rewind.
- Reconnecting to arbitrary Shell processes, process trees, sockets, streams, or program counters after process exit.
- Persisted background jobs that outlive a Tool Call.
- Recovery of child Runs or nested user-facing recovery flows.
- Recovery of Full Access Runs or same-Run downgrade from Full Access to sandbox.
- Checkpoint time travel, user-selected fallback to older checkpoints, recovery forks, or follow-up-on-resume variants.
- Backward-compatible checkpoint readers, migrations, aliases, or legacy TaskCheckpoint/RunCheckpoint paths.
- Adding new Tools or Skills to an interrupted Run.
- Physical storage layout, lease TTL and heartbeat tuning, fencing-token representation, exact schema field names, hashing algorithm, command names, UI wording, retention policy, and temporary-artifact cleanup mechanics.
- Adapter-specific idempotency keys, provider lookup APIs, and state-verifiable mutation reconciliation; these may be designed later without changing the v1 non-replay contract.

## Further Notes

- This Spec uses the repository glossary terms Session, Session Resume, Run, Run Recovery, Attempt, Attempt Lease, AgentCheckpoint, Conversation History, Session Working Memory, Working Memory Overlay, Recovery Required, Recovery Blocked, Interrupted Tool Call, Interrupted Delegate Call, Ambiguity Review, Continue with Ambiguity, Run Suspension, and Run Abandonment.
- ADR 0060 is superseded by the accepted recovery decisions in ADRs 0061, 0065, 0066, and 0069. ADRs 0061 through 0069 collectively define the identity, non-replay, UX gating, permission, compatibility, persistence ownership, lifecycle, Delegate, and Full Access boundaries used here.
- Current code still contains TaskCheckpoint and duplicates Conversation History/Working Memory in AgentContextSnapshot. Those are current-state facts to remove, not compatibility constraints to preserve.
- The user-facing safety promise is deliberately narrow: Loom Runtime will not automatically replay an Interrupted Tool Call. Without a shared transaction, provider idempotency key, or trusted reconciliation query, the product cannot guarantee exactly-once external Effects or prevent a later independently authorized call from being semantically equivalent.
