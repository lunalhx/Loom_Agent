# Model Plan as a versioned Session Artifact

Status: Accepted.

A Plan is a first-class, durable Session Artifact with stable identity and revision history, not merely an Assistant message containing Markdown. Only an explicitly finalized proposal creates or revises a Plan; questions, research updates, and incomplete analysis remain conversation entries. This adds lifecycle state but makes review, feedback, Session recovery, audit, and an exact Build Mode handoff possible without guessing which message represents the intended Plan.
