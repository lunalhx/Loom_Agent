# Keep Full Access Runs nonrecoverable after process exit

Status: Accepted.

A non-terminal Full Access Run whose process exits enters Recovery Required for inspection but offers only Run Abandonment; it cannot restore Full Access or continue the same Run under a downgraded sandbox. Full Access can have unbounded host/network effects that Runtime cannot reconcile, is launch-scoped rather than Session-persisted, and is part of the Run's frozen Execution Profile, so `Recover in Sandbox` would create a mixed-authority Run. Conversation History, actual changes, interrupted-call facts, and audit records remain, and subsequent work starts a new Run under an explicitly selected current-launch profile.
