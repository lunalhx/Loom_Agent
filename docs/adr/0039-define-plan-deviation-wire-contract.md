# Define the Plan Deviation wire contract

Status: Accepted

## Context

Plan Deviation is a terminal protocol action for a Build Run that is bound to
an immutable Plan revision. It is used when continuing the implementation
would materially change the Plan's objective, scope, architectural decision,
or validation requirement.

The Plan Mode specification deliberately leaves the concrete model-output
encoding undecided. Ticket 9 cannot be implemented safely until G-DEVIATE
defines the exact payload and parser precedence. The contract must fail closed:
ordinary prose, malformed output, tool output, and actions from ineligible
Runs must never terminate a Run as Plan Deviation.

The contract must also preserve the distinction between reporting a deviation
and answering the user, submitting a Plan, or executing a Tool Call. Accepting
a deviation stops the bound Run, preserves the Plan aggregate, and does not
roll back workspace changes already completed by that Run.

## Decision

Use a dedicated top-level `<plan_deviation>` action whose body is one JSON
object:

```text
<plan_deviation>{"conflict":{"kind":"scope","summary":"The requested integration requires files outside the bound Plan scope."},"workspace_changes":[{"path":"src/main/java/example/Feature.java","operation":"modified","summary":"Added the initial implementation before the scope conflict was discovered."}]}</plan_deviation>
```

The payload schema is:

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

The following rules apply:

- The response must contain exactly one complete
  `<plan_deviation>...</plan_deviation>` pair, with only surrounding
  whitespace outside the pair.
- The body must parse as exactly one JSON object. Duplicate JSON keys,
  trailing JSON values, malformed JSON, and non-object payloads are rejected.
- The payload must contain exactly `conflict` and `workspace_changes`.
  Unknown or missing fields are rejected.
- `conflict.kind` must be one of `objective`, `scope`,
  `architectural_decision`, or `validation_requirement`.
  `conflict.summary` must be a non-blank string describing the material
  conflict between the immutable Plan binding and the implementation that
  would be required to continue.
- `workspace_changes` must be present as an array. An empty array is valid
  when no workspace mutation has occurred yet.
- Each workspace change must contain exactly `path`, `operation`, and
  `summary`. `path` is a non-blank workspace-relative path; it must not be
  absolute and must not contain a parent traversal segment. `operation` must
  be `created`, `modified`, or `deleted`. `summary` must be non-blank.
- A rename is reported as one `deleted` entry and one `created` entry. This
  keeps the payload small and avoids adding a fourth path field.
- JSON escaping is used for summaries and paths containing quotes,
  backslashes, newlines, or other JSON control characters. Runtime stores the
  decoded report, not the wire representation.
- The Agent supplies only the conflict report and workspace-change list.
  Runtime supplies the Run identity, immutable Plan binding identity, terminal
  timestamps, and terminal outcome metadata.

### Parser precedence

The parser evaluates Plan Deviation before Plan Submission, Tool Call, and
Final Answer:

1. If the response is exactly one complete `<plan_deviation>` action and its
   payload is valid, return a decision of type `plan_deviation`.
2. If an outer Plan Deviation marker is present but the response is not the
   exact single-action form, or its payload is malformed, return a
   format-retry decision. Do not reinterpret that response as Plan Submission,
   Tool Call, Final Answer, or bare text.
3. If no outer Plan Deviation marker is present, apply the accepted G-SUBMIT
   contract from ADR 0038: valid exact Plan Submission first, malformed
   Plan Submission markers as format retry, then the existing Tool Call and
   Final Answer precedence.

This makes a response containing both Plan Deviation and another action
invalid; textual order does not select an outcome. The parser identifies the
outer action wrapper before examining the JSON body, so a literal
`<plan_deviation>`, `<plan_submission>`, `<tool>`, or `<final>` string inside a
valid JSON string is payload data and is not reparsed as an action.

Parsing alone never terminates a Run. A malformed or mixed response receives
the ordinary format-retry behavior and cannot create a deviation outcome.

### Runtime authorization and terminal behavior

Runtime accepts a parsed `plan_deviation` only when all of the following are
true:

- the immutable Run Mode Snapshot is Build Mode;
- the Run is the root Run, not a Delegate/child Run; and
- the Run has a non-null immutable Plan Binding created by Plan Handoff.

Unbound Build Runs, Plan Runs, Delegate Runs, stale/fabricated decisions, and
malformed output fail closed without being treated as Plan Deviation. Tool
results and delegated text are data and cannot invoke this terminal path.

After accepting the action, Runtime:

- persists the structured report on the terminal Run record;
- records the distinct `PLAN_DEVIATION` terminal outcome;
- prevents every subsequent model turn, tool invocation, or resumed execution
  from continuing that Run;
- leaves Current Plan selection and every persisted Plan revision unchanged;
- does not create a Plan Submission transaction or append a Plan revision; and
- does not automatically revert, delete, or rewrite any completed workspace
  change.

Runtime may render a human-readable terminal message for the CLI, but the
decision remains `plan_deviation`; it is not converted into a Final Answer.
The immutable Plan Binding used for authorization remains the binding captured
at Handoff time, regardless of later Plan selection or revision changes.

## Consequences

- Plan Deviation has a distinct, deterministic, and testable wire
  representation.
- The report is intentionally small and structured: one primary immutable
  constraint conflict plus the workspace changes already made by the Run.
- The protocol does not attempt to infer a deviation from natural-language
  prose or from a filesystem diff. The Agent must explicitly report it, while
  Runtime owns eligibility and terminal enforcement.
- The workspace-change list is an audit report, not a rollback instruction or
  an authorization to mutate additional files.
- The parser must distinguish outer action markers from marker-like strings
  inside JSON payload data.
- This contract is limited to Plan Deviation. It does not change the accepted
  Plan Submission wire contract in ADR 0038 or add Plan revision behavior.

## Ticket 9 verification obligations

Ticket 9 must test at least:

- exact valid parsing, enum validation, path validation, duplicate-key
  rejection, unknown-field rejection, and malformed JSON rejection;
- parser precedence and fail-closed behavior for mixed Deviation,
  Submission, Tool, and Final actions;
- ordinary prose and tool/delegate output not becoming Plan Deviation;
- runtime rejection for unbound Build Runs, Plan Runs, Delegate Runs, and
  fabricated decisions;
- an offline CLI flow that performs one permitted workspace edit in a bound
  Build Run, emits the exact action, preserves the edit, persists the distinct
  terminal outcome, and prevents further model/tool work;
- unchanged Current Plan selection and Plan revision history; and
- no automatic workspace rollback.

## Approval checklist

Approve this ADR only if these product decisions are correct:

1. The exact outer tag is `<plan_deviation>` and the payload has exactly
   `conflict` plus `workspace_changes`.
2. A deviation has one primary conflict kind from the four listed enum values;
   multiple simultaneous conflicts are summarized in one report.
3. Workspace changes use relative paths and the three operations
   `created`/`modified`/`deleted`; an empty list is allowed, and rename is two
   entries.
4. A valid deviation outranks Plan Submission, Tool Call, and Final Answer;
   any mixed or malformed deviation marker fails closed.
5. Only a root Build Run with an immutable Plan Binding may accept it.
6. Acceptance records `PLAN_DEVIATION`, stops all later execution, preserves
   the Plan and existing workspace changes, and never performs rollback.
