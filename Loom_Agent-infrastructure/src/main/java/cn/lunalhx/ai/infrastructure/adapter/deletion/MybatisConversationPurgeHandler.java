package cn.lunalhx.ai.infrastructure.adapter.deletion;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.service.context.ContextArtifactPurgeService;
import cn.lunalhx.ai.infrastructure.dao.AgentContextArtifactDao;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import cn.lunalhx.ai.infrastructure.dao.AgentUndoSnapshotDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public class MybatisConversationPurgeHandler implements ConversationPurgeHandler {

    private static final Logger log = LoggerFactory.getLogger(MybatisConversationPurgeHandler.class);

    private final AgentRunRepository runRepository;
    private final AgentRunDao runDao;
    private final AgentTraceEventDao traceEventDao;
    private final AgentRunCheckpointDao checkpointDao;
    private final AgentContextArtifactDao artifactDao;
    private final AgentPendingApprovalDao approvalDao;
    private final AgentUndoSnapshotDao undoSnapshotDao;
    private final ContextArtifactPurgeService purgeService;
    private final WorkspaceSnapshotPort workspaceSnapshotPort;
    private final ContextArtifactRepository artifactRepository;

    public MybatisConversationPurgeHandler(
            AgentRunRepository runRepository,
            AgentRunDao runDao,
            AgentTraceEventDao traceEventDao,
            AgentRunCheckpointDao checkpointDao,
            AgentContextArtifactDao artifactDao,
            AgentPendingApprovalDao approvalDao,
            AgentUndoSnapshotDao undoSnapshotDao,
            ContextArtifactPurgeService purgeService,
            WorkspaceSnapshotPort workspaceSnapshotPort,
            ContextArtifactRepository artifactRepository) {
        this.runRepository = runRepository;
        this.runDao = runDao;
        this.traceEventDao = traceEventDao;
        this.checkpointDao = checkpointDao;
        this.artifactDao = artifactDao;
        this.approvalDao = approvalDao;
        this.undoSnapshotDao = undoSnapshotDao;
        this.purgeService = purgeService;
        this.workspaceSnapshotPort = workspaceSnapshotPort;
        this.artifactRepository = artifactRepository;
    }

    @Override
    public void purge(String conversationId) {
        List<AgentRun> runs = runRepository.findByConversationId(conversationId);
        List<String> runIds = runs.stream().map(AgentRun::getRunId).toList();
        List<String> rootRunIds = runs.stream().map(AgentRun::getRootRunId).filter(id -> id != null).distinct().toList();

        // 1. Delete context artifact files via purgeService strict
        List<ContextArtifact> artifacts = artifactRepository.listByConversationId(conversationId);
        for (ContextArtifact artifact : artifacts) {
            purgeService.purgeArtifactStrict(artifact);
        }

        // 2. Delete undo snapshot Git refs
        List<AgentUndoSnapshotPO> snapshots = undoSnapshotDao.selectByConversationId(conversationId);
        for (AgentUndoSnapshotPO snapshot : snapshots) {
            if (snapshot.getWorkspace() != null) {
                try {
                    workspaceSnapshotPort.deleteSnapshotRefs(Path.of(snapshot.getWorkspace()), snapshot.getRunId());
                } catch (Exception e) {
                    log.warn("Failed to delete snapshot refs for {}: {}", snapshot.getRunId(), e.getMessage());
                }
            }
        }

        // 3. Delete database records in order
        approvalDao.deleteByConversationId(conversationId);
        artifactDao.deleteByConversationId(conversationId);

        if (!runIds.isEmpty()) {
            checkpointDao.deleteByRunIds(runIds);
        }
        if (!runIds.isEmpty()) {
            traceEventDao.deleteByRunIds(runIds);
        }
        if (!rootRunIds.isEmpty()) {
            traceEventDao.deleteByRootRunIds(rootRunIds);
        }
        undoSnapshotDao.deleteByConversationId(conversationId);
        runDao.deleteByConversationId(conversationId);

        log.info("Mybatis purge completed for conversation {}", conversationId);
    }
}