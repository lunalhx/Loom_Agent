# Bind Plans to Build Runs only through explicit handoff

Status: Accepted.

A Build Run is bound to a Plan only when the user explicitly requests a Plan Handoff. The binding captures an immutable Plan identity and revision when the Run starts; later Plan revisions cannot alter that Run. Ordinary Build requests remain unbound even when a Current Plan exists, preventing stale or unrelated plans from silently changing task semantics while preserving a reproducible basis for plan-directed work.
