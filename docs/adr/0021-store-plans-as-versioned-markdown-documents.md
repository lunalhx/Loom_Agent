# Store Plans as versioned Markdown documents

Status: Accepted.

Each Plan revision combines Runtime-owned structured metadata with an Agent-authored title, Markdown body, and declared dependencies. Runtime does not interpret Markdown checkboxes or turn its steps into executable state; Plan Handoff supplies the exact immutable document to a Build Run for interpretation. This keeps Plans readable and flexible without rebuilding the removed Plan–Execute–Replan workflow model.
