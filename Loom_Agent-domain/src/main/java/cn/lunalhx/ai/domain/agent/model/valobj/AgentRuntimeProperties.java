package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentRuntimeProperties {

    private Boolean enabled = true;
    private String workspaceRoot = ".";
    private List<String> allowedWorkspaceRoots = new ArrayList<>();
    private Integer maxSteps = 6;
    private Long totalTimeoutMs = 120000L;
    private Long stepTimeoutMs = 30000L;
    private Long toolTimeoutMs = 3000L;
    private Integer observationMaxChars = 8000;
    private Integer parseErrorMaxAttempts = 2;
    private Long fileMaxBytes = 200000L;
    private Integer searchMaxResults = 50;
    private Long approvalTtlSeconds = 900L;
    private Long shellTimeoutMs = 120000L;
    private Integer shellMaxOutputChars = 12000;
    private String highRiskPolicy = "DENY";
    private List<String> allowedShellCommands = new ArrayList<>(List.of("mvn", "./mvnw", "git", "pwd", "ls", "rg"));
    private Boolean subAgentEnabled = true;
    private Integer subAgentMaxChildren = 6;
    private Integer subAgentMaxConcurrency = 4;
    private Integer subAgentMaxDepth = 1;
    private Long subAgentTimeoutMs = 60000L;
    private Integer subAgentSummaryMaxChars = 12000;

}
