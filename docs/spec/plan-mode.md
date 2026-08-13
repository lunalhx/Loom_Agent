> Shell availability and static Approval Requirement sections in this Plan v1 Spec are superseded by the accepted Input-aware Shell permissions and lightweight sandboxing Spec. All other Plan Artifact, revision, evidence freshness, and handoff semantics remain in force.

## Problem Statement

Loom Agent currently has one collaboration behavior: each user request starts a Run that may inspect and modify the workspace according to the Session's base tool and approval policy. A user who wants the Agent to investigate first has no durable, explicit Plan Mode, no first-class Plan history, and no reliable boundary preventing analysis from becoming implementation. Tool Approval and the current static `risky` flag also cannot express the difference between approval policy and the concrete effects of one invocation.

Users need a persistent Plan Mode in which they can investigate, discuss, revise, and preserve a proposal without changing Repository State or external business state. They also need an explicit, reproducible handoff from an exact Plan revision into a later Build Run, including detection when the Repository State on which the Plan depended has changed.

## Solution

Add two persistent Session collaboration modes: Build Mode and Plan Mode. Build Mode preserves normal implementation behavior. Plan Mode is a restrictive, non-overridable authorization layer that supports structured repository reads and separately authorized structured External Reads, but forbids repository mutation, external mutation, disposable writes, and Shell execution in v1.

Make a Plan a first-class, versioned Session Artifact with an explicit submission protocol, one Current Plan, linear revision history, Plan Basis, and Plan Evidence captured by Runtime-owned Evidence Receipts. A user explicitly switches modes and explicitly hands an exact, fresh Plan revision to a new Build Run. Producing or selecting a Plan never starts implementation by itself.

## User Stories

