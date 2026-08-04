package cn.lunalhx.ai.domain.agent.service.context;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * Immutable result of building a context view via {@link ContextManager}.
 *
 * <p>Contains the rendered system prefix, ordered {@link ChatMessage} list,
 * render metadata, and whether the model call must be blocked (budget
 * overflow). The underlying {@code ConversationHistory} is never mutated.
 */
public final class ContextBuildResult {

    private final String systemPrefix;
    private final List<ChatMessage> messages;
    private final ContextRenderMetadata metadata;
    private final boolean blocked;
    private final String blockedReason;

    ContextBuildResult(String systemPrefix, List<ChatMessage> messages,
                       ContextRenderMetadata metadata, boolean blocked, String blockedReason) {
        this.systemPrefix = systemPrefix;
        this.messages = List.copyOf(messages);
        this.metadata = metadata;
        this.blocked = blocked;
        this.blockedReason = blockedReason;
    }

    public String systemPrefix() { return systemPrefix; }
    public List<ChatMessage> messages() { return messages; }
    public ContextRenderMetadata metadata() { return metadata; }
    public boolean blocked() { return blocked; }
    public String blockedReason() { return blockedReason; }

    /** Concatenated rendered text of all messages plus prefix, for budget estimation. */
    public String budgetText() {
        StringBuilder sb = new StringBuilder();
        if (systemPrefix != null) {
            sb.append(systemPrefix);
        }
        for (ChatMessage m : messages) {
            sb.append(m.getContent());
        }
        return sb.toString();
    }

    public static ContextBuildResult blocked(String reason) {
        return new ContextBuildResult("", List.of(), new ContextRenderMetadata(), true, reason);
    }

    /**
     * Per-section render metrics plus the ordered reduction log.
     *
     * <p>{@code sections} holds, for each of the five fixed sections, the raw
     * length, the budget, and the final rendered length. {@code reductionLog}
     * records each actual trimming step in order (e.g.
     * {@code ["relevant_memory->floor", "history->floor"]}).
     */
    public record SectionMetrics(int rawChars, int budgetChars, int renderedChars) {
    }

    public record ContextRenderMetadata(
            int totalChars,
            int totalBudgetChars,
            boolean overBudget,
            Map<String, Integer> sectionRawChars,
            Map<String, Integer> sectionBudgetChars,
            Map<String, Integer> sectionRenderedChars,
            List<String> sectionOrder,
            List<String> reductionLog,
            boolean reductionEnabled,
            int historyMerged,
            int historySummarized,
            int historyDeduped,
            int summaryReuseCount,
            int relevantMemorySelected,
            String currentRequest,
            boolean currentRequestPreserved,
            int currentRequestChars) {

        public ContextRenderMetadata() {
            this(0, 0, false, Map.of(), Map.of(), Map.of(), List.of(), List.of(), true,
                    0, 0, 0, 0, 0, "", false, 0);
        }

        // Backward-compatible accessors used by existing tests / middleware.
        public List<String> reductions() { return reductionLog; }
        public Map<String, Integer> rawChars() { return sectionRawChars; }
        public Map<String, Integer> renderedChars() { return sectionRenderedChars; }
    }
}
