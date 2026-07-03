package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;

import java.util.Objects;

/**
 * Unified ledger bootstrap executed before every model call in
 * {@code RenderPromptNode}.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Build candidate StablePrefix from current config (tools, skills, role,
 *       path scope, spawn capability).</li>
 *   <li>New conversation (no ledger): init with candidate prefix, generation=0,
 *       append user_task.</li>
 *   <li>No stored StablePrefix (legacy v2 or uninitialized): bump generation,
 *       write migration marker, set candidate as new prefix.</li>
 *   <li>Stored StablePrefix exists:
 *     <ul>
 *       <li>Same fingerprint: keep prefix and generation.</li>
 *       <li>Different fingerprint: generation+1, immediately set candidate as
 *           new StablePrefix, append [{@code [Config Change]}] note.</li>
 *     </ul>
 *   </li>
 *   <li>Apply pending continuation (if any).</li>
 *   <li>Set {@code ledgerReady = true}.</li>
 * </ol>
 *
 * <h3>Error handling</h3>
 * <p>On any exception, {@code ledgerReady} remains false and the exception
 * propagates so the caller can stop before invoking the model.
 */
public final class LedgerBootstrapService {

    private final ConversationLedgerAppendService appendService;
    private final ConversationLedgerInitializer initializer;

    public LedgerBootstrapService(ConversationLedgerAppendService appendService,
                                   ConversationLedgerInitializer initializer) {
        this.appendService = Objects.requireNonNull(appendService, "appendService must not be null");
        this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
    }

    /**
     * Bootstrap the ledger state for the given context using the candidate
     * StablePrefix built from current configuration.
     *
     * @param context  the agent context
     * @param candidate StablePrefix built from current tools, skills, role, etc.
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
        // ---- Case 1: New conversation (no ledger) ----
        if (!context.isLedgerActive()) {
            initializer.initializeNewConversation(context, candidate);
            return;
        }

        StablePrefix existing = context.getStablePrefix();

        // ---- Case 2: No existing StablePrefix (v2 snapshot or uninitialized) ----
        if (existing == null) {
            migrateFromNoPrefix(context, candidate);
            applyPendingContinuationIfPresent(context);
            return;
        }

        // ---- Case 3: Existing StablePrefix — compare fingerprints ----
        if (candidate.fingerprint().equals(existing.fingerprint())) {
            // Same config — keep prefix and generation
            applyPendingContinuationIfPresent(context);
            return;
        }

        // ---- Case 4: Config changed — new generation with new prefix ----
        int newGen = context.getGeneration() + 1;
        context.setGeneration(newGen);
        context.setStablePrefix(candidate); // immediately use new prefix

        // Append config change marker to ledger (with full StablePrefix fingerprints)
        String note = ControlUpdateTexts.renderConfigChangeNote(
                existing.fingerprint(), candidate.fingerprint(), newGen);
        String eventKey = ConversationLedgerInitializer.eventKey(
                context.getRunId(), "config_change", "generation_" + newGen);
        appendService.appendControlUpdate(context, note, eventKey);

        applyPendingContinuationIfPresent(context);
    }

    /**
     * Migrate when no StablePrefix exists (v2 snapshot, or ledger without prefix).
     */
    private void migrateFromNoPrefix(AgentContext context, StablePrefix candidate) {
        int newGen = context.getGeneration() + 1;
        context.setGeneration(newGen);
        context.setStablePrefix(candidate);

        String note = "[Migration] Ledger initialized without prior StablePrefix. "
                + "New generation " + newGen
                + " started with current configuration.";
        String eventKey = ConversationLedgerInitializer.eventKey(
                context.getRunId(), "migrate", "generation_" + newGen);
        appendService.appendControlUpdate(context, note, eventKey);
    }

    /**
     * If a pending continuation was set by {@code createContinuation}, append it
     * to the ledger now (after any config change marker) and clear the pending.
     */
    private void applyPendingContinuationIfPresent(AgentContext context) {
        String pending = context.getPendingContinuation();
        if (pending == null || pending.isEmpty()) {
            return;
        }
        String text = ControlUpdateTexts.renderContinuation(pending);
        String eventKey = ConversationLedgerInitializer.eventKey(
                context.getRunId(), "continuation", "user_input");
        appendService.appendUserInput(context, text, eventKey);
        context.setPendingContinuation(null);
    }

    /** Exposed for testing. */
    ConversationLedgerAppendService appendService() {
        return appendService;
    }
}
