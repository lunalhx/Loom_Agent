package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentCoreProperties {
    private Boolean enabled = true;
    private String workspaceRoot = ".";
    private List<String> allowedWorkspaceRoots = new ArrayList<>();
    private Integer maxSteps = 30;
    private Long totalTimeoutMs = 1800000L;
    private Long stepTimeoutMs = 120000L;
    private Long toolTimeoutMs = 3000L;
    private Integer observationMaxChars = 8000;
    private Integer modelCallRetryMaxAttempts = 2;
    private Long fileMaxBytes = 200000L;
    private Integer searchMaxResults = 50;
    private Long approvalTtlSeconds = 900L;
    private Long shellTimeoutMs = 120000L;
    private Long shellMaxTimeoutMs = 600000L;
    private Integer shellMaxOutputChars = 12000;
    private Integer shellMaxStderrChars = 4000;
    private String highRiskPolicy = "CONFIRM";
    private String permissionMode = "SANDBOX";
    private List<String> allowedShellCommands = new ArrayList<>(List.of("mvn", "./mvnw", "git", "rm", "pwd", "ls", "rg"));
    private Boolean subAgentEnabled = true;
    private Integer subAgentMaxChildren = 6;
    private Integer subAgentMaxConcurrency = 4;
    private Integer subAgentMaxDepth = 1;
    private Long subAgentTimeoutMs = 60000L;
    private Boolean subAgentRecoveryEnabled = true;
    private Long subAgentIdleRecoveryMs = 60000L;
    private Long subAgentRecoveryPollIntervalMs = 1000L;
    private Integer subAgentSummaryMaxChars = 12000;
}
