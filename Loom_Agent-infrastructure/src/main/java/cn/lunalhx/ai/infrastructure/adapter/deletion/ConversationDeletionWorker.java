package cn.lunalhx.ai.infrastructure.adapter.deletion;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationDeletion;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import cn.lunalhx.ai.infrastructure.dao.AgentContextArtifactDao;
import cn.lunalhx.ai.infrastructure.dao.AgentMemoryGenerationJobDao;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import cn.lunalhx.ai.infrastructure.dao.AgentUndoSnapshotDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentContextArtifactPO;
import cn.lunalhx.ai.infrastructure.dao.po.AgentUndoSnapshotPO;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
public class ConversationDeletionWorker implements Runnable {

    private static final int MAX_RETRIES = 5;
    private static final long LOCK_DURATION_SECONDS = 60;

    private final ConversationDeletionRepository deletionRepository;
    private final AgentRunRepository runRepository;
    private final AgentRunDao runDao;
    private final AgentTraceEventDao traceEventDao;
    private final AgentRunCheckpointDao checkpointDao;
    private final AgentContextArtifactDao artifactDao;
    private final AgentPendingApprovalDao approvalDao;
    private final AgentUndoSnapshotDao undoSnapshotDao;
    private final AgentMemoryGenerationJobDao memoryJobDao;
    private final AgentLoopService agentLoopService;
    private final ContextBlobStore contextBlobStore;
    private final WorkspaceSnapshotPort workspaceSnapshotPort;

    private final String workerId = UUID.randomUUID().toString();

    public ConversationDeletionWorker(
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
        this.deletionRepository = deletionRepository;
        this.runRepository = runRepository;
        this.runDao = runDao;
        this.traceEventDao = traceEventDao;
        this.checkpointDao = checkpointDao;
        this.artifactDao = artifactDao;
        this.approvalDao = approvalDao;
        this.undoSnapshotDao = undoSnapshotDao;
        this.memoryJobDao = memoryJobDao;
        this.agentLoopService = agentLoopService;
        this.contextBlobStore = contextBlobStore;
        this.workspaceSnapshotPort = workspaceSnapshotPort;
    }

    @Override
    public void run() {
        try {
            recoverStaleTasks();
            List<ConversationDeletion> tasks = deletionRepository.findPendingWork();
            for (ConversationDeletion task : tasks) {
                if (!claimTask(task.getConversationId())) {
                    continue;
                }
                try {
                    processTask(task);
                } catch (Exception e) {
                    log.error("Deletion task failed for conversation {}: {}", task.getConversationId(), e.getMessage(), e);
                    handleFailure(task, e);
                }
            }
        } catch (Exception e) {
            log.error("Deletion worker iteration failed", e);
        }
    }

    private void recoverStaleTasks() {
        String staleThreshold = Instant.now().minus(LOCK_DURATION_SECONDS, ChronoUnit.SECONDS).toString();
        List<ConversationDeletion> stale = deletionRepository.findStaleTasks(staleThreshold);
        for (ConversationDeletion t : stale) {
            deletionRepository.releaseLock(t.getConversationId());
        }
    }

    private boolean claimTask(String conversationId) {
        String lockExpiresAt = Instant.now().plus(LOCK_DURATION_SECONDS, ChronoUnit.SECONDS).toString();
        return deletionRepository.claimTask(conversationId, workerId, lockExpiresAt);
    }

    private void processTask(ConversationDeletion task) {
        String conversationId = task.getConversationId();
        String status = task.getStatus();

        switch (status) {
            case "REQUESTED" -> processRequested(conversationId);
            case "WAITING_FOR_RUNS" -> processWaitingForRuns(conversationId);
            case "PURGING" -> processPurging(conversationId);
            default -> log.warn("Unknown deletion status {} for {}", status, conversationId);
        }
    }

    private void processRequested(String conversationId) {
        List<AgentRun> runs = runRepository.findByConversationId(conversationId);
        agentLoopService.cancelConversation(conversationId);
        // Preserve current retryCount instead of resetting to 0, and release lock
        ConversationDeletion current = deletionRepository.find(conversationId).orElse(null);
        int retryCount = current != null ? current.getRetryCount() : 0;
        deletionRepository.updateStatusAndReleaseLock(conversationId, "WAITING_FOR_RUNS", retryCount, null);
        log.info("Deletion REQUESTED -> WAITING_FOR_RUNS for conversation {} (retryCount={})", conversationId, retryCount);
    }

    private void processWaitingForRuns(String conversationId) {
        if (agentLoopService.hasActiveRuns(conversationId)) {
            agentLoopService.cancelConversation(conversationId);
            // Release lock so next iteration can re-claim without 60s wait
            deletionRepository.releaseLock(conversationId);
            return;
        }
        ConversationDeletion current = deletionRepository.find(conversationId).orElse(null);
        int retryCount = current != null ? current.getRetryCount() : 0;
        deletionRepository.updateStatusAndReleaseLock(conversationId, "PURGING", retryCount, null);
        log.info("Deletion WAITING_FOR_RUNS -> PURGING for conversation {} (retryCount={})", conversationId, retryCount);
        processPurging(conversationId);
    }

    private void processPurging(String conversationId) {
        List<AgentRun> runs = runRepository.findByConversationId(conversationId);
        List<String> runIds = runs.stream().map(AgentRun::getRunId).toList();
        List<String> rootRunIds = runs.stream().map(AgentRun::getRootRunId).filter(id -> id != null).distinct().toList();

        // 1. Cancel memory generation jobs
        if (!runIds.isEmpty()) {
            memoryJobDao.cancelBySourceRunIds(runIds);
        }

        // 2. Delete context artifact files
        List<AgentContextArtifactPO> artifacts = artifactDao.selectByConversationId(conversationId);
        for (AgentContextArtifactPO artifact : artifacts) {
            contextBlobStore.delete(artifact.getStorageUri());
        }

        // 3. Delete undo snapshot Git refs
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

        // 4. Delete database records in order
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
        if (!runIds.isEmpty()) {
            memoryJobDao.deleteBySourceRunIds(runIds);
        }
        undoSnapshotDao.deleteByConversationId(conversationId);
        runDao.deleteByConversationId(conversationId);

        // 5. Mark completed
        deletionRepository.markCompleted(conversationId);
        log.info("Deletion COMPLETED for conversation {}", conversationId);
    }

    private void handleFailure(ConversationDeletion task, Exception e) {
        int newRetryCount = task.getRetryCount() + 1;
        String errorMsg = e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500)) : "unknown error";

        if (newRetryCount >= MAX_RETRIES) {
            deletionRepository.updateStatus(task.getConversationId(), "FAILED", newRetryCount, errorMsg);
            deletionRepository.releaseLock(task.getConversationId());
            log.error("Deletion FAILED for conversation {} after {} retries: {}",
                    task.getConversationId(), newRetryCount, errorMsg);
        } else {
            long backoffSeconds = (long) Math.pow(2, newRetryCount);
            deletionRepository.updateStatus(task.getConversationId(), "REQUESTED", newRetryCount, errorMsg);
            deletionRepository.releaseLock(task.getConversationId());
            log.warn("Deletion retry {} for conversation {} (backoff {}s): {}",
                    newRetryCount, task.getConversationId(), backoffSeconds, errorMsg);
        }
    }
}
