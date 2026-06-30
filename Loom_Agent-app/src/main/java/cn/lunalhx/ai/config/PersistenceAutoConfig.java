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
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryUndoSnapshotRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryWorkspaceUndoLockRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisApprovalStore;
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
import com.fasterxml.jackson.databind.ObjectMapper;
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
        AgentRunDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("AgentRunRepository: InMemory (mode=memory)");
                yield new InMemoryAgentRunRepository(memoryStoreProperties);
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires AgentRunDao, but MyBatis DAO is not available");
                }
                log.info("AgentRunRepository: MyBatis (mode=sqlite)");
                yield new MybatisAgentRunRepository(dao);
            }
        };
    }

    @Bean
    public AgentCheckpointRepository agentCheckpointRepository(PersistenceProperties persistence,
                                                                ObjectProvider<AgentRunCheckpointDao> daoProvider,
                                                                ObjectMapper objectMapper,
                                                                MemoryStoreProperties memoryStoreProperties) {
        AgentRunCheckpointDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("AgentCheckpointRepository: InMemory (mode=memory)");
                yield new InMemoryAgentCheckpointRepository(memoryStoreProperties);
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires AgentRunCheckpointDao, but MyBatis DAO is not available");
                }
                log.info("AgentCheckpointRepository: MyBatis (mode=sqlite)");
                yield new MybatisAgentCheckpointRepository(dao, objectMapper);
            }
        };
    }

    @Bean
    public ApprovalStore approvalStore(PersistenceProperties persistence,
                                        ObjectProvider<AgentPendingApprovalDao> daoProvider,
                                        ObjectMapper objectMapper,
                                        MemoryStoreProperties memoryStoreProperties) {
        AgentPendingApprovalDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("ApprovalStore: InMemory (mode=memory)");
                yield new InMemoryApprovalStore(memoryStoreProperties);
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires AgentPendingApprovalDao, but MyBatis DAO is not available");
                }
                log.info("ApprovalStore: MyBatis (mode=sqlite)");
                yield new MybatisApprovalStore(dao, objectMapper);
            }
        };
    }

    @Bean
    public TraceRecorder traceRecorder(PersistenceProperties persistence,
                                        ObjectProvider<AgentTraceEventDao> daoProvider,
                                        ObjectMapper objectMapper,
                                        MemoryStoreProperties memoryStoreProperties) {
        AgentTraceEventDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("TraceRecorder: InMemory (mode=memory)");
                yield new InMemoryTraceRecorder(memoryStoreProperties);
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires AgentTraceEventDao, but MyBatis DAO is not available");
                }
                log.info("TraceRecorder: MyBatis (mode=sqlite)");
                yield new MybatisTraceRecorder(dao, objectMapper);
            }
        };
    }

    @Bean
    public ContextArtifactRepository contextArtifactRepository(PersistenceProperties persistence,
                                                                ObjectProvider<AgentContextArtifactDao> daoProvider,
                                                                MemoryStoreProperties memoryStoreProperties) {
        AgentContextArtifactDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("ContextArtifactRepository: InMemory (mode=memory)");
                yield new InMemoryContextArtifactRepository(memoryStoreProperties);
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires AgentContextArtifactDao, but MyBatis DAO is not available");
                }
                log.info("ContextArtifactRepository: MyBatis (mode=sqlite)");
                yield new MybatisContextArtifactRepository(dao);
            }
        };
    }

    @Bean
    public ContextBlobStore contextBlobStore(PersistenceProperties persistence,
                                              AgentRuntimeProperties agentRuntimeProperties,
                                              MemoryStoreProperties memoryStoreProperties) {
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("ContextBlobStore: InMemory (mode=memory)");
                yield new InMemoryContextBlobStore(memoryStoreProperties);
            }
            case SQLITE -> {
                log.info("ContextBlobStore: LocalFile (mode=sqlite)");
                yield new LocalFileContextBlobStore(agentRuntimeProperties.getContext().getStorageRoot());
            }
        };
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
        AgentUndoSnapshotDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("UndoSnapshotRepository: InMemory (mode=memory)");
                yield new InMemoryUndoSnapshotRepository(memoryStoreProperties);
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires AgentUndoSnapshotDao, but MyBatis DAO is not available");
                }
                log.info("UndoSnapshotRepository: MyBatis (mode=sqlite)");
                yield new MybatisUndoSnapshotRepository(dao, objectMapper);
            }
        };
    }

    @Bean
    public WorkspaceUndoLockRepository workspaceUndoLockRepository(PersistenceProperties persistence,
                                                                     ObjectProvider<AgentWorkspaceUndoLockDao> daoProvider,
                                                                     MemoryStoreProperties memoryStoreProperties) {
        AgentWorkspaceUndoLockDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("WorkspaceUndoLockRepository: InMemory (mode=memory)");
                yield new InMemoryWorkspaceUndoLockRepository();
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires AgentWorkspaceUndoLockDao, but MyBatis DAO is not available");
                }
                log.info("WorkspaceUndoLockRepository: MyBatis (mode=sqlite)");
                yield new MybatisWorkspaceUndoLockRepository(dao);
            }
        };
    }

    @Bean
    public UndoSnapshotCleanupTask undoSnapshotCleanupTask(UndoSnapshotRepository undoSnapshotRepository,
                                                            WorkspaceUndoLockRepository workspaceUndoLockRepository,
                                                            WorkspaceSnapshotPort workspaceSnapshotPort,
                                                            AgentRuntimeProperties agentRuntimeProperties) {
        return new UndoSnapshotCleanupTask(undoSnapshotRepository, workspaceUndoLockRepository,
                workspaceSnapshotPort, agentRuntimeProperties.getUndo());
    }

}