1. As a user, I want a Session to remain in Plan Mode across multiple requests, so that I can investigate and discuss a change without repeatedly re-entering the mode.
2. As a user, I want to switch explicitly between Plan Mode and Build Mode, so that ordinary conversation cannot silently widen or narrow Agent authority.
3. As a user, I want the active mode displayed in the CLI prompt, so that I always know whether implementation is permitted.
4. As a user, I want a startup mode option, so that I can begin a Session in the intended collaboration mode.
5. As a user, I want entering or leaving a mode to avoid starting a Run, so that a permission change is not mistaken for a task request.
6. As a user, I want a running Run to retain the mode it started with, so that concurrent Session changes cannot alter its permissions midway.
7. As a user, I want Tool Approval to remain separate from Plan Mode authorization, so that approving a call cannot accidentally authorize implementation while planning.
8. As a user, I want Plan Mode to preserve Repository State, so that investigation cannot modify source, configuration, documentation, user files, the Git index, or Git history.
9. As a user, I want Plan Mode to forbid external business mutations, so that planning cannot send messages, create tickets, push commits, publish packages, or modify cloud resources.
10. As a user, I want permitted structured External Reads to remain available under my existing disclosure and approval policy, so that planning can research information without gaining new data-sharing authority.
11. As a user, I want `run_shell` unavailable in Plan v1, so that the unrestricted shell cannot bypass the hard planning boundary.
12. As a user, I want Plan Mode to expose only tools with at least one valid Plan invocation, so that the model receives an accurate view of its effective capabilities.
13. As a user, I want Runtime to authorize every tool invocation even when the model fabricates or uses a stale tool definition, so that the presented tool catalog is not the security boundary.
14. As a user, I want unclear or incomplete Effect classification to fail closed, so that unknown behavior is not treated as safe.
15. As a user, I want a normal answer in Plan Mode to remain an ordinary answer, so that questions and intermediate research do not create Plans accidentally.
16. As a user, I want the Agent to submit a Plan through an explicit protocol action, so that Runtime can distinguish a complete proposal from Markdown discussion.
17. As a user, I want each submitted Plan to have stable identity and revision metadata assigned by Runtime, so that Plans can be referenced and audited reliably.
18. As a user, I want Plan content to remain readable Markdown with a title and declared dependencies, so that it is useful without becoming a workflow engine.
19. As a user, I want every persisted revision to be complete and self-contained, so that Current Plan never points at an incomplete Draft.
20. As a user, I want multiple historical Plans in one Session but only one Current Plan, so that sequential work is supported without ambiguous simultaneous active plans.
21. As a user, I want to start a New Plan explicitly, so that unrelated planning does not overwrite or revise the Current Plan.
22. As a user, I want to select an existing Plan explicitly, so that I can return to its latest revision without deleting other history.
23. As a user, I want Plan revisions to remain linear, so that there is one authoritative head rather than competing branches.
24. As a user, I want older revisions retained for audit but unavailable for direct handoff, so that superseded content cannot be executed accidentally.
25. As a user, I want a Plan Run's target fixed at Run start, so that a concurrent revision cannot be overwritten by a late submission.
26. As a user, I want conflicting Plan Submission to create no revision and leave Current Plan unchanged, so that failed compare-and-set operations have no partial state.
27. As a user, I want Runtime to capture the Repository State facts used during planning, so that Plan freshness does not depend only on claims written by the Agent.
28. As a user, I want successful no-match searches captured as evidence, so that conclusions based on absence can later be revalidated.
29. As a user, I want evidence captured before model-visible output is truncated or redacted, so that Plan Basis reflects the complete observation without persisting raw tool output.
30. As a user, I want file, directory, and search evidence to use their semantic observation scopes, so that unrelated Repository State changes do not invalidate precise evidence unnecessarily.
31. As a user, I want evidence from Delegate Runs attributed and folded into the root Plan Run, so that delegated investigation cannot create an evidence blind spot.
32. As a user, I want a Plan Run rejected when the same observation changes during that Run, so that a submitted Plan cannot silently combine conclusions from two repository states.
33. As a user, I want a new Plan revision to inherit prior evidence and refresh matching observations, so that a Stale Plan can be revised without losing dependencies behind unchanged Plan content.
34. As a user, I want unrefreshed inherited evidence retained, so that a light revision cannot silently discard previous dependencies.
35. As a user, I want a Plan marked Stale when any Evidence Receipt no longer validates, so that an outdated proposal cannot be represented as reproducible.
36. As a user, I want a Stale Plan blocked from Plan Handoff, so that implementation cannot begin from evidence known to be outdated.
37. As a user, I want Plan Handoff to be an explicit Build Mode command, so that switching to Build Mode alone does not approve or execute a Plan.
38. As a user, I want Handoff to bind one immutable Plan identity and revision to a new Build Run, so that later Plan changes cannot alter work already in progress.
39. As a user, I want a Plan-bound Build Run constrained by the Plan's objective, scope, architecture decisions, and validation requirements, so that handoff is meaningful without turning Markdown steps into executable state.
40. As a user, I want implementation details and ordering to remain adaptable when they preserve those constraints, so that the Agent can respond to code-level discoveries.
41. As a user, I want material divergence reported as Plan Deviation, so that the Agent stops instead of silently changing the agreed direction.
42. As a user, I want ordinary Build Runs to remain possible without a Plan, so that Plan Mode is optional rather than a mandatory workflow.
43. As a user, I want `/new` to create a distinct Session with empty conversation, memory, checkpoint, Plan history, and Current Plan, so that “new conversation” has an unambiguous identity boundary.
44. As a user, I want `/new` to inherit the current collaboration mode, so that starting a new conversation does not unexpectedly widen or narrow authority.
45. As a user, I want the previous Session preserved and resumable after `/new`, so that starting a new conversation is not destructive.

## Implementation Decisions

