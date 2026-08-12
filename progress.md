# Progress

- 2026-08-12: Created completion plan. Current phase: native process supervision.
- Prior completed commits: authorization/policy/grants (`51f2a99` through `0e490f4`) and Full Access launch controls (`be4ca7d`, `fab323d`).
- 2026-08-12: Inspected current ShellRunner. It needs replacement of direct ProcessBuilder lifecycle with a supervisor seam before sandbox/evidence work.
- 2026-08-12: Native supervision attempt 1 failed: post-start `setpgid` returned failure because the child had already exec'd. Production profile launches remain fail-closed; direct no-profile runner tests intentionally bypass this incomplete supervisor seam.
- 2026-08-12: Native supervision attempt 2 failed: JVM child reaper races JNA `waitpid(WUNTRACED)` even with a fixed stopped-child wrapper. Reverted the uncommitted integration to avoid breaking Full Access Shell; next implementation must use an independent native launcher process.
- 2026-08-12: Confirmed local C toolchain availability for a potential fixed native launcher. No native source exists in the repository yet.
- 2026-08-12: User authorized the fixed native launcher implementation. Starting its C source, build integration, and Java handshake seam.
- 2026-08-12: Added a C launcher built during `generate-resources`. It blocks `SIGUSR1`, sets its group, reports readiness, and only then execs after Java releases it. Full Access handshake test passes.
- 2026-08-12: Native timeout test initially missed a `TimeUnit` import; corrected before rerun.
- 2026-08-12: Added process-group cleanup for ordinary background descendants after direct Shell completion; result records `backgroundProcessTerminated`.
- 2026-08-12: Phase 1 verified with native handshake, timeout, background cleanup, bounded output tests and full Maven suite (263 tests). Moving to sandbox backends.
- 2026-08-12: Added backend selector and Bubblewrap command construction (empty root, network/PID/IPC namespaces, only runtime/workspace/declared grant mounts). Backend availability remains fail-closed.
- 2026-08-12: Phase 2 contract tests passed for Seatbelt policy, Bubblewrap command construction, and native Shell lifecycle. Moving to root-run security scope.
- 2026-08-12: Added shared root security scope with disposable HOME/TMP, a fair one-Shell semaphore, registered process cancellation, and Reactor sink cancellation propagation. Root terminal loop closes the scope; delegates inherit it transiently.
- 2026-08-12: Replaced WorkspaceFingerprint with RepositoryStateTracker: regular files and symlink entries are digested without following links; `.loom-code` is excluded; Git HEAD/index/refs/packed-refs become logical paths.
- 2026-08-12: Plan/Delegate Shell vertical slice now conditionally exposes only when native backend is ready and classifies conservative read-only command forms as repository reads; all other shell forms stay untrusted and profile-denied.
- 2026-08-12: Added an explicit `maven_repository` user-local policy field. It resolves to one existing read-only directory only for Plan profiles, while ordinary sandbox Shell now receives its disposable HOME/TMP roots in both the environment and native backend mounts.
