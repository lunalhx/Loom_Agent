# Bind Handoffs to Plan decisions, not literal steps

Status: Accepted.

A Plan-bound Build Run must preserve the Plan's objective, scope boundaries, architectural decisions, and validation requirements, while remaining free to adjust equivalent implementation details and ordering. A material conflict produces a terminal Plan Deviation report instead of silent divergence or mutation of the Plan from Build Mode; existing work is reported rather than automatically rolled back. Runtime records the binding and deviation, but does not parse Markdown to enforce semantic conformance as a workflow engine.
