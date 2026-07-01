package cn.lunalhx.ai.domain.agent.service.replay;

import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public class DefaultReplayService implements ReplayService {

    private final TraceRecorder traceRecorder;

    public DefaultReplayService(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @Override
    public AgentReplayTimeline replayRun(String runId, boolean includeChildren) {
        if (StringUtils.isBlank(runId)) {
            return empty(runId, includeChildren);
        }
        List<AgentTraceEvent> runEvents = traceRecorder.timeline(runId);
        if (runEvents.isEmpty()) {
            return empty(runId, includeChildren);
        }
        AgentTraceEvent first = runEvents.get(0);
        List<AgentTraceEvent> events = includeChildren && StringUtils.isNotBlank(first.getTraceId())
                ? traceRecorder.timelineByTraceId(first.getTraceId())
                : runEvents;
        return AgentReplayTimeline.builder()
                .mode(AgentReplayTimeline.MODE)
                .traceId(first.getTraceId())
                .rootRunId(first.getRootRunId())
                .runId(runId)
                .includeChildren(includeChildren)
                .events(events)
                .costGenerated(false)
                .build();
    }

    @Override
    public Flux<Object> streamReplayRun(String runId, boolean includeChildren) {
        return Flux.defer(() -> {
            AgentReplayTimeline timeline = replayRun(runId, includeChildren);
            return Flux.concat(
                    Flux.just(Map.of(
                            "type", "replay_started",
                            "mode", AgentReplayTimeline.MODE,
                            "runId", StringUtils.defaultString(runId),
                            "traceId", StringUtils.defaultString(timeline.getTraceId()),
                            "rootRunId", StringUtils.defaultString(timeline.getRootRunId()),
                            "includeChildren", includeChildren,
                            "costGenerated", false)),
                    Flux.fromIterable(timeline.getEvents()),
                    Flux.just(Map.of(
                            "type", "replay_done",
                            "mode", AgentReplayTimeline.MODE,
                            "runId", StringUtils.defaultString(runId),
                            "eventCount", timeline.getEvents().size(),
                            "costGenerated", false)));
        });
    }

    private AgentReplayTimeline empty(String runId, boolean includeChildren) {
        return AgentReplayTimeline.builder()
                .mode(AgentReplayTimeline.MODE)
                .runId(runId)
                .includeChildren(includeChildren)
                .events(List.of())
                .costGenerated(false)
                .build();
    }

}