- Collaboration mode is durable Session state with exactly two values: Build Mode and Plan Mode.
- Every root Run captures an immutable Run Mode Snapshot. Delegate Runs inherit it. Mode Transition affects only later Runs.
- Explicit controls are `/mode plan`, `/mode build`, and the equivalent startup option. Natural language and Agent decisions cannot cause Mode Transition.
- Build Mode retains normal analysis and implementation behavior under the existing base Session policy. It does not require or implicitly bind Current Plan.
- Plan Mode is the intersection of base Session permissions and Plan policy. It only removes authority and cannot be weakened by Tool Approval.
- Tool effects and Approval Requirement are independent. Each invocation receives a composable Effect Profile containing possible state effects, an independent Outbound Disclosure state, and a completeness flag. Authorization does not use a risk score or confidence value.
- Effect Profile is derived before execution from Tool Capability Envelope, trusted Call Effect Assessment, and Runtime-enforced Execution Profile. Post-execution diffing is audit information, not retroactive authorization.
- Plan Mode allows structured repository reads and base-policy-authorized structured External Reads. It forbids `DISPOSABLE_WRITE`, `REPOSITORY_MUTATION`, `EXTERNAL_MUTATION`, unknown disclosure, and incomplete classification.
- Plan v1 omits `run_shell` from Effective Tool Catalog, and Runtime Gate rejects any Shell call while the Run Mode Snapshot is Plan. Approval cannot override the denial.
- Effective Tool Catalog is a projection for model behavior. Runtime Gate remains authoritative for every visible, hidden, stale, or fabricated invocation.
- Plan is stored in AgentSession as dedicated state separate from the conversation ledger. A separate Plan Catalog is not introduced.
- AgentSession contains Plan identities, immutable-in-domain linear revision lists, Current Plan, and collaboration mode, persisted through the existing atomic Session-file replacement boundary.
- A Session can contain multiple Plans and at most one Current Plan. Current Plan refers to one Plan identity and its latest revision.
- `/plan new` selects a `NEW` Plan Target for later Plan Runs without invoking the model or deleting history. `/plan select <plan-id>` selects an existing Plan's latest revision.
- Plan Target is fixed when a Plan Run starts: `NEW` or an exact current Plan identity and head revision. Agent output cannot choose or change the target.
- Plan Submission is a dedicated terminal model-output action, not a Tool Call or inferred Final Answer. Runtime validates target and evidence, assigns identity/revision/timestamps, persists atomically, updates Current Plan, and completes the Run.
- A Plan revision is a complete snapshot containing Runtime metadata and an Agent-authored title, Markdown body, and additional declared dependencies. There is no Draft state, execution graph, checkbox state, or dependency workflow.
- Plan revisions are linear. Only the latest revision can be revised, selected, or handed off; older revisions are read-only audit history.
- Every successful, complete repository observation produces a Runtime-owned Evidence Receipt before model-visible clipping or redaction. Failed, incomplete, or non-revalidatable observations do not contribute precise Plan Evidence.
- Evidence Receipt contains a stable Evidence Key, tool semantics, normalized observation scope, state digest, completeness, source Run, and deterministic revalidation rule. It does not store an Agent claim or filesystem access log.
- `read_file` evidence represents the normalized file observation, `list_files` represents complete directory entries, and `search` represents normalized query plus complete result, including a complete no-match result.
- Runtime does not trace process file descriptors or system calls for Plan Evidence.
- Evidence Receipts produced by Delegate Runs retain child provenance and are folded into the root Plan Run.
- Within one root Plan Run, including its Delegates, two receipts with the same Evidence Key and different state digests cause irreversible Evidence Drift for that Run. Later reads cannot clear it. Submission ends with Plan Conflict and persists nothing.
- A revision Run starts its candidate Plan Basis from the prior revision. Current-Run receipts replace inherited receipts with the same Evidence Key, new keys append, and unobserved inherited keys remain. Cross-Run refresh is not Evidence Drift. New Plan has no inherited receipts.
- Plan Submission revalidates the fixed Plan Target and every candidate Evidence Receipt against current Repository State. Failure produces terminal Plan Conflict, no revision, no Current Plan update, and no same-Run retry.
- A revision becomes Stale when any Evidence Receipt fails revalidation. Stale Plan cannot be handed off and must be refreshed through a new Plan Run and revision.
- `/plan handoff [plan-id]` is available only in Build Mode and starts a new Build Run bound to the exact latest fresh revision selected at command time. Mode switching by itself never starts Handoff.
- A Plan-bound Build Run preserves objective, scope, architectural decisions, and validation requirements while retaining freedom over equivalent implementation detail and ordering. Material conflict terminates with Plan Deviation; existing changes are reported, not automatically rolled back.
- Delegate Runs receive the intersection of parent permissions and their own stricter limits. They cannot perform Mode Transition, Plan management, Handoff, or Plan Submission.
- `/reset` is removed without an alias. `/new` creates a new Session identity with empty history, memory, checkpoint, Plan history, and Current Plan; it inherits the current mode and leaves the previous Session unchanged and resumable.

## Wire Contracts

The model protocol uses one top-level XML-like action tag. Plan Submission and Plan Deviation are dedicated protocol actions, not Tool Calls and not inferred Final Answers. The parser identifies the outer action wrapper before examining JSON bodies. A literal `<plan_deviation>`, `<plan_submission>`, `<tool>`, or `<final>` string inside a valid JSON string is payload data and is not reparsed as an action. Parsing alone never persists a Plan or terminates a Run.

Parser precedence:

