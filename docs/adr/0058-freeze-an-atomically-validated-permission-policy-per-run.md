# Freeze an atomically validated Permission Policy per Run

Status: Accepted.

Before a root Run calls the model or any tool, Runtime atomically parses and validates every Built-in, User-local, and Project permission source into one immutable Permission Policy Snapshot. A syntax or validation error in any source rejects the Run with a diagnostic that identifies the source and location; Runtime neither ignores bad rules nor accepts a partial source, including under Full Access. On-disk changes apply to the next Run, Delegates inherit the root snapshot, and Tool Approvals created during the current Run take effect through a separate append-only Permission Grant overlay. This prevents a malformed restrictive rule from silently disappearing and keeps authorization deterministic without requiring live configuration reload.
