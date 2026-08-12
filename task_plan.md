# Shell Permissions completion plan

## Goal

Complete the remaining Shell Permissions plan in the agreed order while preserving fail-closed behavior and verifying each phase before it is committed.

## Phases

1. **Complete — Native process supervision**: fixed native launcher establishes a process group before exec; timeout uses group TERM→KILL and normal completion clears ordinary in-group background processes. Root cancellation registration is grouped with the shared security scope in phase 3.
2. **Complete — Sandbox backends**: Seatbelt and Bubblewrap are selected by platform and self-test; ordinary execution remains fail-closed when neither is usable. Bubblewrap uses an empty root with only runtime/workspace/grant mounts and network/PID/IPC namespaces.
3. **In progress — Root-run security scope**: shared cancellation, shell semaphore, disposable HOME/TMP/cache lifecycle.
4. **Pending — Repository state tracking**: replace best-effort workspace fingerprint and report partial success correctly.
5. **Pending — Plan/Delegate shell**: profile intersection, Maven cache grants, conditional catalog exposure.
6. **Pending — Shell evidence**: semantic observation, repository fallback receipts, verifier registry.
7. **Pending — Normalizer/classifier**: conservative POSIX coverage, safety floors, sensitive-resource rules.
8. **Pending — Full Access controls**: complete REPL sandbox selection and host-credential per-call-only behavior.
9. **Pending — Schema cutover and cleanup**: context/checkpoint snapshots, obsolete code removal, final contract suites and review.

## Acceptance checks

- No ordinary Build shell executes when the native sandbox backend is unavailable.
- Permission and execution grants never expand a frozen profile outside their exact scope.
- Full Access is launch-scoped, interactive, root-Build-only, and visibly labeled.
- Full Maven suite, native contract tests where backend is available, review, and commits pass at completion.

## Errors encountered

| Error | Resolution |
|---|---|
| Current macOS environment returns `sandbox_apply: Operation not permitted` | Keep ordinary Shell hidden/rejected through cached fail-closed self-test; do not add a host fallback. |
