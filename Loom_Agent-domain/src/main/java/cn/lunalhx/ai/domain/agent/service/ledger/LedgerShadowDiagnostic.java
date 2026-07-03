package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.LedgerShadowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates ledger shadow-mode comparison.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Stores the previous {@link CanonicalSnapshot} for cross-round
 *       message-level prefix comparison.</li>
 *   <li>On generation change, the previous snapshot is cleared so the
 *       next comparison gets {@link ComparisonStatus#GENERATION_RESET}
 *       and rebuilds the baseline.</li>
 *   <li>Diagnostic body is <b>off by default</b>.</li>
 *   <li>Any failure is caught, logged at WARN with a bounded message,
 *       and returns an error-bearing result — the agent main loop
 *       continues unaffected.</li>
 * </ul>
 */
public final class LedgerShadowDiagnostic {

    private static final Logger log = LoggerFactory.getLogger(LedgerShadowDiagnostic.class);
    private static final int MAX_ERROR_CHARS = 500;

    private final LedgerShadowComparator comparator;
    private CanonicalSnapshot previousSnapshot;
    private boolean diagnosticBodyEnabled;

    public LedgerShadowDiagnostic() {
        this.comparator = new LedgerShadowComparator();
        this.previousSnapshot = null;
        this.diagnosticBodyEnabled = false;
    }

    public void setDiagnosticBodyEnabled(boolean enabled) {
        this.diagnosticBodyEnabled = enabled;
    }

    public boolean isDiagnosticBodyEnabled() {
        return diagnosticBodyEnabled;
    }

    /** Exposed for testing. */
    public CanonicalSnapshot previousSnapshot() {
        return previousSnapshot;
    }

    /**
     * Run the shadow comparison for the current prompt render.
     *
     * <p>On generation change, the previous snapshot is cleared before
     * comparison so that the result is {@code GENERATION_RESET}.
     */
    public LedgerShadowResult compareAndLog(AgentContext context, String oldPromptText) {
        Objects.requireNonNull(context, "context must not be null");

        try {
            // Capture previous BEFORE checking generation so comparator can
            // detect GENERATION_RESET vs INITIAL correctly.
            CanonicalSnapshot prev = previousSnapshot;

            LedgerShadowResult result = comparator.compare(
                    context, oldPromptText, prev, diagnosticBodyEnabled);

            // After comparison: store current snapshot for next round.
            // Generation change means the current snapshot becomes the new baseline.
            if (context.getConversationLedger() != null) {
                previousSnapshot = CanonicalSnapshot.from(context);
            }

            logResult(result);
            return result;

        } catch (Exception e) {
            String boundedMsg = truncate(e.toString(), MAX_ERROR_CHARS);
            log.warn("Ledger shadow comparison failed (agent flow unaffected): {}", boundedMsg, e);

            return new LedgerShadowResult(
                    Map.of(), List.of(),
                    ComparisonStatus.INITIAL,
                    0, 0, 0, 0, -1,
                    context.getGeneration(),
                    boundedMsg, false, null);
        }
    }

    private void logResult(LedgerShadowResult result) {
        if (result.error() != null) {
            log.warn("Shadow comparison error: {}", result.error());
            return;
        }
        log.info("Shadow status={} gen={} msgLcp={} charLcp={} prevCnt={} curCnt={} firstDiffIdx={} coverage={} gaps={}",
                result.comparisonStatus(), result.generation(),
                result.messageLcp(), result.charLcp(),
                result.previousMessageCount(), result.currentMessageCount(),
                result.firstDiffMessageIndex(),
                result.semanticCoverage(), result.mandatoryGaps());

        if (!result.mandatoryGaps().isEmpty()) {
            log.warn("Shadow mandatory coverage gaps: {}", result.mandatoryGaps());
        }
        if (result.diagnosticBodyEnabled() && result.diagnosticBody() != null) {
            log.debug("Shadow diagnostic body:\n{}", result.diagnosticBody());
        }
    }

    static String truncate(String s, int maxChars) {
        if (s == null) return null;
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "...[truncated]";
    }
}
