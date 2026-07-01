package cn.lunalhx.ai.domain.agent.service.conversation;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationDeletionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationDeletion;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
public class ConversationDeletionService {

    private final AgentRunRepository runRepository;
    private final ConversationDeletionRepository deletionRepository;
    private final AgentLoopService agentLoopService;

    public ConversationDeletionService(AgentRunRepository runRepository,
                                       ConversationDeletionRepository deletionRepository,
                                       AgentLoopService agentLoopService) {
        this.runRepository = runRepository;
        this.deletionRepository = deletionRepository;
        this.agentLoopService = agentLoopService;
    }

    public DeletionRequestResult requestDeletion(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return DeletionRequestResult.invalidResult("conversationId is required");
        }

        Optional<ConversationDeletion> existing = deletionRepository.find(conversationId);
        if (existing.isPresent()) {
            ConversationDeletion d = existing.get();
            if ("FAILED".equals(d.getStatus())) {
                deletionRepository.resetForRetry(conversationId);
                d.setStatus("REQUESTED");
                d.setRetryCount(0);
                d.setLastError(null);
            }
            return DeletionRequestResult.from(d);
        }

        List<AgentRun> runs = runRepository.findByConversationId(conversationId);
        if (runs.isEmpty()) {
            return DeletionRequestResult.notFoundResult();
        }

        ConversationDeletion deletion = ConversationDeletion.builder()
                .conversationId(conversationId)
                .status("REQUESTED")
                .requestedAt(Instant.now())
                .updatedAt(Instant.now())
                .retryCount(0)
                .build();
        deletionRepository.save(deletion);

        cancelActiveRuns(conversationId);

        return DeletionRequestResult.from(deletion);
    }

    public Optional<DeletionRequestResult> getDeletionStatus(String conversationId) {
        return deletionRepository.find(conversationId).map(DeletionRequestResult::from);
    }

    public boolean isConversationDeleted(String conversationId) {
        return deletionRepository.find(conversationId)
                .map(d -> !"FAILED".equals(d.getStatus()))
                .orElse(false);
    }

    private void cancelActiveRuns(String conversationId) {
        agentLoopService.cancelConversation(conversationId);
    }

    public record DeletionRequestResult(String conversationId, String status,
                                        String requestedAt, String completedAt,
                                        int retryCount, String lastError,
                                        boolean notFound, boolean invalid) {

        public boolean isAccepted() {
            return !notFound && !invalid;
        }

        public boolean isAlreadyCompleted() {
            return "COMPLETED".equals(status);
        }

        static DeletionRequestResult from(ConversationDeletion d) {
            return new DeletionRequestResult(
                    d.getConversationId(), d.getStatus(),
                    d.getRequestedAt() != null ? d.getRequestedAt().toString() : null,
                    d.getCompletedAt() != null ? d.getCompletedAt().toString() : null,
                    d.getRetryCount(), d.getLastError(),
                    false, false);
        }

        static DeletionRequestResult notFoundResult() {
            return new DeletionRequestResult(null, null, null, null, 0, null, true, false);
        }

        static DeletionRequestResult invalidResult(String message) {
            return new DeletionRequestResult(null, null, null, null, 0, message, false, true);
        }
    }
}
