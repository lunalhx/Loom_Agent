# Input-aware Shell permissions and lightweight sandboxing

## Problem Statement

Loom Agent currently assigns approval behavior statically at the tool level. That model is too coarse for `run_shell`: the same tool can perform a harmless repository read, modify the workspace, publish external state, or execute a catastrophic host operation. The current Plan Mode response is to omit Shell entirely because the existing `/bin/sh -c` runner has no enforceable filesystem, network, credential, or child-process boundary.

Users need Plan Mode to use familiar Shell-based exploration without authorizing implementation, and they need ordinary Build Runs to remain useful without silently inheriting the current host user's full authority. They also need permission prompts to describe the concrete command being authorized, reusable approvals to remain bounded, and an explicit bypass-like Full Access option when they knowingly accept host-level risk. This must be achieved with lightweight native isolation rather than containers, virtual machines, repository copies, or a general overlay filesystem.

## Solution

Replace static tool-level Approval Requirement with one input-aware Permission Policy shared by every mode and tool. Each concrete Tool Call receives a `PermissionDecision` of `ALLOW`, `ASK`, or `DENY` from a default action plus normalized, tool-specific Permission Rules. Keep authorization dimensions separate: Effect Profile describes possible effects, Execution Profile supplies enforceable capabilities, and Permission Decision controls whether the eligible call proceeds, asks the user, or stops. No numeric risk level participates in authorization.

Run all ordinary Shell calls inside a fail-closed native sandbox. Plan Shell receives repository-read, Run-scoped disposable-write, hidden host-private data, and no network. Ordinary Build Shell receives workspace-write, a disposable HOME, and no network, with bounded external filesystem access available only through user-approved Execution Grants. Delegate Runs inherit the stricter intersection of their parent and delegate profiles. Use Seatbelt on macOS and bubblewrap on Linux and WSL2; when the required backend cannot enforce the selected profile, Shell is unavailable rather than falling back unsandboxed.

Provide an explicit, launch-scoped Full Access selection only for root Build Runs. Full Access combines the host user's filesystem and network authority with an `ALLOW` default so ordinary commands may execute silently, while continuing to evaluate the shared tool allowlist, explicit Permission Rules, and recognizable Built-in Safety Floor rules. Its confirmation and persistent indicator must state that scripts, plugins, interpreters, and binaries can perform indirect behavior that command classification cannot constrain. Full Access is a deliberate host trust boundary, not a stronger sandbox guarantee.

## User Stories

