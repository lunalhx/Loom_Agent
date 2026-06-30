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
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentRunDao, but MyBatis DAO is not available");
                }
                log.info("AgentRunRepository: MyBatis (mode=mysql)");
                yield new MybatisAgentRunRepository(dao);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("AgentRunRepository: MyBatis (mode=auto, DAO available)");
                    yield new MybatisAgentRunRepository(dao);
                }
                log.info("AgentRunRepository: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryAgentRunRepository(memoryStoreProperties);
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
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentRunCheckpointDao, but MyBatis DAO is not available");
                }
                log.info("AgentCheckpointRepository: MyBatis (mode=mysql)");
                yield new MybatisAgentCheckpointRepository(dao, objectMapper);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("AgentCheckpointRepository: MyBatis (mode=auto, DAO available)");
                    yield new MybatisAgentCheckpointRepository(dao, objectMapper);
                }
                log.info("AgentCheckpointRepository: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryAgentCheckpointRepository(memoryStoreProperties);
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
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentPendingApprovalDao, but MyBatis DAO is not available");
                }
                log.info("ApprovalStore: MyBatis (mode=mysql)");
                yield new MybatisApprovalStore(dao, objectMapper);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("ApprovalStore: MyBatis (mode=auto, DAO available)");
                    yield new MybatisApprovalStore(dao, objectMapper);
                }
                log.info("ApprovalStore: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryApprovalStore(memoryStoreProperties);
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
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentTraceEventDao, but MyBatis DAO is not available");
                }
                log.info("TraceRecorder: MyBatis (mode=mysql)");
                yield new MybatisTraceRecorder(dao, objectMapper);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("TraceRecorder: MyBatis (mode=auto, DAO available)");
                    yield new MybatisTraceRecorder(dao, objectMapper);
                }
                log.info("TraceRecorder: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryTraceRecorder(memoryStoreProperties);
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
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentContextArtifactDao, but MyBatis DAO is not available");
                }
                log.info("ContextArtifactRepository: MyBatis (mode=mysql)");
                yield new MybatisContextArtifactRepository(dao);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("ContextArtifactRepository: MyBatis (mode=auto, DAO available)");
                    yield new MybatisContextArtifactRepository(dao);
                }
                log.info("ContextArtifactRepository: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryContextArtifactRepository(memoryStoreProperties);
            }
        };
    }

    @Bean
    public ContextBlobStore contextBlobStore(PersistenceProperties persistence,
                                              AgentRuntimeProperties agentRuntimeProperties,
                                              ObjectProvider<AgentContextArtifactDao> artifactDaoProvider,
                                              MemoryStoreProperties memoryStoreProperties) {
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("ContextBlobStore: InMemory (mode=memory)");
                yield new InMemoryContextBlobStore(memoryStoreProperties);
            }
            case MYSQL -> {
                log.info("ContextBlobStore: LocalFile (mode=mysql)");
                yield new LocalFileContextBlobStore(agentRuntimeProperties.getContext().getStorageRoot());
            }
            case AUTO -> {
                AgentContextArtifactDao dao = artifactDaoProvider.getIfAvailable();
                if (dao != null) {
                    log.info("ContextBlobStore: LocalFile (mode=auto, DAO available)");
                    yield new LocalFileContextBlobStore(agentRuntimeProperties.getContext().getStorageRoot());
                }
                log.info("ContextBlobStore: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryContextBlobStore(memoryStoreProperties);
            }
        };
    }

    @Bean
    public InitializingBean persistenceValidator(PersistenceProperties persistence,
                                                  ObjectProvider<AgentRunDao> runDaoProvider,
                                                  ObjectProvider<AgentRunCheckpointDao> checkpointDaoProvider,
                                                  ObjectProvider<AgentPendingApprovalDao> approvalDaoProvider,
                                                  ObjectProvider<AgentTraceEventDao> traceDaoProvider,
                                                  ObjectProvider<AgentContextArtifactDao> artifactDaoProvider,
                                                  AgentRuntimeProperties agentRuntimeProperties) {
        return () -> {
            boolean runOk = runDaoProvider.getIfAvailable() != null;
            boolean checkpointOk = checkpointDaoProvider.getIfAvailable() != null;
            boolean approvalOk = approvalDaoProvider.getIfAvailable() != null;
            boolean traceOk = traceDaoProvider.getIfAvailable() != null;
            boolean artifactOk = artifactDaoProvider.getIfAvailable() != null;
            boolean allDaosOk = runOk && checkpointOk && approvalOk && traceOk && artifactOk;

            log.info("Persistence mode={}, required={}", persistence.getMode(), persistence.getRequired());
            log.info("DAOs — AgentRunDao={}, AgentRunCheckpointDao={}, AgentPendingApprovalDao={}, AgentTraceEventDao={}, AgentContextArtifactDao={}",
                    status(runOk), status(checkpointOk), status(approvalOk), status(traceOk), status(artifactOk));

            if (persistence.isExplicitMysql()) {
                if (!allDaosOk) {
                    List<String> missing = new ArrayList<>();
                    if (!runOk) missing.add("AgentRunDao");
                    if (!checkpointOk) missing.add("AgentRunCheckpointDao");
                    if (!approvalOk) missing.add("AgentPendingApprovalDao");
                    if (!traceOk) missing.add("AgentTraceEventDao");
                    if (!artifactOk) missing.add("AgentContextArtifactDao");
                    throw new IllegalStateException(
                            "persistence mode=mysql requires all MyBatis DAOs, missing: " + String.join(", ", missing));
                }
                String storageRoot = agentRuntimeProperties.getContext().getStorageRoot();
                if (StringUtils.isBlank(storageRoot)) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AGENT_CONTEXT_STORAGE_ROOT to be configured");
                }
                log.info("ContextBlobStore storage root: {}", storageRoot);
            }

            if (persistence.isAuto() && !allDaosOk) {
                if (Boolean.TRUE.equals(persistence.getRequired())) {
                    List<String> missing = new ArrayList<>();
                    if (!runOk) missing.add("AgentRunDao");
                    if (!checkpointOk) missing.add("AgentRunCheckpointDao");
                    if (!approvalOk) missing.add("AgentPendingApprovalDao");
                    if (!traceOk) missing.add("AgentTraceEventDao");
                    if (!artifactOk) missing.add("AgentContextArtifactDao");
                    throw new IllegalStateException(
                            "persistence mode=auto with required=true cannot fall back to memory, missing DAOs: "
                                    + String.join(", ", missing));
                }
                log.warn("Some MyBatis DAOs unavailable — falling back to InMemory implementations (mode=auto, required=false)");
            }

            if (persistence.isExplicitMemory()) {
                log.info("Persistence mode=memory — all agent state stored in memory only");
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
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentUndoSnapshotDao, but MyBatis DAO is not available");
                }
                log.info("UndoSnapshotRepository: MyBatis (mode=mysql)");
                yield new MybatisUndoSnapshotRepository(dao, objectMapper);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("UndoSnapshotRepository: MyBatis (mode=auto, DAO available)");
                    yield new MybatisUndoSnapshotRepository(dao, objectMapper);
                }
                log.info("UndoSnapshotRepository: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryUndoSnapshotRepository(memoryStoreProperties);
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
            case MYSQL -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=mysql requires AgentWorkspaceUndoLockDao, but MyBatis DAO is not available");
                }
                log.info("WorkspaceUndoLockRepository: MyBatis (mode=mysql)");
                yield new MybatisWorkspaceUndoLockRepository(dao);
            }
            case AUTO -> {
                if (dao != null) {
                    log.info("WorkspaceUndoLockRepository: MyBatis (mode=auto, DAO available)");
                    yield new MybatisWorkspaceUndoLockRepository(dao);
                }
                log.info("WorkspaceUndoLockRepository: InMemory (mode=auto, DAO unavailable)");
                yield new InMemoryWorkspaceUndoLockRepository();
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

    private static String status(boolean ok) {
        return ok ? "available" : "unavailable";
    }
}
