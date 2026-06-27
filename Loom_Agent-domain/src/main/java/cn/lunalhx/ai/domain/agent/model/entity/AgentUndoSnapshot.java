package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.UndoSnapshotStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentUndoSnapshot {

    private String snapshotId;
    private String runId;
    private String conversationId;
    private String workspace;
    private UndoSnapshotStatus status;

    private String beforeHeadCommit;
    private String afterHeadCommit;
    private String branch;

    private String beforeWorktreeOid;
    private String beforeIndexOid;
    private String afterWorktreeOid;
    private String afterIndexOid;

    private String changedPathsJson;
    private Integer changedFileCount;
    private Long changedByteCount;

    private String unavailabilityReason;
    private String errorInfo;

    private Long version;

    private Instant createdAt;
    private Instant finalizedAt;
    private Instant undoneAt;
    private Instant expiresAt;
}
