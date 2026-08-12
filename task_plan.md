# Shell Permissions completion plan

## Goal

Complete the remaining Shell Permissions plan in the agreed order while preserving fail-closed behavior and verifying each phase before it is committed.

## Phases

1. **Complete — Native process supervision**: fixed native launcher establishes a process group before exec; timeout uses group TERM→KILL and normal completion clears ordinary in-group background processes. Root cancellation registration is grouped with the shared security scope in phase 3.
2. **Complete — Sandbox backends**: Seatbelt and Bubblewrap are selected by platform and self-test; ordinary execution remains fail-closed when neither is usable. Bubblewrap uses an empty root with only runtime/workspace/grant mounts and network/PID/IPC namespaces.
3. **Complete — Root-run security scope**: shared transient scope provides disposable HOME/TMP, fair one-Shell permit, registered process cancellation, delegate inheritance, Reactor cancellation propagation, and root terminal cleanup.
4. **Complete — Repository state tracking**: RepositoryStateTracker replaces best-effort fingerprints, records symlink entries without traversal, excludes only runtime artifacts, and adds Git logical HEAD/index/refs state to diffs used for partial-success reporting.
5. **Complete — Plan/Delegate shell**: profile intersection, explicitly configured read-only Maven cache for Plan, disposable HOME/TMP, and conditional catalog exposure.
6. **Complete — Shell evidence**: transient receipt lists, real repository-state fallback observations, and registry-based revalidation.
7. **Complete — Normalizer/classifier**: conservative compound parsing, non-opaque unit policy composition, built-in safety floor, and sensitive-resource rules.
8. **Complete — Full Access controls**: launch-only confirmation, active/inactive REPL state, root-Build-only binding, and host-credential per-call-only behavior.
9. **Complete — Schema cutover and cleanup**: checkpoint schema v13 carries safe authorization audit metadata; obsolete workspace fingerprints and unsandboxed Shell paths are removed; source compilation, review, and full verification are complete.

## Acceptance checks

- No ordinary Build shell executes when the native sandbox backend is unavailable.
- Permission and execution grants never expand a frozen profile outside their exact scope.
- Full Access is launch-scoped, interactive, root-Build-only, and visibly labeled.
- Full Maven suite, native contract tests where backend is available, review, and commits pass at completion.

## Errors encountered

| Error | Resolution |
|---|---|
| Current macOS environment returns `sandbox_apply: Operation not permitted` | Keep ordinary Shell hidden/rejected through cached fail-closed self-test; do not add a host fallback. |
