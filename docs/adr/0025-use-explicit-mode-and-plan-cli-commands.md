# Use explicit mode and Plan CLI commands

Status: Accepted.

The line-oriented CLI exposes persistent mode changes through `/mode plan`, `/mode build`, and `--mode`, and Plan management through `/plan new|list|show|select|handoff`; the prompt displays the active mode. Management commands do not invoke the model, while handoff is allowed only from Build Mode and starts a separately bound Run rather than implicitly switching mode. This preserves explicit control semantics in both REPL and one-shot use without requiring a key-event terminal UI merely to copy OpenCode's Tab shortcut.
