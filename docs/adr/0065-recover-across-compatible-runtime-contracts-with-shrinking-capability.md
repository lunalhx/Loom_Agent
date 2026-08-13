# Recover across compatible Runtime contracts with shrinking capability

Status: Accepted.

Run Recovery freezes the safety and semantic contracts that define the Run—mode, authorization and grants, Execution Profile, Skill snapshots, Tool schemas, Effect boundaries, and recovery contracts—while allowing a new Attempt to use a different model/provider and a newer Runtime that natively accepts the checkpoint schema. Each Attempt records its actual Runtime/model/provider. Missing or contract-incompatible Tools are removed rather than replaced by same-name drifted implementations, newly discovered Tools or Skills never join the recovered Run, and capability may only stay equal or shrink; obsolete schemas fail explicitly without migration, compatibility layers, or fallback.
