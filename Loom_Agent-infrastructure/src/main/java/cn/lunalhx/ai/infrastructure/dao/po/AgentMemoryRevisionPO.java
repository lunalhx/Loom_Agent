package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class AgentMemoryRevisionPO {
    private Long id;
    private String memoryId;
    private Integer version;
    private String snapshotJson;
    private String sourceType;
    private String sourceRunId;
    private String createdAt;
}
