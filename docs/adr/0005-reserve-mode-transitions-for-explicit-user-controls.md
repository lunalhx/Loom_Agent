# Reserve Mode Transitions for explicit user controls

Only an explicit user control such as a dedicated command, startup option, or mode selector may change the Session's collaboration mode. Natural-language requests and Agent decisions may recommend a mode but cannot enter or leave it, so an imperative such as “start implementing” remains non-mutating while Plan Mode is active. This keeps the authorization boundary deterministic and avoids granting the model control over persistent Session permissions.
