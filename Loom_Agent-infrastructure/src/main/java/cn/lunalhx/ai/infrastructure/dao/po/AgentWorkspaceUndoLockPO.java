package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.Instant;

@Data
public class AgentWorkspaceUndoLockPO {

    private Long id;
    private String lockId;
    private String workspace;
    private String holderRunId;
    private Instant acquiredAt;
    private Instant expiresAt;
}
