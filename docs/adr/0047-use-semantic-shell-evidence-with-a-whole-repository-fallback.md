# Use semantic Shell Evidence with a whole-Repository fallback

Status: Accepted.

Plan Shell commands with a trusted semantic Evidence Adapter produce precise Receipts using existing file, directory, search, Git, or equivalent revalidation scopes, and compound commands merge the scopes of every executable unit. A successful command without an Adapter produces a coarse Receipt over the entire Repository State, so any later repository change makes the Plan stale rather than leaving an evidence blind spot. Runtime neither infers dependencies from stdout nor adds platform-specific syscall tracing; adapters can be added incrementally while the whole-Repository fallback preserves conservative correctness for Maven, Gradle, tests, project scripts, and opaque commands.
