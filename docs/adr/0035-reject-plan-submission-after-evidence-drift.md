# Reject Plan Submission after same-Run Evidence Drift

Status: Accepted.

Evidence Receipts are not replaceable within one root Plan Run. When the root or any Delegate observes the same Evidence Key with a different state digest, Runtime marks the root Run with Evidence Drift; later reads may help explain the change but cannot clear the marker or replace the earlier observation. Plan Submission from that Run terminates with Plan Conflict and persists no revision, so a new Plan Run must investigate against a consistent Repository State. This rejects an Evidence Epoch model and sacrifices recovery inside a long-running Plan Run because Runtime cannot prove that the proposed Plan no longer relies on conclusions formed from the earlier state.
