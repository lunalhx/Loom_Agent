package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryVectorIndex;
import cn.lunalhx.ai.domain.memory.adapter.port.MemoryEmbeddingGateway;
import cn.lunalhx.ai.domain.memory.service.MemorySearchService;
import cn.lunalhx.ai.infrastructure.adapter.embedding.OpenAiCompatibleEmbeddingGateway;
import cn.lunalhx.ai.infrastructure.adapter.vector.SqliteVecVectorIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${loom.agent.long-term-memory.enabled:false} && ${loom.agent.long-term-memory.vector.enabled:false}")
public class VecAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(VecAutoConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "loom.agent.persistence", name = "mode", havingValue = "sqlite", matchIfMissing = true)
    public AgentMemoryVectorIndex agentMemoryVectorIndex(DataSource dataSource, MemoryProperties memoryProperties) {
        MemoryProperties.VectorConfig config = memoryProperties.getVector();
        String extPath = config.getExtensionPath();
        if (extPath == null || extPath.isBlank()) {
            extPath = System.getenv("SQLITE_VEC_EXTENSION_PATH");
            if (extPath == null || extPath.isBlank()) {
                log.info("No sqlite-vec extension path configured, trying default paths...");
            }
        }
        SqliteVecVectorIndex index = new SqliteVecVectorIndex(dataSource, extPath, config.getEmbedding().getDimensions());
        if (index.available()) {
            log.info("sqlite-vec vector index is available");
        } else {
            log.warn("sqlite-vec vector index is NOT available — semantic search disabled, keyword fallback active");
        }
        return index;
    }

    @Bean
    @ConditionalOnProperty(prefix = "loom.agent.persistence", name = "mode", havingValue = "sqlite", matchIfMissing = true)
    public MemoryEmbeddingGateway memoryEmbeddingGateway(MemoryProperties memoryProperties) {
        MemoryProperties.EmbeddingConfig ec = memoryProperties.getVector().getEmbedding();
        String apiKey = ec.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("EMBEDDING_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = System.getenv("DASHSCOPE_API_KEY");
            }
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = System.getenv("OPENAI_API_KEY");
            }
        }
        String baseUrl = ec.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = System.getenv("EMBEDDING_BASE_URL");
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.openai.com/v1";
            }
        }
        return new OpenAiCompatibleEmbeddingGateway(
                baseUrl, apiKey, ec.getModel(), ec.getDimensions(), ec.getTimeoutMs(), ec.getBatchSize());
    }

    @Bean
    @ConditionalOnProperty(prefix = "loom.agent.persistence", name = "mode", havingValue = "sqlite", matchIfMissing = true)
    public MemorySearchService memorySearchService(ObjectProvider<AgentMemoryRepository> agentMemoryRepositoryProvider,
                                                    AgentMemoryVectorIndex agentMemoryVectorIndex,
                                                    MemoryEmbeddingGateway memoryEmbeddingGateway,
                                                    MemoryProperties memoryProperties) {
        return new MemorySearchService(agentMemoryRepositoryProvider.getObject(), agentMemoryVectorIndex,
                memoryEmbeddingGateway, memoryProperties.getVector().getSearchK());
    }
}
