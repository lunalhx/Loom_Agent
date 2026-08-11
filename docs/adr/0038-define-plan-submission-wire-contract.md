# Define the Plan Submission wire contract

Status: Accepted

## Context

Plan Submission is a terminal protocol action, not a Tool Call and not an
inferred Final Answer. The Plan Mode specification requires an exact model
output encoding and deterministic parser precedence before ticket 06 can be
implemented. The existing model protocol uses one top-level XML-like action
tag containing either JSON (`<tool>...</tool>`) or text (`<final>...</final>`).

The contract must also fail closed: malformed output, output that combines
actions, and untrusted text returned by a tool must never create or revise a
Plan.

## Decision

Use a dedicated top-level `<plan_submission>` action whose body is one JSON
object:

```text
<plan_submission>{"title":"...","body":"...","dependencies":["..."]}</plan_submission>
```

The payload schema is:

```json
{
  "title": "non-empty string",
  "body": "non-empty Markdown string",
  "dependencies": ["string", "string"]
}
```

The following rules apply:

- The response must contain exactly one complete `<plan_submission>...</plan_submission>` pair,
  with only surrounding whitespace outside the pair.
- The body must parse as one JSON object. `title` and `body` must be non-blank
  strings. `dependencies` must be present as an array of strings; an empty
  array is valid. Unknown fields are rejected.
- JSON escaping is used for Markdown newlines, quotes, backslashes, and other
  JSON control characters. Runtime stores the decoded Markdown body and
  dependency strings, not the wire representation.
- The Agent supplies only the three payload fields. Runtime assigns Plan
  identity, revision, timestamps, digest, and Plan Basis.
- A valid parsed action has decision type `plan_submission` and terminates the
  root Plan Run. It is never dispatched through the Tool Registry and is not
  represented as a Final Answer.

### Parser precedence

The parser evaluates Plan Submission before the existing Tool Call and Final
Answer branches:

1. If the response is exactly one complete `<plan_submission>` action and its
   payload is valid, return `plan_submission`.
2. If any `<plan_submission>` opening or closing marker is present but the
   response is not the exact single-action form, or its payload is malformed,
   return a format-retry decision. Do not reinterpret that response as a Tool
   Call, Final Answer, or bare text.
3. If no Plan Submission marker is present, use the existing parser precedence:
   valid `<tool>...</tool>` before `<final>...</final>`, then bare text as a
   Final Answer; malformed tool structure remains a format retry.

This makes a response containing both Plan Submission and another action
invalid rather than allowing textual order to choose an outcome. A literal
`<tool>` or `<final>` string inside the decoded Markdown body is payload data
and is not reparsed as an action.

### Runtime authorization and terminal behavior

Parsing alone never persists a Plan. Runtime accepts a `plan_submission` only
when the immutable Run Mode Snapshot is Plan and the Run is a root Run. Tool
results, Delegate output, Build Runs, fabricated/stale calls, and ordinary
Final Answers cannot reach the Plan Submission persistence path.

After a valid action, Runtime performs the ticket 06 target, state-version,
Evidence Drift, and Evidence Receipt checks. A validation or persistence
conflict produces terminal Plan Conflict with no revision, no Current Plan
change, and no same-Run retry. A malformed protocol response may receive the
ordinary format-retry behavior, but it never creates a Plan.

## Consequences

- Plan Submission has a distinct, testable wire representation and cannot be
  inferred from Markdown prose.
- The existing Tool Call and Final Answer protocol remains unchanged when no
  Plan Submission marker is present.
- The parser must distinguish the outer action wrapper from strings inside the
  JSON payload before applying action precedence.
- Models must emit JSON-escaped Markdown in the `body` field.
- This contract is intentionally limited to first Plan Submission. Plan
  revision and Plan Deviation remain separate protocol decisions.

## Ticket 06 verification obligations

Ticket 06 must test at least:

- exact valid parsing and payload validation;
- precedence and fail-closed behavior for mixed, malformed, or fabricated
  Submission output;
- ordinary Final Answer versus root Plan Submission;
- rejection for Build Runs and Delegate Runs;
- terminal conflict behavior with no same-Run retry; and
- persistence and CLI visibility of the resulting revision.
