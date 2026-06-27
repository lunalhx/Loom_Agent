package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentWorkspaceUndoLockPO {

    private Long id;
    private String lockId;
    private String workspace;
    private String holderRunId;
    private LocalDateTime acquiredAt;
    private LocalDateTime expiresAt;
}
