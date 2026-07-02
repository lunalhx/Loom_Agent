package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.service.replay.ReplayService;
import cn.lunalhx.ai.domain.common.CommonErrorCode;
import cn.lunalhx.ai.types.error.ApplicationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunHttpQueryService {

    private final AgentRunRepository agentRunRepository;
    private final TraceRecorder traceRecorder;
    private final ReplayService replayService;
    private final AgentResponseMapper responseMapper;

    public Response<AgentTraceTimelineResponse> trace(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new ApplicationException(CommonErrorCode.INVALID_PARAMETER, "runId 不能为空");
        }
        AgentRun run = agentRunRepository.find(runId).orElse(null);
        if (run == null) {
            throw new ApplicationException(CommonErrorCode.INVALID_REQUEST, "未找到 run");
        }
        List<AgentTraceEvent> events = traceRecorder.timeline(runId);
        if (events.isEmpty()) {
            throw new ApplicationException(CommonErrorCode.INVALID_REQUEST, "未找到 trace");
        }
        return Response.success(responseMapper.toTraceTimeline(runId, events));
    }

    public Response<AgentReplayResponse> replay(String runId, boolean includeChildren) {
        if (runId == null || runId.isBlank()) {
            throw new ApplicationException(CommonErrorCode.INVALID_PARAMETER, "runId 不能为空");
        }
        AgentReplayTimeline timeline = replayService.replayRun(runId, includeChildren);
        if (timeline.getEvents().isEmpty()) {
            throw new ApplicationException(CommonErrorCode.INVALID_REQUEST, "未找到可 replay 的 trace");
        }
        return Response.success(responseMapper.toReplayResponse(timeline));
    }

    public AgentReplayTimeline replayTimeline(String runId, boolean includeChildren) {
        return replayService.replayRun(runId, includeChildren);
    }
}
