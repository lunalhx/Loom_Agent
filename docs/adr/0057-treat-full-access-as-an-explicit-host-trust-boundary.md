# Treat Full Access as an explicit host trust boundary

Status: Accepted.

Full Access preserves the normal Permission Policy evaluator and blocks high-impact or catastrophic command forms that the Built-in Safety Floor recognizes, but it does not claim that syntactic command classification can constrain behavior inside scripts, plugins, interpreters, or arbitrary binaries. Without an enforced Execution Profile or system-call boundary, such code has the current host user's filesystem and network authority and can perform an indirect equivalent of a denied command. Full Access activation must disclose this limitation clearly and is therefore an explicit host trust decision comparable to a bypass mode, while sandboxed Plan and Build remain the profiles that provide enforceable capability containment.
