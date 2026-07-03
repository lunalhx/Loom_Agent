package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.service.ledger.ComparisonStatus;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of a ledger shadow comparison run.
 *
 * <p>Distinguishes:
 * <ul>
 *   <li>{@link #messageLcp} — count of identical prefix messages (strict).</li>
 *   <li>{@link #charLcp} — auxiliary character-level LCP of serialized snapshots.</li>
 *   <li>{@link #previousMessageCount} / {@link #currentMessageCount} —
 *       message counts used for prefix validation.</li>
 *   <li>{@link #firstDiffMessageIndex} — 0-based index of the first
 *       differing message, or -1 when identical.</li>
 * </ul>
 *
 * <p>For valid APPEND_ONLY: {@code messageLcp == previousMessageCount}
 * and {@code currentMessageCount > previousMessageCount}.
 *
 * <p>All text fields are desensitized — free of secrets, tokens, keys,
 * runIds, UUIDs, and paths. The diagnostic body is populated only when enabled.
 */
public final class LedgerShadowResult {

    private final Map<String, String> semanticCoverage;
    private final List<String> mandatoryGaps;
    private final ComparisonStatus comparisonStatus;
    private final int messageLcp;
    private final int charLcp;
    private final int previousMessageCount;
    private final int currentMessageCount;
    private final int firstDiffMessageIndex;
    private final int generation;
    private final String error;
    private final boolean diagnosticBodyEnabled;
    private final String diagnosticBody;

    public LedgerShadowResult(Map<String, String> semanticCoverage,
                              List<String> mandatoryGaps,
                              ComparisonStatus comparisonStatus,
                              int messageLcp,
                              int charLcp,
                              int previousMessageCount,
                              int currentMessageCount,
                              int firstDiffMessageIndex,
                              int generation,
                              String error,
                              boolean diagnosticBodyEnabled,
                              String diagnosticBody) {
        this.semanticCoverage = Collections.unmodifiableMap(semanticCoverage);
        this.mandatoryGaps = Collections.unmodifiableList(mandatoryGaps);
        this.comparisonStatus = comparisonStatus;
        this.messageLcp = messageLcp;
        this.charLcp = charLcp;
        this.previousMessageCount = previousMessageCount;
        this.currentMessageCount = currentMessageCount;
        this.firstDiffMessageIndex = firstDiffMessageIndex;
        this.generation = generation;
        this.error = error;
        this.diagnosticBodyEnabled = diagnosticBodyEnabled;
        this.diagnosticBody = diagnosticBody;
    }

    /** Semantic block → "covered" | "not_covered" | "n/a". */
    public Map<String, String> semanticCoverage() { return semanticCoverage; }

    /** Mandatory blocks (present in old prompt) not covered by ledger/StablePrefix. */
    public List<String> mandatoryGaps() { return mandatoryGaps; }

    /** Structural comparison result. */
    public ComparisonStatus comparisonStatus() { return comparisonStatus; }

    /** Count of identical prefix messages (strict). */
    public int messageLcp() { return messageLcp; }

    /** Auxiliary character-level LCP of serialized snapshots. */
    public int charLcp() { return charLcp; }

    /** Message count in the previous snapshot (0 on first call). */
    public int previousMessageCount() { return previousMessageCount; }

    /** Message count in the current snapshot. */
    public int currentMessageCount() { return currentMessageCount; }

    /** 0-based index of first differing message, or -1 when identical. */
    public int firstDiffMessageIndex() { return firstDiffMessageIndex; }

    /** Ledger generation at the time of comparison. */
    public int generation() { return generation; }

    /** Error message if shadow comparison itself failed (bounded), or null. */
    public String error() { return error; }

    /** Whether the detailed diagnostic body is populated. */
    public boolean diagnosticBodyEnabled() { return diagnosticBodyEnabled; }

    /** Desensitized diagnostic text, or null when disabled. */
    public String diagnosticBody() { return diagnosticBody; }

    /** True iff status is APPEND_ONLY and mandatory gaps are empty. */
    public boolean isClean() {
        return comparisonStatus == ComparisonStatus.APPEND_ONLY
                && mandatoryGaps.isEmpty()
                && error == null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("LedgerShadowResult{");
        sb.append("gen=").append(generation);
        sb.append(", status=").append(comparisonStatus);
        sb.append(", msgLcp=").append(messageLcp);
        sb.append(", charLcp=").append(charLcp);
        sb.append(", prevMsgCnt=").append(previousMessageCount);
        sb.append(", curMsgCnt=").append(currentMessageCount);
        sb.append(", firstDiffIdx=").append(firstDiffMessageIndex);
        sb.append(", coverage=").append(semanticCoverage);
        if (!mandatoryGaps.isEmpty()) {
            sb.append(", mandatoryGaps=").append(mandatoryGaps);
        }
        if (error != null) {
            sb.append(", error='").append(error).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}
