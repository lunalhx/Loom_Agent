# Fix the Plan Target at Run start

Runtime binds every Plan Run at startup either to `NEW` or to an exact Plan identity and revision; Plan Submission cannot choose or change that target. Existing Current Plan is the default revision target, while starting an unrelated Plan requires an explicit user New Plan control event, and a concurrent revision change causes submission to fail rather than overwrite. This prevents the Agent from guessing create-versus-revise intent and gives Plan revisions stable compare-and-set semantics.
