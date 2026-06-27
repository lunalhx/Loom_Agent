package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.model.entity.WorkspaceUndoLock;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryWorkspaceUndoLockRepository implements WorkspaceUndoLockRepository {

    private final ConcurrentMap<String, WorkspaceUndoLock> locks = new ConcurrentHashMap<>();

    @Override
    public boolean acquire(String workspace, String runId, Instant expiresAt) {
        WorkspaceUndoLock existing = locks.get(workspace);
        if (existing != null && existing.getExpiresAt().isAfter(Instant.now())) {
            return false;
        }
        WorkspaceUndoLock lock = WorkspaceUndoLock.builder()
                .lockId(UUID.randomUUID().toString())
                .workspace(workspace)
                .holderRunId(runId)
                .acquiredAt(Instant.now())
                .expiresAt(expiresAt)
                .build();
        locks.put(workspace, lock);
        return true;
    }

    @Override
    public boolean release(String workspace, String runId) {
        WorkspaceUndoLock existing = locks.get(workspace);
        if (existing == null) {
            return true;
        }
        if (runId != null && !runId.equals(existing.getHolderRunId())) {
            return false;
        }
        locks.remove(workspace);
        return true;
    }

    @Override
    public Optional<WorkspaceUndoLock> findByWorkspace(String workspace) {
        if (StringUtils.isBlank(workspace)) {
            return Optional.empty();
        }
        return Optional.ofNullable(locks.get(workspace));
    }

    @Override
    public int deleteStaleBefore(Instant threshold) {
        int count = 0;
        for (WorkspaceUndoLock lock : locks.values()) {
            if (lock.getExpiresAt() != null && lock.getExpiresAt().isBefore(threshold)) {
                locks.remove(lock.getWorkspace());
                count++;
            }
        }
        return count;
    }
}
