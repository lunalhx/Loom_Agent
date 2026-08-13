# Block Plan Handoff when the Plan Basis is stale

Status: Accepted.

Each Plan revision has a Plan Basis describing the relevant Repository State on which it depends. If that basis no longer matches at handoff time, the revision becomes a Stale Plan and cannot be bound to a Build Run; it must be revalidated in Plan Mode and submitted as a new revision. Unrelated repository changes do not invalidate the Plan, and users remain free to start an ordinary unbound Build Run, preserving flexibility without misrepresenting stale work as reproducible Plan execution.
