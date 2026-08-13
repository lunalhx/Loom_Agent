# Inherit and refresh Plan Basis across revisions

Status: Accepted.

A revision of an existing Plan starts its candidate Plan Basis from the previous revision's Evidence Receipts. Receipts produced by the new Plan Run replace inherited receipts with the same Evidence Key, new keys are appended, and inherited keys that are not re-observed remain in the candidate Basis and cannot be removed by the Agent. Replacing an inherited receipt across Runs is not same-Run Evidence Drift; any stale inherited receipt that was not refreshed still causes Plan Conflict during Submission revalidation. A New Plan starts with no inherited receipts. This preserves dependencies behind unchanged Plan content and provides a deterministic way to refresh a Stale Plan, at the cost of a Plan's Basis becoming conservatively broader over successive revisions.
