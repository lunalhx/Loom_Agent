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
    private Integer autoCompactTokenLimit = 64000;
    private Integer reactiveCompactMaxAttempts = 1;
    private Integer reactiveKeepRecentEntries = 5;
    private Integer contextSafetyMarginTokens = 4096;
    private Integer summaryMaxChars = 6000;
    private String deepSummaryModel;
    private Integer deepSummaryChunkTokenLimit = 12000;
    private Integer deepSummaryMaxCalls = 8;
    private Integer deepSummaryMaxOutputTokens = 2048;
    private Integer transcriptRetentionHours = 168;
    private Integer transcriptCleanupIntervalMs = 3600000;
    private Integer transcriptCleanupBatchSize = 500;
    private Boolean transcriptCleanupEnabled = true;
}
