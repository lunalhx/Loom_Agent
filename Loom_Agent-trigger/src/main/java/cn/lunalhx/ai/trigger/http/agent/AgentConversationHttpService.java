package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.ConversationDeletionResponse;
import cn.lunalhx.ai.api.dto.ConversationSummaryResponse;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationDeletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentConversationHttpService {

    private final AgentRunRepository runRepository;
    private final ConversationDeletionService deletionService;

    public List<ConversationSummaryResponse> listConversations() {
        return runRepository.listConversationSummaries().stream()
                .map(s -> ConversationSummaryResponse.builder()
                        .conversationId(s.getConversationId())
                        .title(s.getTitle())
                        .runCount(s.getRunCount())
                        .workspace(s.getWorkspace())
                        .build())
                .toList();
    }

    public ConversationDeletionService.DeletionRequestResult requestDeletion(String conversationId) {
        ConversationDeletionService.DeletionRequestResult result =
                deletionService.requestDeletion(conversationId);
        if (result.invalid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, result.lastError());
        }
        if (result.notFound()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }
        return result;
    }

    public Optional<ConversationDeletionService.DeletionRequestResult> getDeletionStatus(
            String conversationId) {
        return deletionService.getDeletionStatus(conversationId);
    }

    public static ConversationDeletionResponse toDeletionResponse(
            ConversationDeletionService.DeletionRequestResult result) {
        return ConversationDeletionResponse.builder()
                .conversationId(result.conversationId())
                .status(result.status())
                .requestedAt(result.requestedAt())
                .completedAt(result.completedAt())
                .retryCount(result.retryCount())
                .lastError(result.lastError())
                .build();
    }
}
