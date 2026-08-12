# Persist frozen Skill and authorization state for unfinished Run continuation

Status: Accepted.

Unfinished Run checkpoints use schema v14 and persist the frozen Skill Catalog Snapshot, Active Skill instruction bodies with resource identities, and a serializable Frozen Authorization Snapshot (policy rules, grants, and capability profile). Host-absolute Skill package paths are excluded from durable state and rebound on restore from source labels; disposable security-scope roots are always recreated. Resume continues the same Run from `prompt_build` using the frozen snapshots rather than rediscovering Skills or recompiling policy from host configuration, so later disk changes cannot alter the restored Run. Full Access Runs remain non-recoverable after process restart. AgentSession stays at schema v4; each new root Run still rediscovers its Skill Catalog.
