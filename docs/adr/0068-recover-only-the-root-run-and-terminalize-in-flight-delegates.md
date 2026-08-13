# Recover only the root Run and terminalize in-flight Delegates

Status: Accepted.

The root Run is the only recovery unit and presents one Recovery Required/Ambiguity Review flow to the user. A Delegate Result already durable in Conversation History survives, but an in-flight or completed-without-durable-parent-result Delegate becomes an Interrupted Delegate Call; its child Run terminates as `INTERRUPTED_WITH_PARENT`, its uncertain effects are surfaced through the root Run, and its process, Attempt, internal reasoning, and cursor are never resumed. The recovered root may later create a new Delegate Run through the ordinary capability and permission boundaries, avoiding nested leases, independent authority, and multiple recovery entry points.
