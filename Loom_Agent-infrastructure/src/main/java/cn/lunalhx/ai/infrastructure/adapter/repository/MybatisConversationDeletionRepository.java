package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationDeletion;
import cn.lunalhx.ai.infrastructure.dao.ConversationDeletionDao;
import cn.lunalhx.ai.infrastructure.dao.po.ConversationDeletionPO;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class MybatisConversationDeletionRepository implements ConversationDeletionRepository {

    private final ConversationDeletionDao dao;

    public MybatisConversationDeletionRepository(ConversationDeletionDao dao) {
        this.dao = dao;
    }

    @Override
    public ConversationDeletion save(ConversationDeletion deletion) {
        dao.upsert(toPo(deletion));
        return deletion;
    }

    @Override
    public Optional<ConversationDeletion> find(String conversationId) {
        return Optional.ofNullable(dao.selectByConversationId(conversationId)).map(this::toEntity);
    }

    @Override
    public List<ConversationDeletion> findPendingWork() {
        return dao.selectPendingWork().stream().map(this::toEntity).toList();
    }

    @Override
    public boolean claimTask(String conversationId, String lockedBy, String lockExpiresAt) {
        return dao.claimTask(conversationId, lockedBy, lockExpiresAt) > 0;
    }

    @Override
    public boolean updateStatus(String conversationId, String status, int retryCount, String lastError) {
        return dao.updateStatus(conversationId, status, retryCount, lastError) > 0;
    }

    @Override
    public boolean updateStatusAndReleaseLock(String conversationId, String status, int retryCount, String lastError) {
        return dao.updateStatusAndReleaseLock(conversationId, status, retryCount, lastError) > 0;
    }

    @Override
    public boolean markCompleted(String conversationId) {
        return dao.markCompleted(conversationId) > 0;
    }

    @Override
    public boolean resetForRetry(String conversationId) {
        return dao.resetForRetry(conversationId) > 0;
    }

    @Override
    public void releaseLock(String conversationId) {
        dao.releaseLock(conversationId);
    }

    @Override
    public List<ConversationDeletion> findStaleTasks(String staleThreshold) {
        return dao.selectStaleTasks(staleThreshold).stream().map(this::toEntity).toList();
    }

    private ConversationDeletionPO toPo(ConversationDeletion e) {
        ConversationDeletionPO po = new ConversationDeletionPO();
        po.setConversationId(e.getConversationId());
        po.setStatus(e.getStatus());
        po.setRequestedAt(e.getRequestedAt());
        po.setUpdatedAt(e.getUpdatedAt());
        po.setCompletedAt(e.getCompletedAt());
        po.setRetryCount(e.getRetryCount());
        po.setLastError(e.getLastError());
        return po;
    }

    private ConversationDeletion toEntity(ConversationDeletionPO po) {
        return ConversationDeletion.builder()
                .conversationId(po.getConversationId())
                .status(po.getStatus())
                .requestedAt(po.getRequestedAt())
                .updatedAt(po.getUpdatedAt())
                .completedAt(po.getCompletedAt())
                .retryCount(po.getRetryCount())
                .lastError(po.getLastError())
                .build();
    }
}
