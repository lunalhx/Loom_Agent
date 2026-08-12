# Treat Skill Activation as a dedicated control action with untrusted Tool output

Status: Accepted.

Agent Skills are discovered as metadata-only packages from user and project `.agents/skills` and `.claude/skills` sources, with deterministic same-name precedence and compatibility diagnostics for unsupported Claude extensions. Skill Activation is a dedicated non-terminal Runtime control action—not a Tool Call—that assembles complete instruction snapshots into a lower-priority system-prompt section for the current Run only; it cannot grant Tool permissions, widen Execution Profile, or execute packaged scripts during discovery or activation. Ordinary ToolResult content remains untrusted and cannot promote instructions. `/skills` is a read-only control command that reports the effective catalog without creating a Run or calling the model.
