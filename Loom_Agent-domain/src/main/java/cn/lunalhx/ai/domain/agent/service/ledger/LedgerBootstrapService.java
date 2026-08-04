package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;

import java.util.Objects;

/**
 * Unified ledger bootstrap executed before every model call in
 * {@code RenderPromptNode}.
 *
 * <p>This is now purely an initializer + prefix refresher:
 * <ol>
 *   <li>New conversation (no ledger): initialize with the candidate prefix,
 *       append the raw user question as a {@code USER_TASK}.</li>
 *   <li>Existing ledger with a compatible StablePrefix (same workspace, tool
 *       and runtime signatures): reuse it — ordinary git status or doc churn
 *       does NOT rebuild the prefix, bump generation, or append history noise.</li>
 *   <li>Legacy two-field prefix or changed signatures: rebuild the prefix and
 *       replace it. No {@code [Config Change]} history entry is written.</li>
 *   <li>Consume any pending continuation as raw {@code USER_INPUT}.</li>
 *   <li>Set {@code ledgerReady = true}.</li>
 * </ol>
 *
 * <h3>Error handling</h3>
 * <p>On any exception, {@code ledgerReady} remains false and the exception
 * propagates so the caller can stop before invoking the model.
 */
public final class LedgerBootstrapService {

    private final ConversationHistoryAppendService appendService;
    private final ConversationHistoryInitializer initializer;

    public LedgerBootstrapService(ConversationHistoryAppendService appendService,
                                   ConversationHistoryInitializer initializer) {
        this.appendService = Objects.requireNonNull(appendService, "appendService must not be null");
        this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
    }

    /**
     * Bootstrap the ledger state for the given context using the candidate
     * StablePrefix built from current configuration.
     */
    public void bootstrap(AgentContext context, StablePrefix candidate) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        context.setLedgerReady(false);
        doBootstrap(context, candidate);
        context.setLedgerReady(true);
        context.setConfigFingerprint(candidate.fingerprint());
    }

    private void doBootstrap(AgentContext context, StablePrefix candidate) {
        if (!context.isLedgerActive()) {
            initializer.initializeNewConversation(context, candidate);
            applyPendingContinuationIfPresent(context);
            return;
        }

        StablePrefix existing = context.getStablePrefix();
        if (existing == null || existing.isLegacyTwoField() || !existing.matches(candidate)) {
            // Rebuild the prefix and replace it. No generation bump, no [Config Change] noise.
            context.setStablePrefix(candidate);
        }

        applyPendingContinuationIfPresent(context);
    }

    /**
     * If a pending continuation was set by {@code createContinuation}, append it
     * to the ledger as raw {@code USER_INPUT} and clear the pending.
     */
    private void applyPendingContinuationIfPresent(AgentContext context) {
        String pending = context.getPendingContinuation();
        if (pending == null || pending.isEmpty()) {
            return;
        }
        String eventKey = ConversationHistoryInitializer.eventKey(
                context.getRunId(), "continuation", "user_input");
        appendService.appendUserInput(context, pending, eventKey);
        context.setPendingContinuation(null);
    }

    /** Exposed for testing. */
    ConversationHistoryAppendService appendService() {
        return appendService;
    }
}
