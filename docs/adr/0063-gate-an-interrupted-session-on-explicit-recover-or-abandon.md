# Gate an interrupted Session on explicit Recover or Abandon

Status: Accepted.

When a Session contains a non-terminal root Run whose Attempt was lost, Runtime automatically enters Recovery Required and shows the durable interruption facts, but never starts model or Tool work automatically. The Session cannot accept a new ordinary request until the user explicitly chooses Run Recovery or Run Abandonment; recovery creates a new Attempt for the same Run, while abandonment terminates it without rollback and preserves all changes, ambiguity, and audit records. This prevents multiple unfinished root Runs from interleaving one conversation and workspace; users who need unrelated work can use another Session.
