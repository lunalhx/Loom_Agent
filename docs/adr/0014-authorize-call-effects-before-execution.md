# Authorize call effects before execution

Plan Mode authorizes every tool invocation from its Call Effect before execution; post-execution workspace checks may audit the result and expose classification errors but cannot be the primary permission boundary. Calls whose effects cannot be established in advance are classified as unknown and denied, because Repository State damage may be difficult to reverse and external mutations or disclosure cannot be reliably rolled back.
