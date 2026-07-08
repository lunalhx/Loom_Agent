package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryVectorIndex;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService;
import cn.lunalhx.ai.domain.memory.service.MemorySearchService;
import cn.lunalhx.ai.domain.memory.service.MemorySelectionService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.IndexingAgentMemoryRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentMemoryRepository;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryDao;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryGenerationJobDao;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryRevisionDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "loom.agent.long-term-memory.enabled", havingValue = "true")
public class MemoryAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryAutoConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "loom.agent.long-term-memory")
    public MemoryProperties memoryProperties() {
        return new MemoryProperties();
    }

    @Bean
    public AgentMemoryRepository agentMemoryRepository(PersistenceProperties persistence,
                                                        ObjectProvider<AgentMemoryDao> daoProvider,
                                                        ObjectProvider<AgentMemoryVectorIndex> vectorIndexProvider,
                                                        ObjectProvider<DataSource> dataSourceProvider) {
        AgentMemoryDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("AgentMemoryRepository: InMemory (mode=memory)");
                yield new InMemoryAgentMemoryRepository();
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires AgentMemoryDao, but MyBatis DAO is not available");
                }
                MybatisAgentMemoryRepository repo = new MybatisAgentMemoryRepository(dao);
                AgentMemoryVectorIndex vi = vectorIndexProvider.getIfAvailable();
                DataSource ds = dataSourceProvider.getIfAvailable();
                if (vi != null && vi.available() && ds != null) {
                    log.info("AgentMemoryRepository: Indexing (vector search enabled)");
                    yield new IndexingAgentMemoryRepository(repo, vi, ds);
                }
                log.info("AgentMemoryRepository: MyBatis (mode=sqlite, vector disabled)");
                yield repo;
            }
        };
    }

    @Bean
    public AgentMemoryGenerationJobRepository agentMemoryGenerationJobRepository(PersistenceProperties persistence,
                                                                                  ObjectProvider<AgentMemoryGenerationJobDao> daoProvider) {
        AgentMemoryGenerationJobDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("AgentMemoryGenerationJobRepository: InMemory (mode=memory)");
                yield new InMemoryAgentMemoryGenerationJobRepository();
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires AgentMemoryGenerationJobDao, but MyBatis DAO is not available");
                }
                log.info("AgentMemoryGenerationJobRepository: MyBatis (mode=sqlite)");
                yield new MybatisAgentMemoryGenerationJobRepository(dao);
            }
        };
    }

    @Bean
    public MemorySelectionService memorySelectionService(AgentMemoryRepository agentMemoryRepository,
                                                         MemoryProperties memoryProperties,
                                                         ObjectProvider<MemorySearchService> searchServiceProvider) {
        MemorySearchService searchService = searchServiceProvider.getIfAvailable();
        return new MemorySelectionService(agentMemoryRepository,
                searchService,
                memoryProperties.getMaxSelected(),
                memoryProperties.getMaxInjectedChars());
    }

    @Bean
    public MemoryExtractionService memoryExtractionService(ModelGateway modelGateway,
                                                            ObjectMapper objectMapper,
                                                            MemoryProperties memoryProperties,
                                                            TraceRecorder traceRecorder) {
        return new MemoryExtractionService(modelGateway, objectMapper,
                memoryProperties.getExtractionModel(), traceRecorder);
    }
}
