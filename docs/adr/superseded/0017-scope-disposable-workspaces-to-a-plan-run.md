# Scope each Disposable Workspace to one Plan Run

Status: Superseded by ADR 0033.

Runtime lazily creates at most one Disposable Workspace when a Plan Run first needs isolated execution, reuses it within that Run, and releases it whenever the Run reaches a terminal outcome. Entering Plan Mode alone creates no workspace, later Runs never inherit one, and startup recovery reclaims orphaned workspaces left by abnormal process termination. This preserves useful intermediate validation artifacts within a Run without allowing hidden mutable state to cross Run boundaries.
