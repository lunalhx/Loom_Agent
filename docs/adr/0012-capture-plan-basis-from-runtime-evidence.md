# Capture Plan Basis from Runtime evidence

Status: Accepted, refined by ADRs 0034 and 0036.

The Runtime derives each Plan Basis from the Repository State actually observed during Plan Mode. The Agent may add dependencies or assumptions at Plan Submission but cannot remove captured Plan Evidence; later revisions inherit still-relevant evidence and add newly observed evidence. This makes freshness checks depend on auditable tool activity rather than an incomplete model-authored path list, while avoiding invalidation by repository content that the Plan never observed or declared.
