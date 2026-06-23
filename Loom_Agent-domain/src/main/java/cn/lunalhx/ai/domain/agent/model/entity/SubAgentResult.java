package cn.lunalhx.ai.domain.agent.model.entity;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.SubAgentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubAgentResult {

    private String taskId;
    private String runId;
    private AgentRole role;
    private SubAgentStatus status;
    private String summary;
    private String errorCode;
    private String message;
    private boolean truncated;
    private int stepCount;
    private long elapsedMs;

}