1. As a user, I want permission decisions to depend on the concrete Tool Call, so that one multi-purpose tool does not have one misleading risk level.
2. As a user, I want Plan Mode, Build Mode, built-in tools, and external tools to use one Permission Policy, so that permission behavior is consistent across the product.
3. As a user, I want `ask`, `auto`, and `never` to be presets for the default `ASK`, `ALLOW`, and `DENY` actions, so that CLI choices do not create a second authorization system.
4. As a user, I want Effect Profile, Execution Profile, and Permission Decision to remain separate, so that approval cannot be mistaken for an enforceable capability boundary.
5. As a user, I want authorization to avoid a numeric risk scale, so that unrelated dimensions are not collapsed into an ambiguous ordering.
6. As a user, I want Plan Mode to expose `run_shell` when a native sandbox can enforce its boundary, so that planning can use familiar repository exploration commands.
7. As a user, I want Plan Shell to keep Repository State read-only, so that exploration cannot become implementation.
8. As a user, I want Plan Shell to write only to Run-scoped Disposable State, so that temporary command state cannot become a repository change.
9. As a user, I want Plan Shell to run offline, so that Shell cannot mutate external systems or disclose data over the network.
10. As a user, I want Plan Shell to hide host credentials and private configuration, so that repository exploration does not inherit my developer identity.
11. As a user, I want Plan Shell child processes to inherit the same sandbox, so that a command cannot escape its boundary by spawning another process.
12. As a user, I want Plan Shell to fail closed when native isolation is unavailable, so that the product never substitutes an unrestricted Shell silently.
13. As a user, I want command classification to influence permission prompts but not prove an Effect safe, so that parser confidence is not treated as a sandbox.
14. As a user, I want ordinary Build Shell to write inside the Workspace but remain offline by default, so that implementation has useful local authority without implicit network access.
15. As a user, I want ordinary Build Shell to use a disposable HOME, so that tools do not automatically read or modify my real home configuration.
16. As a user, I want a root Build Run to request a concrete external file or directory, so that a bounded task does not require Full Access.
17. As a user, I want each Execution Grant to specify read or write authority, so that external access is no broader than the task requires.
18. As a user, I want Execution Grants to support one-call, Session, and Workspace lifetimes, so that I can balance repeated prompts against durable trust.
19. As a user, I want Permission Grants and Execution Grants evaluated separately, so that approving a command does not also grant an external path.
20. As a user, I want Plan and Delegate Runs unable to request Execution Grants, so that restricted collaboration modes cannot escalate their filesystem authority.
21. As a user, I want project configuration unable to create Execution Grants, so that opening a repository cannot authorize host access.
22. As a user, I want external targets resolved to real regular files or directories, so that symlinks and special files cannot disguise a stronger capability.
23. As a user, I want sockets, devices, FIFOs, and other special files excluded from Execution Grants, so that a bounded path grant cannot approximate host control.
24. As a user, I want denied or missing Execution Grants to stop the call without an unsandboxed retry, so that failure cannot widen authority.
25. As a user, I want an Agent to declare minimal external filesystem needs before process start, so that approval precedes side effects.
26. As a user, I want an undeclared sandbox violation to terminate the call rather than expand and replay it, so that partial workspace changes are not duplicated.
27. As a user, I want a failed call that already changed the Workspace reported as partial success with affected paths, so that failure is not presented as atomic rollback.
28. As a user, I want workspace traversal and symlinks that resolve outside the canonical root treated as external paths, so that workspace authority cannot escape its root.
29. As a user, I want project permission rules to select only `ASK` or `DENY`, so that untrusted repository content can restrict but cannot grant execution authority.
30. As a user, I want user-local and bounded Session sources to grant authority, so that trust remains under my control rather than the repository's.
31. As a user, I want Built-in, Project, User-local, and Session rules to use the same evaluator, so that precedence is understandable and auditable.
32. As a user, I want compound Shell commands evaluated by executable unit, so that a harmless first command cannot conceal a dangerous later command.
33. As a user, I want overlapping rule matches resolved as `DENY > ASK > ALLOW`, so that a weaker rule cannot erase a stronger restriction.
34. As a user, I want Shell rules matched against normalized token prefixes rather than raw globs, so that quoting and control flow do not create accidental authorization.
35. As a user, I want incompletely parsed Shell input treated as opaque, so that only an exact full-call rule can match it.
36. As a user, I want broader Shell prefixes to require an explicit user-local rule, so that one approval cannot silently generate a wider policy.
37. As a user, I want an approval prompt to show the redacted normalized command and its executable units, so that I can inspect what will run.
38. As a user, I want an approval prompt to show the Workspace, Execution Profile, matching rule, source, and reason, so that I understand the applicable boundary.
39. As a user, I want unknown inline secrets redacted when recognized but the command otherwise reviewable, so that approval remains meaningful without persisting raw input.
40. As a user, I want unredacted Shell input excluded from traces, Sessions, and durable reports, so that authorization does not create a new secret store.
41. As a user, I want to allow once, allow for the Session, always allow in the Workspace, or deny a prompted call, so that approval lifetime is explicit.
42. As a user, I want reusable Permission Grants to match the complete normalized Tool Call, so that later calls do not inherit authority accidentally.
43. As a user, I want reusable Permission Grants bound to the same or a stricter Execution Profile, so that a Plan approval cannot authorize unsandboxed Build execution.
44. As a user, I want recognizable safe workspace-read calls eligible for built-in `ALLOW`, so that routine inspection does not always interrupt me.
45. As a user, I want recognizable high-impact actions to force `ASK` even under an `ALLOW` default, so that automation does not silently perform destructive or external actions.
46. As a user, I want recognizable catastrophic commands denied by the Built-in Safety Floor, so that obvious host-destruction requests cannot be authorized by another rule source.
47. As a user, I want direct privileged changes, destructive Git operations, recursive Workspace deletion, publication, deployment, external mutation, and download-then-execute to require approval, so that high-impact intent is visible.
48. As a user, I want direct root or home deletion, filesystem formatting, raw block-device writes, fork bombs, shutdown, and reboot denied, so that recognizable catastrophic calls stop before execution.
49. As a user, I want direct reads of recognizable repository secrets to warn that content may enter model context, so that disclosure is deliberate.
50. As a user, I want `.env.example` and equivalent clearly identified examples exempt from sensitive-resource prompting, so that documentation workflows remain usable.
51. As a user, I want sensitive-resource classification described as best-effort, so that scripts and binaries are not presented as fully inspected.
52. As a user, I want direct Full Access reads of host credential resources to require non-persistable per-call approval, so that recognizable credential disclosure is never silently made durable.
53. As a user, I want a Full Access option for root Build Runs, so that I can deliberately trade containment for unrestricted host capability.
54. As a user, I want Full Access enabled only through an explicit user control and confirmation, so that natural language, an Agent, a tool, or project configuration cannot activate it.
55. As a user, I want Full Access selection frozen when a root Build Run starts, so that authority cannot change midway through execution.
56. As a user, I want Full Access limited to the current Runtime launch and excluded from Session persistence, so that resume cannot recover host authority silently.
57. As a user, I want `/new` to return to the ordinary Workspace profile, so that a fresh Session does not inherit bypass authority.
58. As a user, I want a persistent `FULL ACCESS` indicator while it is selected, so that host-level authority remains visible.
59. As a user, I want Full Access to keep evaluating recognizable explicit `ASK` and `DENY` rules, so that bypass mode does not require a second permission engine.
60. As a user, I want the Full Access warning to state that arbitrary code has my host-user authority, so that best-effort command rules are not mistaken for a host safety guarantee.
61. As a user, I want Plan Shell reads to contribute Plan Evidence, so that a later Plan Handoff can detect stale repository assumptions.
62. As a user, I want known file, directory, search, and Git Shell forms to produce semantic Evidence Receipts, so that unrelated changes do not invalidate precise evidence.
63. As a user, I want unsupported, opaque, build, test, and project-script Shell calls to fall back to a whole-Repository Receipt, so that evidence remains conservative without syscall tracing.
64. As a user, I want compound Shell evidence to merge every executable unit's scope, so that one command chain cannot omit dependencies.
65. As a user, I want Runtime to avoid inferring evidence dependencies from stdout, so that command text cannot fabricate a trustworthy scope.
66. As a user, I want an empty Run-scoped HOME and cache location for Plan Shell, so that host settings and cache writes remain isolated.
67. As a user, I want to grant a specific artifact cache read-only for a Workspace, so that offline Plan commands can reuse already downloaded dependencies without exposing credentials.
68. As a user, I want host artifact cache grants to exclude settings, SSH material, cloud profiles, and credential-bearing configuration, so that cache reuse does not become identity reuse.
69. As a user, I want missing offline dependencies to fail explicitly, so that Plan Shell never turns cache absence into implicit network access.
70. As a user, I want every Shell Tool Call to own its process tree, so that child processes cannot leak beyond the call lifetime.
71. As a user, I want completion, cancellation, and timeout to terminate remaining descendants, so that abandoned work does not continue silently.
72. As a user, I want stdout and stderr drained into bounded buffers, so that excessive output cannot consume unbounded application memory.
73. As a user, I want concurrent Shell calls limited within a Run, so that one Run cannot create unbounded local process concurrency.
74. As a user, I want background processes unsupported by `run_shell`, so that long-lived services require an explicit future Job capability.
75. As a user, I want the lightweight sandbox not to claim strict hostile-code CPU, memory, or PID isolation, so that its availability protection is stated honestly.
76. As a user, I want permission sources parsed and validated atomically before a Run starts, so that a malformed restrictive rule cannot disappear silently.
77. As a user, I want an invalid permission source to reject the Run with its source and location, so that I can repair it without guessing the effective policy.
78. As a user, I want on-disk permission changes to apply only to the next Run, so that current authorization remains deterministic.
79. As a user, I want Tool Approvals made during a Run to affect later Tool Calls through an append-only overlay, so that explicit decisions take effect immediately without live policy reload.
80. As a user, I want Delegate Runs to inherit the root Permission Policy Snapshot and a stricter Execution Profile, so that delegation cannot widen authority.

