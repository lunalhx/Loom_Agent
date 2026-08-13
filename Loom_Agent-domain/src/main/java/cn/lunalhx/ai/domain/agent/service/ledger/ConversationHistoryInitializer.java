package cn.lunalhx.ai.domain.agent.service.ledger;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;

import java.util.Objects;

/**
 * Initializes the {@link ConversationHistory} for a new agent run.
 *
 * <p>Uses deterministic event keys so that retry does not produce duplicate
 * entries. Obsolete snapshot shapes are rejected at restore time rather than
 * migrated here.
 */
public final class ConversationHistoryInitializer {

    public ConversationHistoryInitializer() {
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

        String runId = context.getRunId();
        String question = context.getQuestion();

        context.setStablePrefix(stablePrefix);
        context.setGeneration(0);

        ConversationHistory ledger = new ConversationHistory();
        if (question != null && !question.isEmpty()) {
            String eventKey = eventKey(runId, "init", "user_task");
            ledger.appendWithEventKey("user", question,
                    ConversationEntryType.USER_TASK, eventKey);
        }
        context.setConversationHistory(ledger);
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
