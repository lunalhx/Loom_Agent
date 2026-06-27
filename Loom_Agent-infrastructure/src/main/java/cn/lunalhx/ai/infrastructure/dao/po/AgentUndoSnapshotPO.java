package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentUndoSnapshotPO {

    private Long id;
    private String snapshotId;
    private String runId;
    private String conversationId;
    private String workspace;
    private String status;
    private String beforeHead;
    private String afterHead;
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
    private LocalDateTime createdAt;
    private LocalDateTime finalizedAt;
    private LocalDateTime undoneAt;
    private LocalDateTime expiresAt;
}