## Implementation Decisions

- Remove static tool-level Approval Requirement rather than retaining a compatibility layer. Permission Policy owns a default `ALLOW`, `ASK`, or `DENY` action, with `auto`, `ask`, and `never` acting only as default-action presets.
- Preserve the existing separation between Tool Capability Envelope, trusted Call Effect Assessment, Effect Profile, collaboration-mode authorization, Effective Tool Catalog, and approval. Permission Policy evaluates only calls that remain eligible under earlier boundaries.
- Represent permission outcomes as `PermissionDecision(ALLOW | ASK | DENY, reason)` and retain an auditable matching reason and source. Do not introduce a numeric L0-L3 or risk-score authorization model; UI tags may summarize independent dimensions without participating in comparison.
- Compile Built-in, Project, User-local, and Session Permission Rule sources through one evaluator. Project Source may contain `ASK` and `DENY` only. User-local and bounded Session sources may grant `ALLOW`; no repository trust mode is introduced.
- Freeze an atomically validated Permission Policy Snapshot before a root Run invokes the model or a tool. Any invalid source rejects the Run without partial loading or fallback, including under Full Access. Delegates inherit the root snapshot; current-Run Permission Grants use a separate append-only overlay.
- Use tool-specific normalized match subjects. Shell input is parsed into executable units and token-prefix matched; compound input takes the strictest result across units and overlapping rules. Opaque input supports only a complete exact-call rule. Other tools use their natural canonical subjects, such as normalized paths or domains.
- Model reusable approvals as Permission Grants over the complete normalized Tool Call. Support one-call, current-Session, and user-local current-Workspace lifetimes; bind reusable grants to the original Execution Profile and allow reuse only under the same or a stricter profile. Never infer a broader prefix from an approval.
- Display an ephemeral Approval Display containing the redacted normalized command, parsed units, Workspace, Execution Profile, matching rule source, and reason. Persist only safe summaries; do not persist raw unredacted command input as a trace, Session artifact, or report.
- Ship an unrelaxable Built-in Safety Floor through the same rule language. Built-in `ALLOW` is limited to fully parsed, valid, workspace-confined reads. Recognizable privileged system changes, destructive workspace or Git operations, external mutations, publication, deployment, and download-then-execute force `ASK`. Recognizable root or home deletion, filesystem formatting, raw block writes, fork bombs, shutdown, and reboot force `DENY`.
- Treat the Built-in Safety Floor as a permission floor, not syscall containment. Native sandbox profiles provide enforceable capability bounds; Full Access command classification is best-effort for scripts, plugins, interpreters, and binaries.
- Apply one best-effort Sensitive Resource classifier to structured file tools and Shell input. Direct recognizable repository-sensitive reads force a disclosure warning and approval, with `.env.example`-style examples exempt. Session or Workspace reuse remains exact-call and profile-bound; project rules cannot grant it. Do not add per-file sandbox mediation or syscall observation for indirect reads.
- Implement ordinary Shell execution through shared native sandbox adapters: Seatbelt on macOS and bubblewrap on Linux and WSL2. The adapters must enforce canonical-root filesystem scopes, offline network policy, hidden host-private data, inherited child-process restrictions, and fail-closed availability. Containers, VMs, repository copies, and a general overlay filesystem are not part of this capability.
- Give Plan Shell a repository-read, Run-scoped disposable-write, empty-HOME, offline Execution Profile. Repository traversal and symlink targets outside the canonical root remain unauthorized host paths. Tool Approval cannot widen this profile.
- Give ordinary Build Shell a workspace-write, disposable-HOME, offline Execution Profile. Additional real-path-resolved regular files or directories require a separately evaluated Execution Request and Execution Grant with explicit read or write scope.
- Permit Execution Grants only for ordinary root Build Runs and only from the user. Support one-call, Session, and Workspace lifetimes. Plan, Delegate, Agent, and Project Source cannot grant them. Network, IPC, sockets, devices, FIFOs, and other special resources are unsupported.
- Require Execution Requests before process start. An undeclared or denied sandbox access terminates the call without automatic expansion or replay. Any continuation is a new Tool Call, and a failed call that changed the Workspace reports partial success and affected paths.
- Keep Plan, ordinary Build, and Delegate Shell offline in the initial capability. Structured External Read tools retain their separate Effect, Outbound Disclosure, and permission boundaries. Do not implement domain grants, a network proxy, or post-denial network escalation.
- Allow Plan Shell to read an explicit user-local Host Resource Grant for a Workspace artifact cache, mounted read-only. Keep real caches non-writable, place locks and metadata in Disposable State when a tool-specific adapter supports it, and keep settings and credential-bearing host resources hidden. Missing cache content fails offline.
- Derive Delegate Execution Profiles as the stricter intersection with the parent profile. Delegate Runs cannot activate Full Access or obtain Execution Grants.
- Make Full Access an explicit `DANGER_FULL_ACCESS` Execution Profile plus `ALLOW` default available only to root Build Runs. It uses the host user's filesystem and network authority while retaining the unified Permission Policy, tool allowlist, recognizable Safety Floor, and Shell Process Supervisor.
- Activate Full Access only through an explicit user control with a clear confirmation and persistent indicator. Freeze the selection at Run start, keep it out of Session persistence, require reselection after exit or resume, and reset `/new` to the ordinary Workspace profile. Natural language, Agent output, tools, approval prompts, delegates, and project configuration cannot enable it.
- State in Full Access confirmation that indirect behavior inside arbitrary code is not constrained by command classification. Direct recognizable host-credential reads require non-persistable per-call approval, but no guarantee is made for indirect equivalents.
- Own every Shell descendant through a Shell Process Supervisor. Propagate cancellation; terminate remaining descendants on normal completion, cancellation, or timeout; drain stdout and stderr to bounded buffers; and limit concurrent Shell calls per Run. Do not let background processes outlive `run_shell`.
- Do not claim strict CPU, memory, or PID quotas in the lightweight sandbox. Exact timeout, output-buffer, and concurrency values remain implementation planning details.
- Capture Plan Shell evidence before model-visible truncation or redaction. Trusted Shell Evidence Adapters provide semantic file, directory, search, Git, or equivalent Receipts; compound units merge scopes. A successful unsupported or opaque call records a whole-Repository Receipt. Do not infer scope from stdout or trace syscalls.
- Keep `run_shell` in the Effective Tool Catalog only when the current Run has at least one valid invocation under its frozen mode and available Execution Profile. Catalog visibility is not authorization; hidden, stale, or fabricated calls still pass through Runtime Gate.

