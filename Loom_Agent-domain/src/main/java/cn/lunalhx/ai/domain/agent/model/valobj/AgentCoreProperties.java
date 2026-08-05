package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentCoreProperties {
    private Boolean enabled = true;
    private String workspaceRoot = ".";
    private List<String> allowedWorkspaceRoots = new ArrayList<>();
    private Integer maxSteps = 6;
    private Long totalTimeoutMs = 1800000L;
    private Long stepTimeoutMs = 120000L;
    private Long toolTimeoutMs = 3000L;
    private Integer observationMaxChars = 4000;
    private Integer subAgentMaxDepth = 1;
}
