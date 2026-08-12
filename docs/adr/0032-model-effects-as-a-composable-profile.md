# Model effects as a composable profile

Status: Accepted for the composable Effect Profile; the Plan-v1 Disposable Write limitation is superseded by ADR 0040 and the static Approval Requirement model by ADR 0041.

Each invocation has a composable Effect Profile rather than one mutually exclusive effect: a set of possible state effects, an independent Outbound Disclosure state, and a completeness flag. Policies evaluate every member, and Plan Mode fails closed when classification or disclosure is unknown; Approval Requirement remains separate and no probabilistic risk or confidence score participates in authorization. The profile is shared permission infrastructure and can represent mixed effects such as isolated tests or web research, but Plan v1 does not authorize `DISPOSABLE_WRITE` or provide the isolation needed to make arbitrary shell writes disposable.
