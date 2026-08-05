package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.dto.AgentUsageSummaryDTO;
import cn.lunalhx.ai.api.dto.AgentRunStatusResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentErrorCode;
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
    private final AgentUsageSummaryService usageSummaryService;

    public Response<AgentRunStatusResponse> status(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new ApplicationException(CommonErrorCode.INVALID_PARAMETER, "runId 不能为空");
        }
        AgentRun run = agentRunRepository.find(runId)
                .orElseThrow(() -> new ApplicationException(
                        AgentErrorCode.RUN_NOT_FOUND));
        AgentRunStatus status = run.getStatus();
        boolean terminal = status != null && status.terminal();
        boolean resumable = status != null && status.resumable();
        return Response.success(AgentRunStatusResponse.builder()
                .runId(run.getRunId())
                .status(status == null ? null : status.name())
                .currentNode(run.getCurrentNode())
                .toolSteps(run.getToolSteps())
                .modelAttempts(run.getModelAttempts())
                .lastTool(run.getLastTool())
                .stopReason(run.getStopReason())
                .finalAnswer(run.getFinalAnswer())
                .checkpointVersion(run.getCheckpointVersion())
                .terminal(terminal)
                .resumable(resumable)
                .updatedAt(run.getUpdatedAt())
                .build());
    }

    public Response<AgentTraceTimelineResponse> trace(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new ApplicationException(CommonErrorCode.INVALID_PARAMETER, "runId 不能为空");
        }
        AgentRun run = agentRunRepository.find(runId).orElse(null);
        if (run == null) {
            throw new ApplicationException(AgentErrorCode.RUN_NOT_FOUND);
        }
        List<AgentTraceEvent> events = traceRecorder.timeline(runId);
        if (events.isEmpty()) {
            return Response.success(AgentTraceTimelineResponse.builder()
                    .runId(runId)
                    .status(run.getStatus() == null ? null : run.getStatus().name())
                    .events(List.of())
                    .build());
        }
        return Response.success(responseMapper.toTraceTimeline(
                runId,
                run.getStatus() == null ? null : run.getStatus().name(),
                events));
    }

    public Response<AgentReplayResponse> replay(String runId, boolean includeChildren) {
        if (runId == null || runId.isBlank()) {
            throw new ApplicationException(CommonErrorCode.INVALID_PARAMETER, "runId 不能为空");
        }
        AgentRun run = agentRunRepository.find(runId)
                .orElseThrow(() -> new ApplicationException(
                        AgentErrorCode.RUN_NOT_FOUND));
        AgentReplayTimeline timeline = replayService.replayRun(runId, includeChildren);
        return Response.success(responseMapper.toReplayResponse(
                timeline,
                run.getStatus() == null ? null : run.getStatus().name()));
    }

    public AgentReplayTimeline replayTimeline(String runId, boolean includeChildren) {
        return replayService.replayRun(runId, includeChildren);
    }

    public Response<AgentUsageSummaryDTO> usage(String runId) {
        return Response.success(usageSummaryService.summarize(runId));
    }
}
