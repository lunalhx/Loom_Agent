package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.AgentAskRequest;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationDeletionService;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.trigger.http.agent.AgentRequestMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentSseResponder;
import cn.lunalhx.ai.types.enums.ResponseCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/code")
public class AgentExecutionController {

    private final AgentLoopService agentLoopService;
    private final AgentRequestMapper requestMapper;
    private final AgentSseResponder sseResponder;
    private final StreamRequestLimiter streamRequestLimiter;
    private final ConversationDeletionService deletionService;

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody(required = false) AgentAskRequest request,
                           HttpServletRequest httpRequest) {
        AgentRequestMapper.Result<AgentQuestion> result = requestMapper.mapAsk(request);
        if (!result.valid()) {
            return sseResponder.completedAgentError(result.problem());
        }

        AgentQuestion question = result.value();
        if (question.getConversationId() != null && deletionService.isConversationDeleted(question.getConversationId())) {
            return sseResponder.completedAgentError(
                    new AgentRequestMapper.Problem(ResponseCode.CONVERSATION_DELETED.getCode(), "会话已删除"));
        }

        String clientKey = streamRequestLimiter.resolveClientKey(httpRequest);
        StreamRequestLimiter.Lease lease = streamRequestLimiter.tryAcquire(clientKey, "agent-ask");
        if (!lease.isAllowed()) {
            return sseResponder.completedAgentError(
                    new AgentRequestMapper.Problem(lease.rejectCode(), lease.rejectMessage()));
        }

        AgentSseResponder.StreamProfile profile = Boolean.TRUE.equals(request.getIncludeTrace())
                ? AgentSseResponder.StreamProfile.ALL_EVENTS
                : AgentSseResponder.StreamProfile.PUBLIC_ASK;

        return sseResponder.streamAgentEvents(
                "ask",
                question.getRequestId(),
                () -> agentLoopService.ask(question),
                profile,
                lease::release
        );
    }

    @PostMapping(value = "/runs/{runId}/resume/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeRun(@PathVariable String runId) {
        AgentRequestMapper.Result<String> result = requestMapper.mapRunId(runId);
        if (!result.valid()) {
            return sseResponder.completedAgentError(result.problem());
        }

        return sseResponder.streamAgentEvents(
                "resumeRun",
                UUID.randomUUID().toString(),
                () -> agentLoopService.resumeRun(runId),
                AgentSseResponder.StreamProfile.WITHOUT_CHECKPOINT
        );
    }

    @PostMapping(value = "/runs/{runId}/input/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeWithUserInput(@PathVariable String runId,
                                          @RequestBody(required = false) cn.lunalhx.ai.api.dto.AgentUserInputRequest request) {
        AgentRequestMapper.Result<AgentRequestMapper.UserInputCommand> result = requestMapper.mapUserInput(runId, request);
        if (!result.valid()) {
            return sseResponder.completedAgentError(result.problem());
        }

        return sseResponder.streamAgentEvents(
                "userInput",
                UUID.randomUUID().toString(),
                () -> agentLoopService.resumeWithUserInput(runId, result.value().action(), result.value().message()),
                AgentSseResponder.StreamProfile.ALL_EVENTS
        );
    }

    @PostMapping("/runs/{runId}/cancel")
    public Response<Boolean> cancelRun(@PathVariable String runId) {
        AgentRequestMapper.Result<String> result = requestMapper.mapRunId(runId);
        if (!result.valid()) {
            return Response.<Boolean>builder()
                    .code(result.problem().code())
                    .info(result.problem().message())
                    .data(false)
                    .build();
        }
        boolean cancelled = agentLoopService.cancelRun(result.value());
        return Response.success(cancelled, cancelled ? "cancelled" : "run_not_active");
    }
}
