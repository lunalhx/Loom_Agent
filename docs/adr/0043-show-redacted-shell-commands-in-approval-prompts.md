# Show redacted Shell commands in approval prompts

Status: Accepted.

A Shell approval prompt shows the current user the redacted normalized command, parsed subcommands, workspace, enforced Execution Profile, matching rule source, and reason instead of exposing only argument length and hash, because an approval is not meaningful when its subject cannot be inspected. The display is ephemeral and applies configured secret redaction and output limits; persistent traces, Sessions, and reports continue to store only safe summaries rather than the unredacted command. This accepts that unknown inline secrets cannot be detected perfectly in exchange for reviewable authorization, while keeping known secrets and durable artifacts protected.
