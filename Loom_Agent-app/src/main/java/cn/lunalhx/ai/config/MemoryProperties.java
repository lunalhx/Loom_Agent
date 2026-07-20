package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.MemoryRuntimeProperties;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MemoryProperties extends MemoryRuntimeProperties {

    private boolean enabled = false;
    private VectorConfig vector = new VectorConfig();

    @Getter
    @Setter
    public static class VectorConfig {
        private boolean enabled = false;
        private String extensionPath;
        private String distanceMetric = "cosine";
        private int searchK = 50;
        private EmbeddingConfig embedding = new EmbeddingConfig();
    }

    @Getter
    @Setter
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
