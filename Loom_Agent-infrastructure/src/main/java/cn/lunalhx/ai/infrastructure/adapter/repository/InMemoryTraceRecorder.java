package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryStoreProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import com.google.common.cache.CacheBuilder;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class InMemoryTraceRecorder implements TraceRecorder {

    private final ConcurrentMap<String, CopyOnWriteArrayList<AgentTraceEvent>> events;
    private final int maxEventsPerRun;

    public InMemoryTraceRecorder() {
        this.events = new ConcurrentHashMap<>();
        this.maxEventsPerRun = Integer.MAX_VALUE;
    }

    public InMemoryTraceRecorder(MemoryStoreProperties props) {
        this.events = CacheBuilder.newBuilder()
                .maximumSize(props.getMaxTraceRuns())
                .expireAfterAccess(props.getTtlSeconds(), TimeUnit.SECONDS)
                .<String, CopyOnWriteArrayList<AgentTraceEvent>>build()
                .asMap();
        this.maxEventsPerRun = props.getMaxTraceEventsPerRun();
    }

    @Override
    public String recordNodeStart(AgentContext context, AgentNode node, String parentSpanId) {
        if (context == null || node == null) {
            return null;
        }
        String spanId = UUID.randomUUID().toString();
        append(context, AgentTraceEvent.builder()
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .eventType("node_start")
                .node(node.name())
                .status("started")
                .summary("node=" + node.name())
                .replayable(true)
                .sensitiveRedacted(false)
                .build());
        return spanId;
    }

    @Override
    public void recordNodeEnd(AgentContext context,
                              AgentNode node,
                              String spanId,
                              String status,
                              long durationMs,
                              String summary,
                              Throwable error) {
        if (context == null || node == null) {
            return;
        }
        append(context, AgentTraceEvent.builder()
                .spanId(spanId)
                .parentSpanId(context.getParentSpanId())
                .eventType("node_end")
                .node(node.name())
                .status(StringUtils.defaultIfBlank(status, "success"))
                .durationMs(durationMs)
                .summary(summary)
                .errorCode(context.getErrorCode())
                .errorMessage(error == null ? context.getErrorMessage() : StringUtils.abbreviate(error.getMessage(), 1000))
                .replayable(true)
                .sensitiveRedacted(false)
                .build());
    }

    @Override
    public void recordStop(AgentContext context, String status, String summary) {
        if (context == null) {
            return;
        }
        append(context, AgentTraceEvent.builder()
                .spanId(context.getCurrentSpanId())
                .parentSpanId(context.getParentSpanId())
                .eventType("stop")
                .node(context.getCurrentNode())
                .status(StringUtils.defaultIfBlank(status, "completed"))
                .summary(summary)
                .errorCode(context.getErrorCode())
                .errorMessage(context.getErrorMessage())
                .replayable(true)
                .sensitiveRedacted(false)
                .build());
    }

    @Override
    public void recordModelUsage(AgentContext context,
                                 String node,
                                 TokenUsage usage,
                                 TraceCost cost,
                                 Map<String, Object> metadata) {
        if (context == null) {
            return;
        }
        append(context, AgentTraceEvent.builder()
                .spanId(context.getCurrentSpanId())
                .parentSpanId(context.getParentSpanId())
                .eventType("model_usage")
                .node(node)
                .status("success")
                .summary(usage == null ? "model usage missing" : "model usage recorded")
                .tokenUsage(usage)
                .cost(cost)
                .metadata(metadata)
                .replayable(true)
                .sensitiveRedacted(false)
                .build());
    }

    @Override
    public void recordModelGatewayEvent(AgentContext context,
                                        String eventType,
                                        String node,
                                        String status,
                                        long durationMs,
                                        String summary,
                                        Throwable error,
                                        Map<String, Object> metadata) {
        if (context == null) {
            return;
        }
        append(context, AgentTraceEvent.builder()
                .spanId(context.getCurrentSpanId())
                .parentSpanId(context.getParentSpanId())
                .eventType(eventType)
                .node(node)
                .status(status)
                .durationMs(durationMs)
                .summary(summary)
                .errorCode(errorCode(error))
                .errorMessage(error == null ? null : StringUtils.abbreviate(error.getMessage(), 1000))
                .metadata(metadata)
                .replayable(true)
                .sensitiveRedacted(false)
                .build());
    }

    @Override
    public void recordSecurityEvent(AgentContext context,
                                    String eventType,
                                    String node,
                                    String status,
                                    Map<String, Object> metadata) {
        if (context == null) {
            return;
        }
        append(context, AgentTraceEvent.builder()
                .spanId(context.getCurrentSpanId())
                .parentSpanId(context.getParentSpanId())
                .eventType(eventType)
                .node(node)
                .status(StringUtils.defaultIfBlank(status, "warning"))
                .metadata(metadata)
                .replayable(false)
                .sensitiveRedacted(true)
                .build());
    }

    @Override
    public List<AgentTraceEvent> timeline(String runId) {
        if (StringUtils.isBlank(runId)) {
            return List.of();
        }
        List<AgentTraceEvent> runEvents = events.get(runId);
        if (runEvents == null) {
            return List.of();
        }
        return runEvents.stream()
                .sorted(Comparator.comparing(AgentTraceEvent::getSequenceNo, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(AgentTraceEvent::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    @Override
    public List<AgentTraceEvent> timelineByTraceId(String traceId) {
        if (StringUtils.isBlank(traceId)) {
            return List.of();
        }
        return events.values().stream()
                .flatMap(List::stream)
                .filter(event -> traceId.equals(event.getTraceId()))
                .sorted(Comparator.comparing(AgentTraceEvent::getCreatedAt, Comparator.nullsLast(Instant::compareTo))
                        .thenComparing(AgentTraceEvent::getRunId, Comparator.nullsLast(String::compareTo))
                        .thenComparing(AgentTraceEvent::getSequenceNo, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(AgentTraceEvent::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private void append(AgentContext context, AgentTraceEvent event) {
        String runId = context.getRunId();
        if (StringUtils.isBlank(runId)) {
            return;
        }
        event.setId(context.nextTraceSequenceNo());
        event.setSequenceNo(event.getId());
        event.setTraceId(StringUtils.defaultIfBlank(context.getTraceId(), StringUtils.defaultIfBlank(context.getRootRunId(), runId)));
        event.setRootRunId(StringUtils.defaultIfBlank(context.getRootRunId(), runId));
        event.setRunId(runId);
        event.setParentRunId(context.getParentRunId());
        event.setCreatedAt(Instant.now());
        CopyOnWriteArrayList<AgentTraceEvent> list = events.computeIfAbsent(runId,
                k -> new CopyOnWriteArrayList<>());
        while (list.size() >= maxEventsPerRun) {
            list.remove(0);
        }
        list.add(event);
    }

    private String errorCode(Throwable error) {
        if (error instanceof cn.lunalhx.ai.domain.model.valobj.ModelGatewayException exception
                && exception.getErrorCode() != null) {
            return exception.getErrorCode().code();
        }
        return error == null ? null : error.getClass().getSimpleName();
    }

}
