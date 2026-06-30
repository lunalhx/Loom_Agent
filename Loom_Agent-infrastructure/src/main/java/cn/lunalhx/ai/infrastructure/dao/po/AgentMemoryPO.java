package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class AgentMemoryPO {
    private String memoryId;
    private String workspaceKey;
    private String workspacePath;
    private String type;
    private String title;
    private String summary;
    private String body;
    private String status;
    private Integer pinned;
    private Integer importance;
    private String sourceType;
    private String sourceRunId;
    private String contentHash;
    private Long version;
    private Integer usageCount;
    private String lastUsedAt;
    private String createdAt;
    private String updatedAt;
}
