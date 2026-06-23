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
    private String subAgentRunId;
    private String subAgentTaskId;
    private String subAgentRole;
    private String subAgentStatus;
    private Long elapsedMs;
    private Integer step;
    private String node;
    private List<String> nodeInputs;
    private String thought;
    private String tool;
    private Map<String, Object> input;
    private String approvalId;
    private String permissionLevel;
    private String riskReason;
    private String operationPreview;
    private String expiresAt;
    private String observation;
    private Boolean truncated;
    private String answer;
    private String stopReason;
    private Integer stepCount;
    private String code;
    private String message;
    private Map<String, Object> plan;
    private Long checkpointVersion;
    private Map<String, Object> metadata;

}
