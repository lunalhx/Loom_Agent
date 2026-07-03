package cn.lunalhx.ai.domain.agent.service.ledger;

/**
 * Result of comparing two {@link CanonicalSnapshot}s.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link #INITIAL} — first invocation, no previous snapshot to compare.</li>
 *   <li>{@link #IDENTICAL} — same generation, same message count, every message
 *       role+content identical.</li>
 *   <li>{@link #APPEND_ONLY} — same generation, previous messages are a strict
 *       prefix of current messages, no old message modified.</li>
 *   <li>{@link #REWRITTEN} — old message content, role, or order changed
 *       (not a strict prefix).</li>
 *   <li>{@link #INVALID_LEDGER} — duplicate sequence or event key within the
 *       current ledger.</li>
 *   <li>{@link #GENERATION_RESET} — generation changed since last snapshot;
 *       comparison baseline is rebuilt.</li>
 * </ul>
 */
public enum ComparisonStatus {
    INITIAL,
    IDENTICAL,
    APPEND_ONLY,
    REWRITTEN,
    INVALID_LEDGER,
    GENERATION_RESET
}
