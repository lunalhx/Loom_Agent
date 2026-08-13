package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRun {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private Integer schemaVersion;
    private String runId;
    private String sessionId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private AgentRunKind runKind;
    private CollaborationMode runModeSnapshot;
    private Integer depth;
    private Integer maxSteps;
    private String question;
    private String workspace;
    private String planTarget;
    private Integer planRevision;
    private Long planStateVersion;
    private PlanBinding planBinding;
    private PlanDeviation planDeviation;
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
    private List<EvidenceReceipt> evidenceReceipts;
    private Boolean evidenceDrift;
    private Long usedTokens;
    private BigDecimal estimatedCost;
    private Instant createdAt;
    private Instant updatedAt;

}
