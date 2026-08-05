package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRun {

    private String runId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private AgentRunKind runKind;
    private Integer depth;
    private String question;
    private String workspace;
    private AgentRunStatus status;
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
    private Instant createdAt;
    private Instant updatedAt;

}
