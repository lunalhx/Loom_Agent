# Project effective tools without trusting the Catalog

Each Run presents the model with an Effective Tool Catalog derived from base permissions and collaboration mode: Plan Mode omits tools whose every invocation is forbidden, while dynamic tools remain visible when some calls can pass per-call Effect checks. Runtime Gate still authorizes every invocation and rejects hidden, stale, or fabricated calls before approval, so catalog projection improves model behavior without becoming the security boundary.
