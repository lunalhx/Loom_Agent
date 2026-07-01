package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.service.MemoryExtractionService;
import cn.lunalhx.ai.domain.memory.service.MemorySelectionService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryGenerationJobRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentMemoryRepository;
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
                                                        ObjectProvider<AgentMemoryDao> daoProvider) {
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
                log.info("AgentMemoryRepository: MyBatis (mode=sqlite)");
                yield new MybatisAgentMemoryRepository(dao);
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
                                                         MemoryProperties memoryProperties) {
        return new MemorySelectionService(agentMemoryRepository,
                memoryProperties.getMaxSelected(),
                memoryProperties.getMaxInjectedChars());
    }

    @Bean
    public MemoryExtractionService memoryExtractionService(ModelGateway modelGateway,
                                                            ObjectMapper objectMapper,
                                                            MemoryProperties memoryProperties) {
        return new MemoryExtractionService(modelGateway, objectMapper,
                memoryProperties.getExtractionModel());
    }
}
