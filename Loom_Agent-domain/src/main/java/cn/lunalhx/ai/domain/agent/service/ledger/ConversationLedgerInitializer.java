package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;

import java.util.Objects;

/**
 * Initializes or migrates the {@link ConversationLedger} for a new or resumed
 * agent run according to the configured {@code conversationLedger} mode.
 *
 * <h3>New conversation</h3>
 * <ol>
 *   <li>Freezes the given stable prefix on the context.</li>
 *   <li>Creates a fresh ledger and appends one {@code USER_TASK} entry with
 *       the current question as content.</li>
 *   <li>Sets generation to 0.</li>
 * </ol>
 *
 * <h3>V2 snapshot migration</h3>
 * <p>When an existing v2 snapshot (no ledger state) is resumed with
 * shadow or enabled mode, we do NOT claim the old history satisfies
 * append-only. Instead:
 * <ol>
 *   <li>A new generation is created from the current durable state.</li>
 *   <li>A {@code SYSTEM_NOTE} migration marker entry is appended to the
 *       ledger recording the generation bump and the fact that previous
 *       history is not part of the ledger.</li>
 *   <li>The stable prefix is frozen.</li>
 *   <li>The current question is appended as a {@code USER_TASK} entry.</li>
 * </ol>
 *
 * <h3>Mode gating</h3>
 * <p>When both {@code enabled} and {@code shadowEnabled} are false,
 * initialization is a no-op — no ledger state is created.
 *
 * <h3>Idempotency</h3>
 * <p>Uses deterministic event keys so that checkpoint resume or retry
 * does not produce duplicate entries.
 */
public final class ConversationLedgerInitializer {

    private final AgentRuntimeProperties.ConversationLedgerProperties config;

    public ConversationLedgerInitializer(
            AgentRuntimeProperties.ConversationLedgerProperties config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Returns {@code true} if either ledger mode is active.
     */
    public boolean isActive() {
        return Boolean.TRUE.equals(config.getEnabled())
                || Boolean.TRUE.equals(config.getShadowEnabled());
    }

    /**
     * Initialize ledger state for a brand-new conversation.
     *
     * @param context      the agent context (must have runId and question set)
     * @param stablePrefix the frozen stable prefix to attach
     * @throws NullPointerException if context or stablePrefix is null
     */
    public void initializeNewConversation(AgentContext context, StablePrefix stablePrefix) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(stablePrefix, "stablePrefix must not be null");

        if (!isActive()) {
            return;
        }

        String runId = context.getRunId();
        String question = context.getQuestion();

        context.setStablePrefix(stablePrefix);
        context.setGeneration(0);

        ConversationLedger ledger = new ConversationLedger();
        if (question != null && !question.isEmpty()) {
            String eventKey = eventKey(runId, "init", "user_task");
            ledger.appendWithEventKey("user", question,
                    LedgerStableType.USER_TASK, eventKey);
        }
        context.setConversationLedger(ledger);
    }

    /**
     * Migrate from a v2 snapshot (no ledger state) to v3 ledger state.
     *
     * <p>The v2 snapshot's durable state is the starting point for a new
     * generation. Previous history is not retroactively claimed as
     * append-only; the ledger starts fresh with a migration marker.
     *
     * @param context      the agent context restored from v2 snapshot
     * @param stablePrefix the frozen stable prefix to attach
     * @param v2Snapshot   the v2 snapshot being resumed from (for context)
     * @throws NullPointerException if any argument is null
     */
    public void migrateFromV2(AgentContext context, StablePrefix stablePrefix,
                              AgentContextSnapshot v2Snapshot) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(stablePrefix, "stablePrefix must not be null");
        Objects.requireNonNull(v2Snapshot, "v2Snapshot must not be null");

        if (!isActive()) {
            return;
        }

        String runId = context.getRunId();

        // Create a fresh ledger — do not claim old history is append-only
        ConversationLedger ledger = new ConversationLedger();

        // Bump generation: v2 starts a new generation from durable state
        int newGeneration = v2Snapshot.getGeneration() + 1;
        context.setGeneration(newGeneration);
        context.setStablePrefix(stablePrefix);

        // Append migration marker
        String migrationMsg = String.format(
                "Ledger migration from v2 snapshot: new generation %d. "
                        + "Previous conversation history is not part of this ledger.",
                newGeneration);
        String migrationKey = eventKey(runId, "migrate", "generation_" + newGeneration);
        ledger.appendWithEventKey("system", migrationMsg,
                LedgerStableType.SYSTEM_NOTE, migrationKey);

        // Append the current question as user_task
        String question = context.getQuestion();
        if (question != null && !question.isEmpty()) {
            String taskKey = eventKey(runId, "init", "user_task");
            ledger.appendWithEventKey("user", question,
                    LedgerStableType.USER_TASK, taskKey);
        }

        context.setConversationLedger(ledger);
    }

    /**
     * Build a deterministic event key for idempotency.
     *
     * <p>Format: {@code "{runId}:{stepOrPhase}:{eventType}"}
     */
    public static String eventKey(String runId, String stepOrPhase, String eventType) {
        return runId + ":" + stepOrPhase + ":" + eventType;
    }
}
