package cn.lunalhx.ai.config;

import lombok.Data;

@Data
public class MemoryProperties {

    private boolean enabled = false;
    private boolean useMemories = true;
    private boolean generateMemories = true;
    private int generationDelayMinutes = 10;
    private boolean disableOnExternalContext = true;
    private int maxActive = 200;
    private int maxSelected = 8;
    private int maxInjectedChars = 8000;
    private String selectionModel;
    private String extractionModel;
    private WorkerConfig worker = new WorkerConfig();

    @Data
    public static class WorkerConfig {
        private int pollIntervalSeconds = 30;
        private int batchSize = 1;
        private int leaseDurationSeconds = 300;
        private int maxRetries = 3;
        private int staleRecoverySeconds = 600;
    }
}
