package cn.lunalhx.ai.infrastructure.adapter.deletion;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationDeletion;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import lombok.extern.slf4j.Slf4j;

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
    private final AgentLoopService agentLoopService;
    private final ConversationPurgeHandler purgeHandler;

    private final String workerId = UUID.randomUUID().toString();

    public ConversationDeletionWorker(
            ConversationDeletionRepository deletionRepository,
            AgentRunRepository runRepository,
            AgentLoopService agentLoopService,
            ConversationPurgeHandler purgeHandler) {
        this.deletionRepository = deletionRepository;
        this.runRepository = runRepository;
        this.agentLoopService = agentLoopService;
        this.purgeHandler = purgeHandler;
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

    private void processTask(ConversationDeletion task) throws Exception {
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
        agentLoopService.cancelConversation(conversationId);
        ConversationDeletion current = deletionRepository.find(conversationId).orElse(null);
        int retryCount = current != null ? current.getRetryCount() : 0;
        deletionRepository.updateStatusAndReleaseLock(conversationId, "WAITING_FOR_RUNS", retryCount, null);
        log.info("Deletion REQUESTED -> WAITING_FOR_RUNS for conversation {} (retryCount={})", conversationId, retryCount);
    }

    private void processWaitingForRuns(String conversationId) throws Exception {
        if (agentLoopService.hasActiveRuns(conversationId)) {
            agentLoopService.cancelConversation(conversationId);
            deletionRepository.releaseLock(conversationId);
            return;
        }
        ConversationDeletion current = deletionRepository.find(conversationId).orElse(null);
        int retryCount = current != null ? current.getRetryCount() : 0;
        deletionRepository.updateStatusAndReleaseLock(conversationId, "PURGING", retryCount, null);
        log.info("Deletion WAITING_FOR_RUNS -> PURGING for conversation {} (retryCount={})", conversationId, retryCount);
        processPurging(conversationId);
    }

    private void processPurging(String conversationId) throws Exception {
        purgeHandler.purge(conversationId);
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
