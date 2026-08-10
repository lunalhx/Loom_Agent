# Keep Plan revisions linear

Each Plan has one linear revision history: Current Plan and Select Plan always refer to that Plan identity's latest revision, and Plan Submission may only append to the current head. Older revisions remain readable for audit but cannot be revised or handed off directly; restoring one requires revalidation and a new head revision, while independent alternatives use New Plan rather than branching. This avoids competing heads and execution of content that a later revision has superseded.
