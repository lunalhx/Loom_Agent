package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.AgentApprovalDecisionRequest;
import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.dto.AgentAskRequest;
import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentReplayStreamRequest;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.dto.AgentUserInputRequest;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import cn.lunalhx.ai.trigger.http.agent.AgentHttpQueryService;
import cn.lunalhx.ai.trigger.http.agent.AgentRequestMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentSseResponder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/code")
public class AgentCodeController {

    private final AgentLoopService agentLoopService;
    private final AgentRequestMapper requestMapper;
    private final AgentHttpQueryService queryService;
    private final AgentSseResponder sseResponder;
    private final StreamRequestLimiter streamRequestLimiter;

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody(required = false) AgentAskRequest request,
                           HttpServletRequest httpRequest) {
        AgentRequestMapper.Result<AgentQuestion> result = requestMapper.mapAsk(request);
        if (!result.valid()) {
            return sseResponder.completedAgentError(result.problem());
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
                result.value().getRequestId(),
                () -> agentLoopService.ask(result.value()),
                profile,
                lease::release
        );
    }

    @PostMapping(value = "/approvals/{approvalId}/decide/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter decide(@PathVariable String approvalId,
                             @RequestBody(required = false) AgentApprovalDecisionRequest request) {
        AgentRequestMapper.Result<AgentRequestMapper.ApprovalCommand> result = requestMapper.mapApproval(request);
        if (!result.valid()) {
            return sseResponder.completedAgentError(result.problem());
        }

        return sseResponder.streamAgentEvents(
                "decide",
                UUID.randomUUID().toString(),
                () -> agentLoopService.resume(approvalId, result.value().decision(), result.value().reason()),
                AgentSseResponder.StreamProfile.ALL_EVENTS
        );
    }

    @GetMapping("/approvals/{approvalId}")
    public Response<AgentApprovalResponse> approval(@PathVariable String approvalId) {
        return queryService.approval(approvalId);
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
                                          @RequestBody(required = false) AgentUserInputRequest request) {
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

    @GetMapping("/runs/{runId}/trace")
    public Response<AgentTraceTimelineResponse> trace(@PathVariable String runId) {
        return queryService.trace(runId);
    }

    @GetMapping("/runs/{runId}/replay")
    public Response<AgentReplayResponse> replay(@PathVariable String runId,
                                                @RequestParam(required = false) Boolean includeChildren) {
        return queryService.replay(runId, requestMapper.resolveIncludeChildren(includeChildren, null));
    }

    @PostMapping(value = "/runs/{runId}/replay/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter replayStream(@PathVariable String runId,
                                   @RequestParam(required = false) Boolean includeChildren,
                                   @RequestBody(required = false) AgentReplayStreamRequest request) {
        AgentRequestMapper.Result<String> result = requestMapper.mapRunId(runId);
        if (!result.valid()) {
            return sseResponder.completedReplayError(result.problem());
        }

        boolean resolvedIncludeChildren = requestMapper.resolveIncludeChildren(includeChildren, request);

        return sseResponder.streamReplay(
                runId,
                resolvedIncludeChildren,
                () -> queryService.replayTimeline(runId, resolvedIncludeChildren)
        );
    }
}
