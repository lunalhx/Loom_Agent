package cn.lunalhx.ai.domain.agent.model.valobj;

import cn.lunalhx.ai.domain.common.LoomPaths;
import lombok.Data;

@Data
public class ContextProperties {
    private Boolean enabled = true;
    private String storageRoot = LoomPaths.system().contextArtifacts().toString();
    private Integer persistToolResultChars = 12000;
    private Integer toolPreviewChars = 2000;
    private Integer keepRecentToolResults = 4;
    private Integer maxDynamicEntries = 60;
    private Boolean contextReductionEnabled = true;
    private Integer totalBudgetChars = 12000;
    private Integer prefixBudgetChars = 3600;
    private Integer prefixFloorChars = 900;
    private Integer memoryBudgetChars = 1600;
    private Integer memoryFloorChars = 400;
    private Integer relevantMemoryBudgetChars = 1200;
    private Integer relevantMemoryFloorChars = 300;
    private Integer historyBudgetChars = 5200;
    private Integer historyFloorChars = 1300;
    private Integer recentHistoryItems = 6;
    private Integer relevantMemoryLimit = 3;
    private Integer transcriptRetentionHours = 168;
    private Integer transcriptCleanupIntervalMs = 3600000;
    private Integer transcriptCleanupBatchSize = 500;
    private Boolean transcriptCleanupEnabled = true;
}
