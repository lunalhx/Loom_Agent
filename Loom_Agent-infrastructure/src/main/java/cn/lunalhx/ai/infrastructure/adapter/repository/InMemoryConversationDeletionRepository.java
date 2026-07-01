package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationDeletion;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryConversationDeletionRepository implements ConversationDeletionRepository {

    private final ConcurrentMap<String, ConversationDeletion> store = new ConcurrentHashMap<>();

    @Override
    public ConversationDeletion save(ConversationDeletion deletion) {
        deletion.setUpdatedAt(Instant.now());
        store.put(deletion.getConversationId(), deletion);
        return deletion;
    }

    @Override
    public Optional<ConversationDeletion> find(String conversationId) {
        return Optional.ofNullable(store.get(conversationId));
    }

    @Override
    public List<ConversationDeletion> findPendingWork() {
        return store.values().stream()
                .filter(d -> "REQUESTED".equals(d.getStatus())
                        || "WAITING_FOR_RUNS".equals(d.getStatus())
                        || "PURGING".equals(d.getStatus()))
                .sorted(Comparator.comparing(ConversationDeletion::getRequestedAt))
                .limit(10)
                .toList();
    }

    @Override
    public boolean claimTask(String conversationId, String lockedBy, String lockExpiresAt) {
        ConversationDeletion existing = store.get(conversationId);
        if (existing == null) return false;
        // In-memory: always claim since there's no real locking
        return true;
    }

    @Override
    public boolean updateStatus(String conversationId, String status, int retryCount, String lastError) {
        ConversationDeletion existing = store.get(conversationId);
        if (existing == null) return false;
        existing.setStatus(status);
        existing.setRetryCount(retryCount);
        existing.setLastError(lastError);
        existing.setUpdatedAt(Instant.now());
        return true;
    }

    @Override
    public boolean updateStatusAndReleaseLock(String conversationId, String status, int retryCount, String lastError) {
        return updateStatus(conversationId, status, retryCount, lastError);
    }

    @Override
    public boolean markCompleted(String conversationId) {
        ConversationDeletion existing = store.get(conversationId);
        if (existing == null) return false;
        existing.setStatus("COMPLETED");
        existing.setCompletedAt(Instant.now());
        existing.setUpdatedAt(Instant.now());
        return true;
    }

    @Override
    public boolean resetForRetry(String conversationId) {
        ConversationDeletion existing = store.get(conversationId);
        if (existing == null) return false;
        existing.setStatus("REQUESTED");
        existing.setRetryCount(0);
        existing.setLastError(null);
        existing.setUpdatedAt(Instant.now());
        return true;
    }

    @Override
    public void releaseLock(String conversationId) {
        // no-op in memory
    }

    @Override
    public List<ConversationDeletion> findStaleTasks(String staleThreshold) {
        return findPendingWork();
    }
}
