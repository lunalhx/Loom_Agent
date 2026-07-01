package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.ConversationDeletionService;
import cn.lunalhx.ai.infrastructure.adapter.deletion.ConversationDeletionWorker;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class DeletionAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(DeletionAutoConfig.class);

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
    @ConditionalOnProperty(prefix = "loom.agent.persistence", name = "mode", havingValue = "sqlite", matchIfMissing = true)
    public ConversationDeletionWorker conversationDeletionWorker(
            ConversationDeletionRepository deletionRepository,
            AgentRunRepository runRepository,
            AgentRunDao runDao,
            AgentTraceEventDao traceEventDao,
            AgentRunCheckpointDao checkpointDao,
            AgentContextArtifactDao artifactDao,
            AgentPendingApprovalDao approvalDao,
            AgentUndoSnapshotDao undoSnapshotDao,
            AgentMemoryGenerationJobDao memoryJobDao,
            AgentLoopService agentLoopService,
            ContextBlobStore contextBlobStore,
            WorkspaceSnapshotPort workspaceSnapshotPort) {
        return new ConversationDeletionWorker(
                deletionRepository, runRepository, runDao, traceEventDao, checkpointDao,
                artifactDao, approvalDao, undoSnapshotDao, memoryJobDao,
                agentLoopService, contextBlobStore, workspaceSnapshotPort);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "loom.agent.persistence", name = "mode", havingValue = "sqlite", matchIfMissing = true)
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
}
