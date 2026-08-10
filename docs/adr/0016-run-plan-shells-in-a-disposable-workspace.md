# Run Plan Mode shells in a Disposable Workspace

Status: Superseded by ADR 0033.

Plan Mode executes shell commands only inside a Disposable Workspace derived from current Repository State; the real repository remains non-writable and changes in the isolated copy are never promoted automatically. All local writes made by the command, including unexpected source edits by tests or plugins, are therefore Disposable Artifacts rather than Repository State mutations. This avoids relying on command-text inference or build-system-specific output-directory allowlists, at the cost of isolation setup time and storage.
