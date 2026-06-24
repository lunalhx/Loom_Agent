package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class AgentRuntimeProperties {

    private Boolean enabled = true;
    private String workspaceRoot = ".";
    private List<String> allowedWorkspaceRoots = new ArrayList<>();
    private Integer maxSteps = 30;
    private Long totalTimeoutMs = 1800000L;
    private Long stepTimeoutMs = 120000L;
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
    private BudgetProperties budget = new BudgetProperties();
    private ContextProperties context = new ContextProperties();

    @Data
    public static class BudgetProperties {

        private Boolean enabled = false;
        private Integer maxTotalTokens = 200000;
        private Integer reservedOutputTokens = 2048;
        private Integer estimatedCharsPerToken = 4;
        private BigDecimal inputPricePer1k = BigDecimal.ZERO;
        private BigDecimal outputPricePer1k = BigDecimal.ZERO;

    }

    @Data
    public static class ContextProperties {

        private Boolean enabled = true;
        private String storageRoot = System.getProperty("java.io.tmpdir") + "/loom-agent/context-artifacts";
        private Integer persistToolResultChars = 12000;
        private Integer toolPreviewChars = 2000;
        private Integer keepRecentToolResults = 4;
        private Integer maxDynamicEntries = 60;
        private Integer autoCompactTokenLimit = 64000;
        private Integer reactiveCompactMaxAttempts = 1;
        private Integer summaryMaxChars = 6000;

    }

}
