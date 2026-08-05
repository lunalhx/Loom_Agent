package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.List;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {

    private AgentEventType type;
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
    private String reason;
    private String tool;
    private String toolCallId;
    private Map<String, Object> input;
    private String approvalId;
    private String riskReason;
    private String operationPreview;
    private Instant expiresAt;
    private String observation;
    private Boolean truncated;
    private String answer;
    private AgentStopReason stopReason;
    private String lastTool;
    private String code;
    private String message;
    private Long checkpointVersion;
    private Boolean recoverable;
    private Map<String, Object> metadata;
    private Integer maxToolSteps;
    private Integer maxAttempts;

}
