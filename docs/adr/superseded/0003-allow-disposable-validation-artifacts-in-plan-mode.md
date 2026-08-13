# Protect Repository State instead of forbidding every filesystem write

Status: Superseded by ADR 0033.

Plan Mode must preserve Repository State and external system state, but may create Disposable Artifacts as an incidental result of plan-improving validation such as builds, tests, or static analysis. Commands that rewrite source, configuration, documentation, lock files, existing user files, the Git index or history remain forbidden. This permits evidence-based planning without weakening the promise that Plan Mode does not implement the proposed change.
