package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;

import java.util.Optional;

/**
 * Append-only Conversation History store. Session and AgentCheckpoint must not
 * copy History; checkpoints only store an exact History anchor.
 */
public interface ConversationHistoryRepository {

    Optional<ConversationHistoryDocument> find(String sessionId);

    /**
     * Persist the authoritative History for a Session. Existing entries must be
     * an unchanged prefix of the new document (append-only); shrinking or
     * mutating prior entries is rejected.
     */
    ConversationHistoryDocument save(ConversationHistoryDocument document);

    /** Convenience: persist the in-memory History under {@code sessionId}. */
    default ConversationHistoryDocument save(String sessionId, ConversationHistory history) {
        return save(ConversationHistoryDocument.from(sessionId, history));
    }
}
