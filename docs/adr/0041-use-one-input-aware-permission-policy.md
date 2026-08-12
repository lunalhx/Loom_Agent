# Use one input-aware permission policy

Status: Accepted.

Every concrete Tool Call is evaluated by one input-aware Permission Policy containing a default `ALLOW`, `ASK`, or `DENY` action plus rules that may select a different action. The CLI values `auto`, `ask`, and `never` are presets for that default action rather than a second approval mechanism, and Plan Mode, Build Mode, built-in tools, and external tools share the same evaluator. This replaces the static tool-level `ApprovalRequirement`; Effect, Execution Profile, allowlist, and collaboration-mode authorization remain earlier independent boundaries that no Permission Decision or Tool Approval can widen.
