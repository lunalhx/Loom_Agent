package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentQuestion {

    private String runId;
    private String parentRunId;
    private String rootRunId;
    private String requestId;
    private String conversationId;
    private AgentRole agentRole;
    private Integer agentDepth;
    private Integer childOrdinal;
    private String question;
    private String pathScope;
    private String workspace;
    private Integer maxSteps;
    private Boolean includeTrace;
    private Boolean subAgentSpawnAllowed;

}
