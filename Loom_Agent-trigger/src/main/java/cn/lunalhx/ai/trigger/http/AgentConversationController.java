package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.ConversationDeletionResponse;
import cn.lunalhx.ai.api.dto.ConversationSummaryResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationDeletionService;
import cn.lunalhx.ai.trigger.http.agent.AgentConversationHttpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/code")
public class AgentConversationController {

    private final AgentConversationHttpService conversationHttpService;

    @GetMapping("/conversations")
    public Response<List<ConversationSummaryResponse>> listConversations() {
        return Response.success(conversationHttpService.listConversations());
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Response<ConversationDeletionResponse> deleteConversation(@PathVariable String conversationId) {
        ConversationDeletionService.DeletionRequestResult result =
                conversationHttpService.requestDeletion(conversationId);
        HttpStatus httpStatus = result.isAlreadyCompleted() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return Response.success(AgentConversationHttpService.toDeletionResponse(result),
                result.isAlreadyCompleted() ? "already deleted" : "deletion requested");
    }

    @GetMapping("/conversations/{conversationId}/deletion")
    public Response<ConversationDeletionResponse> deletionStatus(@PathVariable String conversationId) {
        return conversationHttpService.getDeletionStatus(conversationId)
                .map(result -> Response.success(AgentConversationHttpService.toDeletionResponse(result)))
                .orElseGet(() -> Response.<ConversationDeletionResponse>success(null, "not found"));
    }
}
