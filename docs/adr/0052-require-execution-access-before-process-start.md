# Require execution access before process start

Status: Accepted.

An Agent must declare a Build Tool Call's minimal external paths and other supported host resources in an Execution Request before the process starts, and Runtime must satisfy that request from an existing or newly user-approved Execution Grant. An undeclared sandbox violation terminates the call without automatic permission expansion or retry, because the first attempt may already have produced workspace side effects and replaying it could duplicate or compound them. Any continuation is a new Tool Call; a failed call that changed the Workspace is reported as partial success with its affected paths rather than being treated as if it never ran. Sandboxed Shell network access is not a supported request in the initial capability.
