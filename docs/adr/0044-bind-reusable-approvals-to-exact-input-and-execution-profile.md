# Bind reusable approvals to exact input and Execution Profile

Status: Accepted.

Tool Approval offers allow once, allow for the current Session, always allow in the current Workspace, or deny the current call. Reusable grants match the complete normalized Tool Call, live in the bounded Session or user-local Workspace source rather than Repository State, and apply only under the same or a stricter Execution Profile; therefore a command approved inside the Plan Shell sandbox is not silently approved for an unsandboxed Build Shell. Runtime never broadens an approval into a command-prefix rule, so wider patterns require an explicit user-local policy edit.
