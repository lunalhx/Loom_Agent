package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.AgentUndoSnapshot;
import cn.lunalhx.ai.domain.agent.model.valobj.UndoSnapshotStatus;

import java.time.Instant;
import java.util.Optional;

public interface UndoSnapshotRepository {

    AgentUndoSnapshot save(AgentUndoSnapshot snapshot);

    Optional<AgentUndoSnapshot> findBySnapshotId(String snapshotId);

    Optional<AgentUndoSnapshot> findByRunId(String runId);

    Optional<AgentUndoSnapshot> findLatestByWorkspace(String workspace);

    Optional<AgentUndoSnapshot> findLatestByConversationId(String conversationId);

    boolean updateStatus(String snapshotId, UndoSnapshotStatus expected, UndoSnapshotStatus next);

    int expireOlderThan(Instant threshold);
}
