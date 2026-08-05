package cn.lunalhx.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamEvent {

    private String type;
    private String runId;
    private String requestId;
    private String conversationId;
    private String workspace;
    private String parentRunId;
    private Long elapsedMs;
    private Integer toolSteps;
    private Integer modelAttempts;
    private String node;
    private List<String> nodeInputs;
    private String thought;
    private String tool;
    private String toolCallId;
    private Map<String, Object> input;
    private String approvalId;
    private String riskReason;
    private String operationPreview;
    private String expiresAt;
    private String observation;
    private Boolean truncated;
    private String answer;
    private String stopReason;
    private String lastTool;
    private Integer maxToolSteps;
    private Integer maxAttempts;
    private String code;
    private String message;
    private Long checkpointVersion;
    private Boolean recoverable;
    private Map<String, Object> metadata;
    private AgentUsageSummaryDTO usage;

}
