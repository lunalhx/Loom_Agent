package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.context.ContextArtifactPurgeService;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationDeletionService;
import cn.lunalhx.ai.infrastructure.adapter.cleanup.ContextArtifactCleanupTask;
import cn.lunalhx.ai.infrastructure.adapter.deletion.ConversationDeletionWorker;
import cn.lunalhx.ai.infrastructure.adapter.deletion.ConversationPurgeHandler;
import cn.lunalhx.ai.infrastructure.adapter.deletion.InMemoryConversationPurgeHandler;
import cn.lunalhx.ai.infrastructure.adapter.deletion.MybatisConversationPurgeHandler;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryConversationDeletionRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisConversationDeletionRepository;
import cn.lunalhx.ai.infrastructure.dao.AgentContextArtifactDao;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryGenerationJobDao;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import cn.lunalhx.ai.infrastructure.dao.AgentUndoSnapshotDao;
import cn.lunalhx.ai.infrastructure.dao.ConversationDeletionDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class DeletionAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(DeletionAutoConfig.class);

    @Bean
    public ContextArtifactPurgeService contextArtifactPurgeService(
            ContextArtifactRepository contextArtifactRepository,
            ContextBlobStore contextBlobStore) {
        return new ContextArtifactPurgeService(contextArtifactRepository, contextBlobStore);
    }

    @Bean
    public ConversationDeletionRepository conversationDeletionRepository(
            PersistenceProperties persistence,
            ObjectProvider<ConversationDeletionDao> daoProvider) {
        ConversationDeletionDao dao = daoProvider.getIfAvailable();
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("ConversationDeletionRepository: InMemory (mode=memory)");
                yield new InMemoryConversationDeletionRepository();
            }
            case SQLITE -> {
                if (dao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires ConversationDeletionDao, but MyBatis DAO is not available");
                }
                log.info("ConversationDeletionRepository: MyBatis (mode=sqlite)");
                yield new MybatisConversationDeletionRepository(dao);
            }
        };
    }

    @Bean
    public ConversationDeletionService conversationDeletionService(
            AgentRunRepository runRepository,
            ConversationDeletionRepository deletionRepository,
            AgentLoopService agentLoopService) {
        return new ConversationDeletionService(runRepository, deletionRepository, agentLoopService);
    }

    @Bean
    public ConversationPurgeHandler conversationPurgeHandler(
            PersistenceProperties persistence,
            AgentRunRepository runRepository,
            ObjectProvider<AgentRunDao> runDaoProvider,
            ObjectProvider<AgentTraceEventDao> traceEventDaoProvider,
            ObjectProvider<AgentRunCheckpointDao> checkpointDaoProvider,
            ObjectProvider<AgentContextArtifactDao> artifactDaoProvider,
            ObjectProvider<AgentPendingApprovalDao> approvalDaoProvider,
            ObjectProvider<AgentUndoSnapshotDao> undoSnapshotDaoProvider,
            ObjectProvider<AgentMemoryGenerationJobDao> memoryJobDaoProvider,
            ContextArtifactPurgeService purgeService,
            WorkspaceSnapshotPort workspaceSnapshotPort,
            ContextArtifactRepository artifactRepository,
            ContextBlobStore contextBlobStore) {
        return switch (persistence.getMode()) {
            case MEMORY -> {
                log.info("ConversationPurgeHandler: InMemory");
                yield new InMemoryConversationPurgeHandler(artifactRepository, contextBlobStore);
            }
            case SQLITE -> {
                AgentRunDao runDao = runDaoProvider.getIfAvailable();
                AgentTraceEventDao traceEventDao = traceEventDaoProvider.getIfAvailable();
                AgentRunCheckpointDao checkpointDao = checkpointDaoProvider.getIfAvailable();
                AgentContextArtifactDao artifactDao = artifactDaoProvider.getIfAvailable();
                AgentPendingApprovalDao approvalDao = approvalDaoProvider.getIfAvailable();
                AgentUndoSnapshotDao undoSnapshotDao = undoSnapshotDaoProvider.getIfAvailable();
                AgentMemoryGenerationJobDao memoryJobDao = memoryJobDaoProvider.getIfAvailable();
                if (runDao == null || traceEventDao == null || checkpointDao == null
                        || artifactDao == null || approvalDao == null
                        || undoSnapshotDao == null || memoryJobDao == null) {
                    throw new IllegalStateException(
                            "persistence mode=sqlite requires all DAOs, but some are not available");
                }
                log.info("ConversationPurgeHandler: Mybatis");
                yield new MybatisConversationPurgeHandler(
                        runRepository, runDao, traceEventDao, checkpointDao,
                        artifactDao, approvalDao, undoSnapshotDao, memoryJobDao,
                        purgeService, workspaceSnapshotPort, artifactRepository);
            }
        };
    }

    @Bean
    public ConversationDeletionWorker conversationDeletionWorker(
            ConversationDeletionRepository deletionRepository,
            AgentRunRepository runRepository,
            AgentLoopService agentLoopService,
            ConversationPurgeHandler purgeHandler) {
        return new ConversationDeletionWorker(deletionRepository, runRepository, agentLoopService, purgeHandler);
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService deletionWorkerScheduler(ConversationDeletionWorker worker) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "conv-deletion-worker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(worker, 1, 1, TimeUnit.SECONDS);
        log.info("ConversationDeletionWorker scheduled at 1s fixed-delay");
        return scheduler;
    }

    @Bean
    public ContextArtifactCleanupTask contextArtifactCleanupTask(
            ContextArtifactRepository contextArtifactRepository,
            ContextArtifactPurgeService purgeService,
            AgentLoopService agentLoopService,
            AgentRuntimeProperties agentRuntimeProperties) {
        return new ContextArtifactCleanupTask(contextArtifactRepository, purgeService,
                agentLoopService, agentRuntimeProperties.getContext());
    }
}
