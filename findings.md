# Findings

- Authorization spine, policy snapshots, permission grants, execution grants, structured shell results, Seatbelt fail-closed probing, and initial Full Access launch confirmation are already committed.
- `sandbox-exec` exists on the host but capability self-test is denied by the host environment; native ordinary-build execution cannot be validated here without a capable macOS environment.
- Current `ShellRunner` uses `ProcessHandle.descendants()` as best effort. It is not the required process-group supervisor and may be denied process enumeration by the host.
- `WorkspaceFingerprint` remains the repository-change mechanism and must be replaced by the specified repository tracker.
- Session execution grants are schema v4 and workspace grants are stored atomically in the user-local workspace grants document.
- `ShellRunner` still starts `ProcessBuilder` directly and cleans descendants with best-effort `ProcessHandle`; it has no process-group ownership, cancellation registration, or JNA dependency.
- JNA `setpgid` is available, but calling it after Java `ProcessBuilder.start()` races with `exec` and fails on this host. A stopped-child wrapper or native launcher is required for the specified no-race production contract.
- A JNA `waitpid(WUNTRACED)` stopped-child attempt also fails because the JVM's process reaper owns child waiting. A standalone native launcher is the remaining viable implementation path without unsafe JVM `fork` use.
- This macOS development host has `/usr/bin/cc`, `clang`, and `make`; the repository currently has no native launcher source or build integration.
- Native launcher uses a fixed argv protocol and no command interpolation: `sigprocmask(SIGUSR1) → setpgid(0,0) → readiness marker → sigwait(SIGUSR1) → execvp`.
