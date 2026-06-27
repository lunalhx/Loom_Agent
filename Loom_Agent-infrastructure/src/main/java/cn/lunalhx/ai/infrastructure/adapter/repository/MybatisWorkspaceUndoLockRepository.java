package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.model.entity.WorkspaceUndoLock;
import cn.lunalhx.ai.infrastructure.dao.AgentWorkspaceUndoLockDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentWorkspaceUndoLockPO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

public class MybatisWorkspaceUndoLockRepository implements WorkspaceUndoLockRepository {

    private final AgentWorkspaceUndoLockDao dao;

    public MybatisWorkspaceUndoLockRepository(AgentWorkspaceUndoLockDao dao) {
        this.dao = dao;
    }

    @Override
    public boolean acquire(String workspace, String runId, Instant expiresAt) {
        AgentWorkspaceUndoLockPO existing = dao.selectByWorkspace(workspace);
        if (existing != null && existing.getExpiresAt().isAfter(LocalDateTime.now())) {
            return false;
        }
        if (existing != null) {
            dao.deleteByWorkspace(workspace);
        }
        AgentWorkspaceUndoLockPO po = new AgentWorkspaceUndoLockPO();
        po.setLockId(UUID.randomUUID().toString());
        po.setWorkspace(workspace);
        po.setHolderRunId(runId);
        po.setAcquiredAt(LocalDateTime.now());
        po.setExpiresAt(toLocalDateTime(expiresAt));
        dao.insert(po);
        return true;
    }

    @Override
    public boolean release(String workspace, String runId) {
        AgentWorkspaceUndoLockPO existing = dao.selectByWorkspace(workspace);
        if (existing == null) {
            return true;
        }
        if (runId != null && !runId.equals(existing.getHolderRunId())) {
            return false;
        }
        dao.deleteByWorkspace(workspace);
        return true;
    }

    @Override
    public Optional<WorkspaceUndoLock> findByWorkspace(String workspace) {
        return Optional.ofNullable(dao.selectByWorkspace(workspace)).map(this::toEntity);
    }

    @Override
    public int deleteStaleBefore(Instant threshold) {
        return dao.deleteStaleBefore(toLocalDateTime(threshold));
    }

    private WorkspaceUndoLock toEntity(AgentWorkspaceUndoLockPO po) {
        return WorkspaceUndoLock.builder()
                .lockId(po.getLockId())
                .workspace(po.getWorkspace())
                .holderRunId(po.getHolderRunId())
                .acquiredAt(toInstant(po.getAcquiredAt()))
                .expiresAt(toInstant(po.getExpiresAt()))
                .build();
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
