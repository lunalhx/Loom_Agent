package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted Conversation History v1 — the append-only fact source for messages
 * and durable Tool results in a Session. Not embedded in Session or Checkpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationHistoryDocument {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private Integer schemaVersion;
    private String sessionId;

    @Builder.Default
    private List<ConversationHistoryEntry> entries = new ArrayList<>();

    private long nextSequence;

    public ConversationHistory toHistory() {
        return ConversationHistory.fromPersisted(
                entries == null ? List.of() : List.copyOf(entries),
                nextSequence);
    }

    public static ConversationHistoryDocument from(String sessionId, ConversationHistory history) {
        return ConversationHistoryDocument.builder()
                .schemaVersion(CURRENT_SCHEMA_VERSION)
                .sessionId(sessionId)
                .entries(history == null ? List.of() : new ArrayList<>(history.entries()))
                .nextSequence(history == null ? 0L : history.nextSequence())
                .build();
    }
}
