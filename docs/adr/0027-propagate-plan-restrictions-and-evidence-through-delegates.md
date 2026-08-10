# Propagate Plan restrictions and evidence through Delegates

Status: Accepted, with evidence representation refined by ADR 0034.

A Delegate Run receives the intersection of its parent's effective permissions and its own stricter tool limits, and cannot perform Session controls, Plan management, Handoff, or Plan Submission. Only the root Agent may submit a Plan, while Evidence Receipts produced by a child's read-only tools retain child-run provenance and are folded into the root Plan Run's Plan Basis. This preserves delegation as bounded research without creating either a permission bypass or an evidence blind spot.
