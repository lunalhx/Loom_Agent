package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisTraceRecorder;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PersistenceAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(PersistenceAutoConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "loom.agent.persistence")
    public PersistenceProperties persistenceProperties() {
        return new PersistenceProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "loom.agent.memory-store")
    public MemoryStoreProperties memoryStoreProperties() {
        return new MemoryStoreProperties();
    }

    @Bean
    public AgentRunRepository agentRunRepository(PersistenceProperties persistence,
                                                  ObjectProvider<AgentRunDao> daoProvider,
                                                  MemoryStoreProperties memoryStoreProperties) {
        return selectByMode(persistence, "AgentRunRepository", "InMemory", "MyBatis",
                () -> new InMemoryAgentRunRepository(memoryStoreProperties),
                () -> new MybatisAgentRunRepository(requireDao(daoProvider, "AgentRunDao")));
    }

    @Bean
    public AgentCheckpointRepository agentCheckpointRepository(PersistenceProperties persistence,
                                                                ObjectProvider<AgentRunCheckpointDao> daoProvider,
                                                                ObjectMapper objectMapper,
                                                                MemoryStoreProperties memoryStoreProperties) {
        return selectByMode(persistence, "AgentCheckpointRepository", "InMemory", "MyBatis",
                () -> new InMemoryAgentCheckpointRepository(memoryStoreProperties),
                () -> new MybatisAgentCheckpointRepository(requireDao(daoProvider, "AgentRunCheckpointDao"), objectMapper));
    }

    @Bean
    public ApprovalStore approvalStore(PersistenceProperties persistence,
                                        ObjectProvider<AgentPendingApprovalDao> daoProvider,
                                        ObjectMapper objectMapper,
                                        MemoryStoreProperties memoryStoreProperties) {
        return selectByMode(persistence, "ApprovalStore", "InMemory", "MyBatis",
                () -> new InMemoryApprovalStore(memoryStoreProperties),
                () -> new MybatisApprovalStore(requireDao(daoProvider, "AgentPendingApprovalDao"), objectMapper));
    }

    @Bean
    public TraceRecorder traceRecorder(PersistenceProperties persistence,
                                        ObjectProvider<AgentTraceEventDao> daoProvider,
                                        ObjectMapper objectMapper,
                                        MemoryStoreProperties memoryStoreProperties) {
        return selectByMode(persistence, "TraceRecorder", "InMemory", "MyBatis",
                () -> new InMemoryTraceRecorder(memoryStoreProperties),
                () -> new MybatisTraceRecorder(requireDao(daoProvider, "AgentTraceEventDao"), objectMapper));
    }

    private <T> T selectByMode(PersistenceProperties persistence,
                                String componentName,
                                String memoryImplName,
                                String sqliteImplName,
                                Supplier<T> memoryFactory,
                                Supplier<T> sqliteFactory) {
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("{}: {} (mode=memory)", componentName, memoryImplName);
                yield memoryFactory.get();
            }
            case SQLITE -> {
                log.info("{}: {} (mode=sqlite)", componentName, sqliteImplName);
                yield sqliteFactory.get();
            }
        };
    }

    private static <D> D requireDao(ObjectProvider<D> daoProvider, String daoClassName) {
        D dao = daoProvider.getIfAvailable();
        if (dao == null) {
            throw new IllegalStateException(
                    "persistence mode=sqlite requires " + daoClassName + ", but MyBatis DAO is not available");
        }
        return dao;
    }

}
