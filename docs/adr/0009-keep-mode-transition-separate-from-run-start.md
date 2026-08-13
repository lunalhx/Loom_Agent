# Keep Mode Transition separate from Run start

Status: Accepted.

A Mode Transition changes only the Session's collaboration mode and never starts a Run, calls a model, or executes the Current Plan. After switching from Plan Mode to Build Mode, the user must submit a new request to begin work. This keeps permission changes separate from task submission and prevents a mode-selection action from becoming an implicit Plan approval or mutation trigger.
