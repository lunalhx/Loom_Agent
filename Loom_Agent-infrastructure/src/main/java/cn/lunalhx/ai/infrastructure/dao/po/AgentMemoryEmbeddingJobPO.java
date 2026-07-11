package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class AgentMemoryEmbeddingJobPO {
    private String jobId;
    private String memoryId;
    private String action;
    private String status;
    private Integer retryCount;
    private String notBefore;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
