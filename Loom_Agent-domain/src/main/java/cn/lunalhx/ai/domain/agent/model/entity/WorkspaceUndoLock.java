package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceUndoLock {

    private String lockId;
    private String workspace;
    private String holderRunId;
    private Instant acquiredAt;
    private Instant expiresAt;
}
