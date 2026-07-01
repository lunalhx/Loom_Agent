package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.ConversationDeletion;

import java.util.List;
import java.util.Optional;

public interface ConversationDeletionRepository {

    ConversationDeletion save(ConversationDeletion deletion);

    Optional<ConversationDeletion> find(String conversationId);

    List<ConversationDeletion> findPendingWork();

    boolean claimTask(String conversationId, String lockedBy, String lockExpiresAt);

    boolean updateStatus(String conversationId, String status, int retryCount, String lastError);

    boolean markCompleted(String conversationId);

    boolean resetForRetry(String conversationId);

    void releaseLock(String conversationId);

    List<ConversationDeletion> findStaleTasks(String staleThreshold);
}
