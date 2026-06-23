package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentRunPO {

    private Long id;
    private String runId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private String agentRole;
    private String runKind;
    private Integer depth;
    private Integer childOrdinal;
    private String question;
    private String workspace;
    private String status;
    private String currentNode;
    private Integer step;
    private Long checkpointVersion;
    private String summaryJson;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
