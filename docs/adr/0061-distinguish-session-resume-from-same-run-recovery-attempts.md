# Distinguish Session Resume from same-Run recovery Attempts

Status: Accepted.

Session Resume remains conversation continuity whose next user request creates a new root Run; it never silently takes over an unfinished Run. Process-crash recovery instead preserves the unfinished logical Run and its `runId`, creates a new exclusive Attempt to advance it, and does not introduce a duplicate `executionId`. Exclusivity is enforced across processes by a durable fenced Attempt Lease: a healthy owner cannot be force-taken, and an expired or released owner cannot keep writing or start Tools. This keeps Tool Call identity, effect reconciliation, frozen Run state, and audit history attached to one logical execution; abandoning that Run and issuing another request is a new Run rather than recovery.
