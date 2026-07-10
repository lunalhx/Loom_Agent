package cn.lunalhx.ai.config;

import lombok.Data;

@Data
public class MemoryProperties {

    private boolean enabled = false;
    private boolean useMemories = true;
    private boolean generateMemories = true;
    private int generationDelayMinutes = 1;
    private boolean disableOnExternalContext = true;
    private int maxActive = 200;
    private int maxSelected = 8;
    private int maxInjectedChars = 8000;
    private String selectionModel;
    private String extractionModel;
    private int pinnedLimit = 4;
    private double minRelevanceScore = 0.35;
    private int archiveAfterUnusedDays = 90;
    private int archiveMinImportance = 80;
    private int cleanupIntervalHours = 24;
    private WorkerConfig worker = new WorkerConfig();
    private VectorConfig vector = new VectorConfig();

    @Data
    public static class WorkerConfig {
        private int pollIntervalSeconds = 30;
        private int batchSize = 1;
        private int leaseDurationSeconds = 300;
        private int maxRetries = 3;
        private int staleRecoverySeconds = 600;
    }

    @Data
    public static class VectorConfig {
        private boolean enabled = true;
        private String extensionPath;
        private String distanceMetric = "cosine";
        private int searchK = 50;
        private EmbeddingConfig embedding = new EmbeddingConfig();
    }

    @Data
    public static class EmbeddingConfig {
        private String provider = "openai-compatible";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey;
        private String model = "text-embedding-3-small";
        private int dimensions = 1536;
        private int timeoutMs = 8000;
        private int batchSize = 16;
    }
}
