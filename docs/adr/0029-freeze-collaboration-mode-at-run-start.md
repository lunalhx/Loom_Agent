# Freeze collaboration mode at Run start

Status: Accepted.

Every root Run captures an immutable Run Mode Snapshot from its Session, and Delegate Runs inherit the parent's snapshot. That value drives prompting, tool projection, Effect policy, isolation, and audit for the entire Run; later Mode Transitions affect only future Runs. This prevents a concurrent Session update from widening permissions halfway through a tool loop and makes historical authorization decisions explainable.
