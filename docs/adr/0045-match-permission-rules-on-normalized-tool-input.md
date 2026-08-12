# Match permission rules on normalized tool input

Status: Accepted.

Permission Rules use a tool-specific matcher over normalized input rather than one raw-string glob engine. Shell input is parsed into executable units and matched by token prefix; compound invocations evaluate every unit and the strictest `DENY > ASK > ALLOW` result wins both across units and overlapping rules. If Shell input cannot be parsed completely, it is opaque and only a full exact rule may match, preventing a broad prefix from authorizing hidden substitutions, redirects, or control flow. Other tools share the same Permission Policy while supplying canonical match subjects appropriate to their inputs, such as normalized paths or domains.
