package cn.lunalhx.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentQuestion {

    private String requestId;
    private String conversationId;
    private String question;
    private String workspace;
    private Integer maxSteps;
    private Boolean includeTrace;

}
