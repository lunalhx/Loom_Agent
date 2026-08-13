# Bound call effects by runtime capabilities

Status: Accepted.

Call Effect represents the maximum effect still possible within the invocation's Runtime-enforced Execution Profile, starting from the tool's conservative Capability Envelope and narrowing only with trusted deterministic evidence. Arbitrary shell text is not treated as proof of safety: an unconstrained shell retains its broad mutation capabilities, while only enforced filesystem, network, credential, or process isolation may narrow its effect. This model is shared permission infrastructure rather than Plan Mode-specific command classification.
