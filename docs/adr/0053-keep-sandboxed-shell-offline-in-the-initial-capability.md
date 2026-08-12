# Keep sandboxed Shell offline in the initial capability

Status: Accepted.

Plan, ordinary Build, and Delegate Shell profiles deny network access and do not implement domain grants, a network proxy, or post-denial network escalation in the initial capability. Commands that require network fail explicitly; structured External Read tools retain their separate Effect, disclosure, and permission boundaries. Only launch-scoped Full Access uses the host user's native network authority. This keeps the native filesystem sandbox and input-aware Permission Policy independently useful without prematurely adding proxy enforcement and TLS-boundary complexity.
