package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentUndoSnapshot;
import cn.lunalhx.ai.domain.agent.model.valobj.UndoSnapshotStatus;
import cn.lunalhx.ai.infrastructure.dao.AgentUndoSnapshotDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

public class MybatisUndoSnapshotRepository implements UndoSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisUndoSnapshotRepository.class);

    private final AgentUndoSnapshotDao dao;
    private final ObjectMapper objectMapper;

    public MybatisUndoSnapshotRepository(AgentUndoSnapshotDao dao, ObjectMapper objectMapper) {
        this.dao = dao;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentUndoSnapshot save(AgentUndoSnapshot snapshot) {
        dao.upsert(toPo(snapshot));
        return snapshot;
    }

    @Override
    public Optional<AgentUndoSnapshot> findBySnapshotId(String snapshotId) {
        return Optional.ofNullable(dao.selectBySnapshotId(snapshotId)).map(this::toEntity);
    }

    @Override
    public Optional<AgentUndoSnapshot> findByRunId(String runId) {
        return Optional.ofNullable(dao.selectByRunId(runId)).map(this::toEntity);
    }

    @Override
    public Optional<AgentUndoSnapshot> findLatestByWorkspace(String workspace) {
        return Optional.ofNullable(dao.selectLatestByWorkspace(workspace)).map(this::toEntity);
    }

    @Override
    public Optional<AgentUndoSnapshot> findLatestByConversationId(String conversationId) {
        return Optional.ofNullable(dao.selectLatestByConversationId(conversationId)).map(this::toEntity);
    }

    @Override
    public boolean updateStatus(String snapshotId, UndoSnapshotStatus expected, UndoSnapshotStatus next) {
        AgentUndoSnapshotPO po = dao.selectBySnapshotId(snapshotId);
        if (po == null) {
            return false;
        }
        int rows = dao.updateStatus(snapshotId, expected.name(), next.name(), po.getVersion());
        return rows > 0;
    }

    @Override
    public List<AgentUndoSnapshot> findExpired(Instant now) {
        return dao.selectExpired(toLocalDateTime(now)).stream()
                .map(this::toEntity)
                .toList();
    }

    @Override
    public int expireByStatus(String snapshotId, UndoSnapshotStatus from, UndoSnapshotStatus to) {
        AgentUndoSnapshotPO po = dao.selectBySnapshotId(snapshotId);
        if (po == null) {
            return 0;
        }
        return dao.expireByStatus(snapshotId, from.name(), to.name(), po.getVersion());
    }

    private AgentUndoSnapshotPO toPo(AgentUndoSnapshot entity) {
        AgentUndoSnapshotPO po = new AgentUndoSnapshotPO();
        po.setSnapshotId(entity.getSnapshotId());
        po.setRunId(entity.getRunId());
        po.setConversationId(entity.getConversationId());
        po.setWorkspace(entity.getWorkspace());
        po.setStatus(entity.getStatus() == null ? null : entity.getStatus().name());
        po.setBeforeHead(entity.getBeforeHeadCommit());
        po.setAfterHead(entity.getAfterHeadCommit());
        po.setBranch(entity.getBranch());
        po.setBeforeWorktreeOid(entity.getBeforeWorktreeOid());
        po.setBeforeIndexOid(entity.getBeforeIndexOid());
        po.setAfterWorktreeOid(entity.getAfterWorktreeOid());
        po.setAfterIndexOid(entity.getAfterIndexOid());
        po.setChangedPathsJson(entity.getChangedPathsJson());
        po.setChangedFileCount(entity.getChangedFileCount());
        po.setChangedByteCount(entity.getChangedByteCount());
        po.setUnavailabilityReason(entity.getUnavailabilityReason());
        po.setErrorInfo(entity.getErrorInfo());
        po.setVersion(entity.getVersion());
        po.setCreatedAt(toLocalDateTime(entity.getCreatedAt()));
        po.setFinalizedAt(toLocalDateTime(entity.getFinalizedAt()));
        po.setUndoneAt(toLocalDateTime(entity.getUndoneAt()));
        po.setExpiresAt(toLocalDateTime(entity.getExpiresAt()));
        return po;
    }

    private AgentUndoSnapshot toEntity(AgentUndoSnapshotPO po) {
        return AgentUndoSnapshot.builder()
                .snapshotId(po.getSnapshotId())
                .runId(po.getRunId())
                .conversationId(po.getConversationId())
                .workspace(po.getWorkspace())
                .status(po.getStatus() == null ? null : UndoSnapshotStatus.valueOf(po.getStatus()))
                .beforeHeadCommit(po.getBeforeHead())
                .afterHeadCommit(po.getAfterHead())
                .branch(po.getBranch())
                .beforeWorktreeOid(po.getBeforeWorktreeOid())
                .beforeIndexOid(po.getBeforeIndexOid())
                .afterWorktreeOid(po.getAfterWorktreeOid())
                .afterIndexOid(po.getAfterIndexOid())
                .changedPathsJson(po.getChangedPathsJson())
                .changedFileCount(po.getChangedFileCount())
                .changedByteCount(po.getChangedByteCount())
                .unavailabilityReason(po.getUnavailabilityReason())
                .errorInfo(po.getErrorInfo())
                .version(po.getVersion())
                .createdAt(toInstant(po.getCreatedAt()))
                .finalizedAt(toInstant(po.getFinalizedAt()))
                .undoneAt(toInstant(po.getUndoneAt()))
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
