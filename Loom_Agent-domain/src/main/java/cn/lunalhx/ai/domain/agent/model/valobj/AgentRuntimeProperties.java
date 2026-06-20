package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class AgentRuntimeProperties {

    private Boolean enabled = true;
    private String workspaceRoot = ".";
    private Integer maxSteps = 6;
    private Long totalTimeoutMs = 120000L;
    private Long stepTimeoutMs = 30000L;
    private Long toolTimeoutMs = 3000L;
    private Integer observationMaxChars = 8000;
    private Integer parseErrorMaxAttempts = 2;
    private Long fileMaxBytes = 200000L;
    private Integer searchMaxResults = 50;

}
