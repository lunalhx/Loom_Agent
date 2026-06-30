package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.model.entity.WorkspaceUndoLock;
import cn.lunalhx.ai.infrastructure.dao.AgentWorkspaceUndoLockDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentWorkspaceUndoLockPO;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class MybatisWorkspaceUndoLockRepository implements WorkspaceUndoLockRepository {

    private final AgentWorkspaceUndoLockDao dao;

    public MybatisWorkspaceUndoLockRepository(AgentWorkspaceUndoLockDao dao) {
        this.dao = dao;
    }

    @Override
    public boolean acquire(String workspace, String runId, Instant expiresAt) {
        AgentWorkspaceUndoLockPO po = new AgentWorkspaceUndoLockPO();
        po.setLockId(UUID.randomUUID().toString());
        po.setWorkspace(workspace);
        po.setHolderRunId(runId);
        po.setAcquiredAt(Instant.now());
        po.setExpiresAt(expiresAt);
        return dao.tryAcquire(po) > 0;
    }

    @Override
    public boolean release(String workspace, String runId) {
        int deleted = dao.deleteOwned(workspace, runId);
        return deleted > 0 || dao.selectByWorkspace(workspace) == null;
    }

    @Override
    public Optional<WorkspaceUndoLock> findByWorkspace(String workspace) {
        return Optional.ofNullable(dao.selectByWorkspace(workspace)).map(this::toEntity);
    }

    @Override
    public int deleteStaleBefore(Instant threshold) {
        return dao.deleteStaleBefore(threshold);
    }

    private WorkspaceUndoLock toEntity(AgentWorkspaceUndoLockPO po) {
        return WorkspaceUndoLock.builder()
                .lockId(po.getLockId())
                .workspace(po.getWorkspace())
                .holderRunId(po.getHolderRunId())
                .acquiredAt(po.getAcquiredAt())
                .expiresAt(po.getExpiresAt())
                .build();
    }
}
