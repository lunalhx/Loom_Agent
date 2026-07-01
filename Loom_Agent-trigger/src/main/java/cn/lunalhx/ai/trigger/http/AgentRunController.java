package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentReplayStreamRequest;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.dto.UndoExecuteRequest;
import cn.lunalhx.ai.api.dto.UndoExecuteResponse;
import cn.lunalhx.ai.api.dto.UndoStatusResponse;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.trigger.http.agent.AgentRequestMapper;
import cn.lunalhx.ai.trigger.http.agent.AgentRunHttpQueryService;
import cn.lunalhx.ai.trigger.http.agent.AgentSseResponder;
import cn.lunalhx.ai.trigger.http.agent.AgentUndoHttpService;
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

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/code")
public class AgentRunController {

    private final AgentRequestMapper requestMapper;
    private final AgentSseResponder sseResponder;
    private final AgentRunHttpQueryService runHttpQueryService;
    private final AgentUndoHttpService undoHttpService;

    @GetMapping("/runs/{runId}/trace")
    public Response<AgentTraceTimelineResponse> trace(@PathVariable String runId) {
        return runHttpQueryService.trace(runId);
    }

    @GetMapping("/runs/{runId}/replay")
    public Response<AgentReplayResponse> replay(@PathVariable String runId,
                                                @RequestParam(required = false) Boolean includeChildren) {
        return runHttpQueryService.replay(runId, requestMapper.resolveIncludeChildren(includeChildren, null));
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
                () -> runHttpQueryService.replayTimeline(runId, resolvedIncludeChildren)
        );
    }

    @GetMapping("/runs/{runId}/undo")
    public Response<UndoStatusResponse> undoStatus(@PathVariable String runId) {
        return undoHttpService.query(runId);
    }

    @PostMapping("/runs/{runId}/undo")
    public Response<UndoExecuteResponse> undoExecute(@PathVariable String runId,
                                                       @RequestBody UndoExecuteRequest request) {
        return undoHttpService.execute(runId, request);
    }
}
