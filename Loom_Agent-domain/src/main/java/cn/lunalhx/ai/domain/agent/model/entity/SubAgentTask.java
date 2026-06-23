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
public class SubAgentTask {

    private String taskId;
    private AgentRole role;
    private String question;
    private String pathScope;
    private String expectedOutput;
    private Integer maxSteps;

}