## Testing Decisions

- Use two externally meaningful test seams and avoid adding lower-level seams merely to mirror implementation classes.
- The primary seam is the existing offline CLI end-to-end harness around `CliSessionService`. Drive the model/tool loop with a fake Model Gateway and assert user-visible output, approval prompts, persisted Run snapshots, effective tool availability, repository bytes, audit summaries, grant lifetimes, and Full Access lifecycle behavior. Existing `CliModeE2ETest`, `CliSessionServiceE2ETest`, `PlanModeBoundaryTest`, `SecurityContractTest`, and `ArtifactLeakScanTest` provide prior art.
- Through the CLI seam, cover the `ask`/`auto`/`never` presets; Project/User-local/Session rule composition; strictest-match behavior; exact Permission Grant reuse; profile binding; invalid-policy startup failure; Plan/Build/Delegate boundaries; Full Access activation and reset; redacted Approval Display; sensitive-read warnings; Execution Request denial and partial-success reporting; and Plan Evidence fallback behavior.
- The secondary seam is a native sandbox contract suite that launches real child processes against temporary workspaces through each supported platform adapter. Assert observable access results rather than generated policy text or adapter internals.
- Through the native sandbox seam, verify repository-read versus workspace-write behavior, Run-scoped disposable writes, empty HOME, hidden host credentials, offline network behavior, canonical-root and symlink boundaries, explicit regular-path grants, special-file denial, inherited restrictions in descendants, timeout/cancellation cleanup, no surviving background process, bounded output, and fail-closed behavior when a backend is unavailable.
- Run the same sandbox contract against Seatbelt on macOS and bubblewrap on Linux/WSL2 where the backend is available. Platform availability may control whether a backend-specific contract runs, but production behavior must still fail closed rather than silently skip isolation.
- Treat a test as good when it proves a user-observable authorization or containment outcome. Avoid assertions on parser class structure, sandbox profile string formatting, private helper calls, or internal collection layout.

