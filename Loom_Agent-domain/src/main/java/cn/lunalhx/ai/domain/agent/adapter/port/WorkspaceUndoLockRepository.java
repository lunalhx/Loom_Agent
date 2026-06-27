package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.WorkspaceUndoLock;

import java.time.Instant;
import java.util.Optional;

public interface WorkspaceUndoLockRepository {

    boolean acquire(String workspace, String runId, Instant expiresAt);

    boolean release(String workspace, String runId);

    Optional<WorkspaceUndoLock> findByWorkspace(String workspace);

    int deleteStaleBefore(Instant threshold);
}