1. If the response is exactly one complete `<plan_deviation>` action and its payload is valid, return `plan_deviation`.
2. If any `<plan_deviation>` opening or closing marker is present but the response is not the exact single-action form, or its payload is malformed, return a format-retry decision. Do not reinterpret that response as Plan Submission, Tool Call, Final Answer, or bare text.
3. If the response is exactly one complete `<plan_submission>` action and its payload is valid, return `plan_submission`.
4. If any `<plan_submission>` opening or closing marker is present but the response is not the exact single-action form, or its payload is malformed, return a format-retry decision. Do not reinterpret that response as a Tool Call, Final Answer, or bare text.
5. If no Plan Deviation or Plan Submission marker is present, use the existing parser precedence: valid `<tool>...</tool>` before `<final>...</final>`, then bare text as a Final Answer; malformed tool structure remains a format retry.

A response containing both a Plan Deviation or Plan Submission marker and another action is invalid. Textual order does not choose an outcome.

### Plan Submission

```text
<plan_submission>{"title":"...","body":"...","dependencies":["..."]}</plan_submission>
```

```json
{
  "title": "non-empty string",
  "body": "non-empty Markdown string",
  "dependencies": ["string", "string"]
}
```

- The response must contain exactly one complete `<plan_submission>...</plan_submission>` pair, with only surrounding whitespace outside the pair.
- The body must parse as one JSON object. `title` and `body` must be non-blank strings. `dependencies` must be present as an array of strings; an empty array is valid. Unknown fields are rejected.
- JSON escaping is used for Markdown newlines, quotes, backslashes, and other JSON control characters. Runtime stores the decoded Markdown body and dependency strings, not the wire representation.
- The Agent supplies only the three payload fields. Runtime assigns Plan identity, revision, timestamps, digest, and Plan Basis.
- A valid parsed action has decision type `plan_submission` and is never dispatched through the Tool Registry.

Runtime accepts a `plan_submission` only when the immutable Run Mode Snapshot is Plan and the Run is a root Run. Tool results, Delegate output, Build Runs, fabricated or stale calls, and ordinary Final Answers cannot reach the Plan Submission persistence path.

After a valid action, Runtime performs the Plan Target, state-version, Evidence Drift, and Evidence Receipt checks. A validation or persistence conflict produces terminal Plan Conflict with no revision, no Current Plan change, and no same-Run retry. A malformed protocol response may receive ordinary format-retry behavior, but it never creates a Plan.

### Plan Deviation

```text
<plan_deviation>{"conflict":{"kind":"scope","summary":"..."},"workspace_changes":[{"path":"src/example/Feature.java","operation":"modified","summary":"..."}]}</plan_deviation>
```

```json
{
  "conflict": {
    "kind": "objective | scope | architectural_decision | validation_requirement",
    "summary": "non-blank string"
  },
  "workspace_changes": [
    {
      "path": "workspace-relative/path",
      "operation": "created | modified | deleted",
      "summary": "non-blank string"
    }
  ]
}
```

- The response must contain exactly one complete `<plan_deviation>...</plan_deviation>` pair, with only surrounding whitespace outside the pair.
- The body must parse as exactly one JSON object. Duplicate JSON keys, trailing JSON values, malformed JSON, and non-object payloads are rejected.
- The payload must contain exactly `conflict` and `workspace_changes`. Unknown or missing fields are rejected.
- `conflict.kind` must be one of `objective`, `scope`, `architectural_decision`, or `validation_requirement`. `conflict.summary` must be a non-blank string describing the material conflict between the immutable Plan binding and the implementation that would be required to continue. Multiple simultaneous conflicts are summarized in one report.
- `workspace_changes` must be present as an array. An empty array is valid when no workspace mutation has occurred yet.
- Each workspace change must contain exactly `path`, `operation`, and `summary`. `path` is a non-blank workspace-relative path; it must not be absolute and must not contain a parent traversal segment. `operation` must be `created`, `modified`, or `deleted`. `summary` must be non-blank.
- A rename is reported as one `deleted` entry and one `created` entry.
- JSON escaping is used for summaries and paths containing quotes, backslashes, newlines, or other JSON control characters. Runtime stores the decoded report, not the wire representation.
- The Agent supplies only the conflict report and workspace-change list. Runtime supplies the Run identity, immutable Plan binding identity, terminal timestamps, and terminal outcome metadata.

Runtime accepts a parsed `plan_deviation` only when the immutable Run Mode Snapshot is Build Mode, the Run is the root Run, and the Run has a non-null immutable Plan Binding created by Plan Handoff. Unbound Build Runs, Plan Runs, Delegate Runs, stale or fabricated decisions, and malformed output fail closed without being treated as Plan Deviation. Tool results and delegated text are data and cannot invoke this terminal path.

