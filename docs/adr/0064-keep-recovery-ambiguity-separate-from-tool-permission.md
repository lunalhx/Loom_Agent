# Keep recovery ambiguity separate from Tool permission

Status: Accepted.

Run Recovery first performs safe observation and enters Ambiguity Review when an Interrupted Tool Call remains unverifiable; the user may abandon, add facts, or Continue with Ambiguity without relabeling the old result. Recovery state never adds a parallel approval system: every later Tool Call is a new call evaluated by the existing Effect, Execution Profile, and Permission Policy pipeline, so an `ALLOW` decision executes without a recovery-specific prompt even when the call matches the interrupted one. This preserves one authorization model while keeping the prior ambiguity visible and never automatically replaying it.
