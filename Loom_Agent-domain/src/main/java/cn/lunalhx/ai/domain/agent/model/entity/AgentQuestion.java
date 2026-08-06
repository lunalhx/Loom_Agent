package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentQuestion {

    private String runId;
    private String sessionId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private Integer agentDepth;
    private String question;
    private String pathScope;
    private String workspace;
    private Integer maxSteps;
    private Boolean includeTrace;
    private String traceId;
    private String model;
    private List<String> allowedTools;
    private String approvalPolicy;
    private String checkpointId;
    private AgentContextSnapshot seedSnapshot;

}
