# Make Plan Mode constraints outrank Tool Approval

Status: Accepted.

Plan Mode is a hard authorization boundary: Tool Approval, including automatic approval, cannot authorize a mutation while the Session remains in Plan Mode. A user who wants to perform a mutating action must explicitly leave Plan Mode first. This sacrifices one-off flexibility so that the mode has a reliable product meaning and cannot be weakened accidentally by approval configuration or tool-specific defaults.
