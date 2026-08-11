# Compose Plan Mode as a restrictive permission layer

Plan Mode only removes authority from the Session's existing tool allowlist, approval policy, sandbox, workspace scope, and external-access rules; it never grants authority that the Session did not already have. Effective permission is the intersection of the base Session policy and the Plan Mode policy. This keeps mode changes monotonic and prevents a user from gaining read, shell, network, MCP, or external-directory access merely by entering Plan Mode.