## Out of Scope

- Sandboxed Shell network access, domain grants, network proxies, TLS interception, and post-denial network escalation.
- Native Windows Shell sandboxing outside WSL2.
- Containers, virtual machines, repository copies, a general overlay filesystem, or promotion of disposable changes into Repository State.
- Strict hostile-code CPU, memory, or PID isolation and a claim that the lightweight sandbox resists denial-of-service attacks.
- A long-lived Job manager or background processes that survive a `run_shell` Tool Call.
- Plan or Delegate Execution Grants, project-granted host capabilities, and Agent-initiated authority escalation.
- IPC, Unix socket, device, FIFO, raw block-device, Docker socket, and other special-resource grants.
- Automatic retries that expand permissions after a sandbox violation.
- Syscall or file-descriptor tracing for permission classification, Sensitive Resource detection, or Plan Evidence.
- Guaranteed detection of dangerous or sensitive behavior hidden inside scripts, plugins, interpreters, or binaries, especially under Full Access.
- Automatic conversion of a one-time approval into a Shell prefix rule.
- A repository trust state or backward-compatibility layer for static Approval Requirement.
- A numeric command risk scale used for authorization.
- Final values for Shell timeout, output limits, or concurrent-call limits; those remain implementation planning details.

## Further Notes

- This Spec supersedes only the Plan-v1 omission of Shell and the static Approval Requirement portions of the existing Plan Mode Spec. Plan identity, revision, submission, evidence freshness, and handoff semantics remain unchanged.
- The design follows the established separation between collaboration policy, permission prompts, and enforceable execution capabilities. The native sandbox is what makes Plan Shell safe for Repository State; command classification is primarily permission UX.
- Full Access is intentionally comparable to a bypass mode: it is useful precisely because it removes ordinary containment, and its warning must be honest about that tradeoff.
- The accepted domain vocabulary and ADRs 0040 through 0058 are normative inputs. Where older Plan-v1 documents conflict on Shell availability, Disposable Write, or static Approval Requirement, the newer decisions take precedence.
