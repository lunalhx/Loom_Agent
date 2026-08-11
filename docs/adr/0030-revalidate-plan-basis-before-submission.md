# Revalidate Plan Basis before Submission

Status: Accepted, with same-Run evidence drift refined by ADR 0035.

Runtime commits a Plan Submission only after rechecking both its fixed Plan Target head and all captured Plan Basis against current Repository State. A mismatch produces a terminal Plan Conflict with no revision or Current Plan change and no same-Run retry, rather than persisting a Plan that is already stale or allowing concurrent overwrite. A later Plan Run must observe the changed state and reconsider the proposal.