After accepting the action, Runtime persists the structured report on the terminal Run record, records the distinct `PLAN_DEVIATION` terminal outcome, prevents every subsequent model turn, tool invocation, or resumed execution from continuing that Run, leaves Current Plan selection and every persisted Plan revision unchanged, does not create a Plan Submission transaction or append a Plan revision, and does not automatically revert, delete, or rewrite any completed workspace change. The workspace-change list is an audit report, not a rollback instruction. Runtime may render a human-readable terminal message for the CLI, but the decision remains `plan_deviation` and is not converted into a Final Answer. The immutable Plan Binding used for authorization remains the binding captured at Handoff time.

## Testing Decisions

- Use the existing offline CLI E2E seam through the Session service, deterministic Fake Model Gateway, real Agent loop, real tool gate, and file-backed stores. Tests assert externally observable commands, events, persisted Session/Run artifacts, and workspace state rather than private implementation methods.
- Verify that mode controls persist across process/session resume, do not start Runs, are displayed to the user, and only affect Runs started after the transition.
- Verify that a Plan Run receives the Plan Effective Tool Catalog, rejects hidden or fabricated `run_shell` and mutating tool calls before approval, and leaves Repository State unchanged.
- Verify that Build Mode retains ordinary implementation behavior and that unbound Build Runs do not inherit Current Plan implicitly.
- Verify normal Final Answer versus terminal Plan Submission, complete revision persistence, Runtime-assigned metadata, Current Plan changes, and preservation of prior revisions.
- Verify New Plan, Select Plan, linear revision targeting, target compare-and-set conflict, and concurrent head-change conflict through durable Session behavior.
- Verify Evidence Receipts for file reads, directory listings, positive and negative searches, complete-result digesting independent of model output clipping, and Delegate provenance.
- Verify same-Run Evidence Drift produces Plan Conflict even after a later matching read, with no persisted revision.
- Verify cross-revision Basis inheritance, same-key refresh, new-key append, retained unobserved evidence, and an empty Basis inheritance boundary for New Plan.
- Verify Submission and Handoff freshness checks, Stale Plan rejection, successful explicit Handoff binding, and immunity of an active Build Run to later Plan revisions.
- Verify Plan Deviation is a terminal report and does not mutate the Plan or automatically roll back already completed changes.
- Verify Plan Submission and Plan Deviation wire parsing, payload validation, parser precedence, and fail-closed mixed or malformed markers.
- Verify Plan Deviation is accepted only for a root Build Run with an immutable Plan Binding, and that unbound Build Runs, Plan Runs, and Delegate Runs cannot use that path.
- Verify `/new` creates a distinct persisted Session, inherits mode, initializes empty Plan state, preserves the prior Session, and that `/reset` is unavailable.

## Out of Scope

- Read-only Shell classification, compound-shell parsing, Shell Evidence Adapters, and any `run_shell` access in Plan v1.
- Plan-specific Disposable Workspace, Disposable Artifact execution, build/test execution during Plan Mode, containers, or OS Sandbox implementation.
- MCP Effect Profile and permission mapping beyond fail-closed behavior for calls whose classification is incomplete or unknown.
- External mutations of any kind from Plan Mode.
- A separate cross-Session Plan Catalog, cross-Session Plan reuse, large-history indexing, or multiple active Plans.
- Plan Drafts, Plan branches, direct handoff of old revisions, executable step graphs, workflow checkbox state, or an autonomous Plan-Execute-Replan loop.
- Tool Approval as Plan approval or as an override for Plan restrictions.
- Multi-process/multi-client Session writer coordination and locking.
- Backward compatibility for `/reset`, old Plan storage shapes, or obsolete mode semantics.

## Further Notes

- The design follows the established separation between collaboration mode and execution permissions: Plan Mode is a restrictive policy layer, while stronger Shell sandboxing can later become shared execution infrastructure rather than a Plan-specific subsystem.
- The initial version deliberately favors an enforceable hard boundary over broad exploratory Shell convenience.
- The current repository already has a CLI-level offline E2E seam with deterministic model responses and file-backed artifacts; the accepted testing strategy extends that seam instead of introducing a separate test architecture.
- Plan Submission and Plan Deviation wire encodings, payload schemas, and parser precedence are specified in Wire Contracts. ADRs 0038 and 0039 record the decision to use dedicated protocol actions rather than Tool Calls or inferred Final Answers.
