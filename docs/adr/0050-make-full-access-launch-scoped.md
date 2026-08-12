# Make Full Access launch-scoped

Status: Accepted.

Full Access can be enabled only through an explicit user control for the current Runtime launch, with a clear confirmation and a persistent `FULL ACCESS` indicator; it cannot be activated by natural language, an Agent, a Tool Call, or project configuration. Each root Build Run freezes the selected Execution Profile at start, while later changes affect only later Runs. Full Access is not persisted in Session state, must be selected again after exit or resume, and `/new` returns to the ordinary Workspace profile, preventing a stale conversation from silently recovering host-level authority.
