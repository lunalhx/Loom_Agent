# Use scoped Execution Grants for Build escalation

Status: Accepted.

An ordinary root Build Run may request a user-approved Execution Grant for a concrete, real-path-resolved regular file or directory with read or write access. Workspace path traversal and symlink targets outside the canonical root count as external paths and do not inherit workspace authority. Execution Grants are evaluated and audited separately from Permission Grants, may last for one call, the Session, or the Workspace, and never mean “disable the sandbox”; Full Access remains a launch-scoped user control. Plan and Delegate Runs cannot request them, project configuration cannot grant them, and a denied or insufficient Grant never causes an automatic unsandboxed fallback. Network, IPC, device, FIFO, and other special-file Grants are outside the initial capability.
