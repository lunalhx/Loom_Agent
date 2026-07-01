package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.AgentApprovalDecisionRequest;
import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.trigger.http.agent.AgentApprovalHttpQueryService;
import cn.lunalhx.ai.trigger.http.agent.AgentRequestMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentSseResponder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
public class AgentApprovalController {

    private final AgentRequestMapper requestMapper;
    private final AgentSseResponder sseResponder;
    private final AgentLoopService agentLoopService;
    private final AgentApprovalHttpQueryService approvalHttpQueryService;

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
        return approvalHttpQueryService.approval(approvalId);
    }
}
