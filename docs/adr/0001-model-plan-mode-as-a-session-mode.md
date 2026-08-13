# Model Plan Mode as a persistent Session mode

Status: Accepted.

Plan Mode is a persistent Session-level collaboration mode, not a phase inside a gated Plan-and-Execute Run. Producing a Plan completes the current Run while the Session remains in Plan Mode; later execution requires an explicit mode change and a new Run. This preserves the existing one-request-one-Run boundary, keeps Plan decisions separate from Tool Approval, and deliberately rejects both same-Run `WAITING_PLAN_APPROVAL` and the previously removed autonomous Plan-Execute-Replan model.
