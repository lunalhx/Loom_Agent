# Limit initial Execution Grants to regular filesystem paths

Status: Accepted.

The initial Execution Grant capability accepts only real-path-resolved regular files and directories with an explicit read or write scope. Unix sockets, device nodes, FIFOs, and other special files are rejected rather than treated as ordinary paths, because access to resources such as a Docker socket or raw device can escape the sandbox or approximate host-level authority. Network and IPC grants remain unsupported; tasks requiring those capabilities use an explicitly selected Full Access launch.
