package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

@Data
public class MemoryRuntimeProperties {
    private boolean useMemories = true;
    private boolean generateMemories = true;
    private boolean disableOnExternalContext = true;
    private int maxActive = 200;
    private int maxSelected = 4;
    private int maxInjectedChars = 8000;
    private String selectionModel;
    private String extractionModel;
    private int pinnedLimit = 4;
    private double minRelevanceScore = 0.5;
    private int archiveAfterUnusedDays = 90;
    private int archiveMinImportance = 80;
    private int cleanupIntervalHours = 24;
    private int extractionTimeoutSeconds = 15;
}
