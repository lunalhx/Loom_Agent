package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.service.ReplayService;
import cn.lunalhx.ai.types.enums.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentHttpQueryService {

    private final ApprovalStore approvalStore;
    private final AgentRunRepository agentRunRepository;
    private final TraceRecorder traceRecorder;
    private final ReplayService replayService;
    private final AgentResponseMapper responseMapper;

    public Response<AgentApprovalResponse> approval(String approvalId) {
        return approvalStore.find(approvalId)
                .map(this::toApprovalSuccess)
                .orElseGet(this::approvalNotFound);
    }

    public Response<AgentTraceTimelineResponse> trace(String runId) {
        if (runId == null || runId.isBlank()) {
            return traceError("runId 不能为空");
        }
        AgentRun run = agentRunRepository.find(runId).orElse(null);
        if (run == null) {
            return traceError("未找到 run");
        }
        List<AgentTraceEvent> events = traceRecorder.timeline(runId);
        if (events.isEmpty()) {
            return traceError("未找到 trace");
        }
        return Response.<AgentTraceTimelineResponse>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(responseMapper.toTraceTimeline(runId, events))
                .build();
    }

    public Response<AgentReplayResponse> replay(String runId, boolean includeChildren) {
        if (runId == null || runId.isBlank()) {
            return replayError("runId 不能为空");
        }
        AgentReplayTimeline timeline = replayService.replayRun(runId, includeChildren);
        if (timeline.getEvents().isEmpty()) {
            return replayError("未找到可 replay 的 trace");
        }
        return Response.<AgentReplayResponse>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(responseMapper.toReplayResponse(timeline))
                .build();
    }

    public AgentReplayTimeline replayTimeline(String runId, boolean includeChildren) {
        return replayService.replayRun(runId, includeChildren);
    }

    private Response<AgentApprovalResponse> toApprovalSuccess(PendingApproval approval) {
        return Response.<AgentApprovalResponse>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(responseMapper.toApprovalResponse(approval))
                .build();
    }

    private Response<AgentApprovalResponse> approvalNotFound() {
        return Response.<AgentApprovalResponse>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info("审批不存在或已过期")
                .build();
    }

    private Response<AgentTraceTimelineResponse> traceError(String message) {
        return Response.<AgentTraceTimelineResponse>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(message)
                .build();
    }

    private Response<AgentReplayResponse> replayError(String message) {
        return Response.<AgentReplayResponse>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(message)
                .build();
    }
}
