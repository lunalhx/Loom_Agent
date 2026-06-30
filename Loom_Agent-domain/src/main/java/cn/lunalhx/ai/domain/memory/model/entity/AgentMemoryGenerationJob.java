package cn.lunalhx.ai.domain.memory.model.entity;

import cn.lunalhx.ai.domain.memory.model.valobj.MemoryGenerationJobStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AgentMemoryGenerationJob {
    private String jobId;
    private String sourceRunId;
    private String workspaceKey;
    private String conversationSummaryJson;
    private MemoryGenerationJobStatus status;
    private Instant notBefore;
    private String lockedBy;
    private Instant lockExpiresAt;
    private int retryCount;
    private String errorMsg;
    private Instant createdAt;
    private Instant updatedAt;
}
