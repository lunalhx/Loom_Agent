package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Exact Conversation History position covered by an {@link AgentContextSnapshot}.
 * AgentCheckpoint stores only this anchor — never a full History copy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationHistoryAnchor {

    /** Next sequence number after the last durable entry this checkpoint covers. */
    private long nextSequence;

    /** Entry id of the last durable entry; null when History is empty. */
    private String lastEntryId;

    public static ConversationHistoryAnchor empty() {
        return ConversationHistoryAnchor.builder().nextSequence(0L).lastEntryId(null).build();
    }

    public static ConversationHistoryAnchor from(ConversationHistory history) {
        if (history == null) {
            return empty();
        }
        if (history.isEmpty()) {
            return ConversationHistoryAnchor.builder()
                    .nextSequence(history.nextSequence())
                    .lastEntryId(null)
                    .build();
        }
        var entries = history.entries();
        return ConversationHistoryAnchor.builder()
                .nextSequence(history.nextSequence())
                .lastEntryId(entries.get(entries.size() - 1).entryId())
                .build();
    }

    public static ConversationHistoryAnchor from(ConversationHistoryDocument document) {
        if (document == null || document.getEntries() == null || document.getEntries().isEmpty()) {
            return ConversationHistoryAnchor.builder()
                    .nextSequence(document == null ? 0L : document.getNextSequence())
                    .lastEntryId(null)
                    .build();
        }
        var entries = document.getEntries();
        return ConversationHistoryAnchor.builder()
                .nextSequence(document.getNextSequence())
                .lastEntryId(entries.get(entries.size() - 1).entryId())
                .build();
    }
}
