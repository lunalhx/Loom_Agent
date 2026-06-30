package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class AgentMemoryGenerationJobPO {
    private String jobId;
    private String sourceRunId;
    private String workspaceKey;
    private String conversationSummaryJson;
    private String status;
    private String notBefore;
    private String lockedBy;
    private String lockExpiresAt;
    private Integer retryCount;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
