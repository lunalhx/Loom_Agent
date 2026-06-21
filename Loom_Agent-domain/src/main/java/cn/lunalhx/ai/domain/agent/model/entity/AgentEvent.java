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
    private String requestId;
    private String conversationId;
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
    private Instant expiresAt;
    private String observation;
    private Boolean truncated;
    private String answer;
    private AgentStopReason stopReason;
    private Integer stepCount;
    private String code;
    private String message;

}
