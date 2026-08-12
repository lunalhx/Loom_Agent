# Keep project permission rules restrictive

Status: Accepted.

Permission rules distributed with Repository State may select `ASK` or `DENY` but may not grant `ALLOW`, because an untrusted repository must not authorize execution of its own commands or code merely by being opened. Runtime built-in rules define an unrelaxable safety floor; only user-local and bounded Session sources may grant authority beyond the default action, while project rules remain useful as version-controlled team restrictions. Loom Agent does not add a separate Repository Trust State for this capability; users who trust a project grant that authority through local rules outside Repository State.
