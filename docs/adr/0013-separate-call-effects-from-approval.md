# Separate call effects from approval

Status: Accepted, with its Plan Mode allowance for Disposable Artifacts superseded by ADR 0033.

Each tool invocation is classified by its concrete Call Effect, while Approval Requirement is determined independently by the base Session policy. Plan Mode authorizes calls from their effects rather than a static `risky` boolean: read-only and Disposable Artifact effects may remain eligible, Repository State and external mutations are forbidden, and unknown effects fail closed. How individual protocol adapters such as MCP supply classification evidence is a separate integration decision.
