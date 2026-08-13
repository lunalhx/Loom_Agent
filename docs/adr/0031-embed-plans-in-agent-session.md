# Embed Plans in AgentSession

Status: Accepted.

Plan identities, immutable-in-domain revision lists, Current Plan, and collaboration mode are stored as dedicated AgentSession fields, separate from the conversation ledger, and saved through the existing atomic Session-file replacement. A separate Plan Catalog would add repositories, cross-file consistency, recovery, and redaction boundaries without a current need for large histories, cross-Session access, or multi-client indexing. First-class Artifact semantics therefore come from identity and lifecycle rather than requiring separate physical storage.
