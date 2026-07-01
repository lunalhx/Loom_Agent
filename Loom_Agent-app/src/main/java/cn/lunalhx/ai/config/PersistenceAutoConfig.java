package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryBackgroundShellTaskRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryUndoSnapshotRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryWorkspaceUndoLockRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisBackgroundShellTaskRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisTraceRecorder;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisUndoSnapshotRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisWorkspaceUndoLockRepository;
import cn.lunalhx.ai.infrastructure.adapter.snapshot.GitWorkspaceSnapshotAdapter;
import cn.lunalhx.ai.infrastructure.adapter.snapshot.UndoSnapshotCleanupTask;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import cn.lunalhx.ai.infrastructure.context.LocalFileContextBlobStore;
import cn.lunalhx.ai.infrastructure.dao.AgentContextArtifactDao;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import cn.lunalhx.ai.infrastructure.dao.AgentUndoSnapshotDao;
import cn.lunalhx.ai.infrastructure.dao.AgentWorkspaceUndoLockDao;
import cn.lunalhx.ai.infrastructure.dao.BackgroundShellTaskDao;
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

    @Bean
    public ContextArtifactRepository contextArtifactRepository(PersistenceProperties persistence,
                                                                ObjectProvider<AgentContextArtifactDao> daoProvider,
                                                                MemoryStoreProperties memoryStoreProperties) {
        return selectByMode(persistence, "ContextArtifactRepository", "InMemory", "MyBatis",
                () -> new InMemoryContextArtifactRepository(memoryStoreProperties),
                () -> new MybatisContextArtifactRepository(requireDao(daoProvider, "AgentContextArtifactDao")));
    }

    @Bean
    public ContextBlobStore contextBlobStore(PersistenceProperties persistence,
                                              AgentRuntimeProperties agentRuntimeProperties,
                                              MemoryStoreProperties memoryStoreProperties) {
        return selectByMode(persistence, "ContextBlobStore", "InMemory", "LocalFile",
                () -> new InMemoryContextBlobStore(memoryStoreProperties),
                () -> new LocalFileContextBlobStore(agentRuntimeProperties.getContext().getStorageRoot()));
    }

    @Bean
    public WorkspaceSnapshotPort workspaceSnapshotPort(AgentRuntimeProperties agentRuntimeProperties) {
        return new GitWorkspaceSnapshotAdapter(agentRuntimeProperties.getUndo());
    }

    @Bean
    public UndoSnapshotRepository undoSnapshotRepository(PersistenceProperties persistence,
                                                          ObjectProvider<AgentUndoSnapshotDao> daoProvider,
                                                          ObjectMapper objectMapper,
                                                          MemoryStoreProperties memoryStoreProperties) {
        return selectByMode(persistence, "UndoSnapshotRepository", "InMemory", "MyBatis",
                () -> new InMemoryUndoSnapshotRepository(memoryStoreProperties),
                () -> new MybatisUndoSnapshotRepository(requireDao(daoProvider, "AgentUndoSnapshotDao"), objectMapper));
    }

    @Bean
    public WorkspaceUndoLockRepository workspaceUndoLockRepository(PersistenceProperties persistence,
                                                                     ObjectProvider<AgentWorkspaceUndoLockDao> daoProvider,
                                                                     MemoryStoreProperties memoryStoreProperties) {
        return selectByMode(persistence, "WorkspaceUndoLockRepository", "InMemory", "MyBatis",
                () -> new InMemoryWorkspaceUndoLockRepository(),
                () -> new MybatisWorkspaceUndoLockRepository(requireDao(daoProvider, "AgentWorkspaceUndoLockDao")));
    }

    @Bean
    public UndoSnapshotCleanupTask undoSnapshotCleanupTask(UndoSnapshotRepository undoSnapshotRepository,
                                                            WorkspaceUndoLockRepository workspaceUndoLockRepository,
                                                            WorkspaceSnapshotPort workspaceSnapshotPort,
                                                            AgentRuntimeProperties agentRuntimeProperties) {
        return new UndoSnapshotCleanupTask(undoSnapshotRepository, workspaceUndoLockRepository,
                workspaceSnapshotPort, agentRuntimeProperties.getUndo());
    }

    @Bean
    public BackgroundShellTaskRepository backgroundShellTaskRepository(PersistenceProperties persistence,
                                                                       ObjectProvider<BackgroundShellTaskDao> daoProvider) {
        return selectByMode(persistence, "BackgroundShellTaskRepository", "InMemory", "MyBatis",
                () -> new InMemoryBackgroundShellTaskRepository(),
                () -> new MybatisBackgroundShellTaskRepository(requireDao(daoProvider, "BackgroundShellTaskDao")));
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
