package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryVectorIndex;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService;
import cn.lunalhx.ai.domain.memory.service.MemoryPersistenceService;
import cn.lunalhx.ai.domain.memory.service.MemorySearchService;
import cn.lunalhx.ai.domain.memory.service.MemorySelectionService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.IndexingAgentMemoryRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentMemoryRepository;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryDao;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryEmbeddingJobDao;
import cn.lunalhx.ai.runtime.worker.MemoryArchiveWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import javax.sql.DataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
                                                        ObjectProvider<DataSource> dataSourceProvider,
                                                        ObjectProvider<AgentMemoryEmbeddingJobDao> embeddingJobDaoProvider,
                                                        MemoryProperties memoryProperties) {
        AgentMemoryDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("AgentMemoryRepository: InMemory (mode=memory)");
                yield new InMemoryAgentMemoryRepository(memoryProperties.getMaxActive());
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires AgentMemoryDao, but MyBatis DAO is not available");
                }
                MybatisAgentMemoryRepository repo = new MybatisAgentMemoryRepository(dao, memoryProperties.getMaxActive());
                AgentMemoryVectorIndex vi = vectorIndexProvider.getIfAvailable();
                DataSource ds = dataSourceProvider.getIfAvailable();
                AgentMemoryEmbeddingJobDao embeddingJobDao = embeddingJobDaoProvider.getIfAvailable();
                if (vi != null && vi.available() && ds != null && embeddingJobDao != null) {
                    log.info("AgentMemoryRepository: Indexing (vector search enabled)");
                    yield new IndexingAgentMemoryRepository(repo, vi, embeddingJobDao);
                }
                log.info("AgentMemoryRepository: MyBatis (mode=sqlite, vector disabled)");
                yield repo;
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
                memoryProperties.getMaxInjectedChars(),
                memoryProperties.getMinRelevanceScore(),
                memoryProperties.getPinnedLimit());
    }

    @Bean
    public MemoryExtractionService memoryExtractionService(ModelGateway modelGateway,
                                                            ObjectMapper objectMapper,
                                                            MemoryProperties memoryProperties,
                                                            TraceRecorder traceRecorder) {
        return new MemoryExtractionService(modelGateway, objectMapper,
                memoryProperties.getExtractionModel(), traceRecorder);
    }

    @Bean
    public MemoryPersistenceService memoryPersistenceService(AgentMemoryRepository agentMemoryRepository,
                                                              MemoryProperties memoryProperties) {
        return new MemoryPersistenceService(agentMemoryRepository, memoryProperties.getMaxActive());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService memoryExtractionExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "memory-extraction");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public MemoryArchiveWorker memoryArchiveWorker(AgentMemoryRepository agentMemoryRepository,
                                                    MemoryProperties memoryProperties) {
        return new MemoryArchiveWorker(agentMemoryRepository, memoryProperties);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startMemoryArchiveScheduler(ApplicationReadyEvent event) {
        MemoryArchiveWorker worker = event.getApplicationContext().getBean(MemoryArchiveWorker.class);
        MemoryProperties props = event.getApplicationContext().getBean(MemoryProperties.class);
        if (!props.isEnabled()) {
            return;
        }
        long intervalMs = props.getCleanupIntervalHours() * 3600L * 1000L;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-archive");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(worker::runArchiveCycle, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Memory archive scheduler started, interval={}h", props.getCleanupIntervalHours());
    }
}
