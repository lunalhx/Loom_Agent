package cn.lunalhx.ai.infrastructure.dao.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class AgentRunPO {

    private Long id;
    private String runId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private String runKind;
    private Integer depth;
    private String question;
    private String workspace;
    private String status;
    private String currentNode;
    private Integer toolSteps;
    private Integer modelAttempts;
    private String lastTool;
    private String stopReason;
    private String finalAnswer;
    private Long checkpointVersion;
    private String summaryJson;
    private String blockedReason;
    private Long usedTokens;
    private BigDecimal estimatedCost;
    private Instant createTime;
    private Instant updateTime;

}