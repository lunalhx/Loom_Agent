package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

/**
 * Append-only trace store under {@code .loom-code/runs/<runId>/trace.jsonl},
 * mirroring the loom-code {@code RunStore} trace contract. The CLI consumes
 * the same trace events; no second trace state machine exists.
 */
public final class FileTraceRecorder implements TraceRecorder {

    private final Path root;
    private final ObjectMapper mapper;
    private final ArtifactRedactor artifactRedactor;

    public FileTraceRecorder(Path workspaceRoot, ObjectMapper mapper) {
        this(workspaceRoot, mapper, new ArtifactRedactor());
    }

    public FileTraceRecorder(Path workspaceRoot, ObjectMapper mapper,
                             ArtifactRedactor artifactRedactor) {
        this.root = workspaceRoot.resolve(".loom-code").resolve("runs");
        this.mapper = mapper;
        this.artifactRedactor = artifactRedactor;
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
    public void recordNodeEnd(AgentContext context, AgentNode node, String spanId,
                              String status, long durationMs, String summary, Throwable error) {
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
    public void recordModelUsage(AgentContext context, String node, TokenUsage usage,
                                 TraceCost cost, Map<String, Object> metadata) {
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
    public void recordModelGatewayEvent(AgentContext context, String eventType, String node,
                                        String status, long durationMs, String summary,
                                        Throwable error, Map<String, Object> metadata) {
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
    public void recordSecurityEvent(AgentContext context, String eventType, String node,
                                    String status, Map<String, Object> metadata) {
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
        Path trace = root.resolve(runId).resolve("trace.jsonl");
        if (!Files.isRegularFile(trace)) {
            return List.of();
        }
        List<AgentTraceEvent> events = readAll(runId);
        events.sort(Comparator.comparing(AgentTraceEvent::getSequenceNo,
                        Comparator.nullsLast(Long::compareTo))
                .thenComparing(AgentTraceEvent::getId, Comparator.nullsLast(Long::compareTo)));
        return events;
    }

    @Override
    public List<AgentTraceEvent> timelineByTraceId(String traceId) {
        if (StringUtils.isBlank(traceId)) {
            return List.of();
        }
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<AgentTraceEvent> result = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                result.addAll(readAll(dir.getFileName().toString()));
            }
        } catch (IOException ignored) {
        }
        return result.stream()
                .filter(event -> traceId.equals(event.getTraceId()))
                .sorted(Comparator.comparing(AgentTraceEvent::getCreatedAt,
                                Comparator.nullsLast(Instant::compareTo))
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
        event.setTraceId(StringUtils.defaultIfBlank(context.getTraceId(),
                StringUtils.defaultIfBlank(context.getRootRunId(), runId)));
        event.setRootRunId(StringUtils.defaultIfBlank(context.getRootRunId(), runId));
        event.setRunId(runId);
        event.setParentRunId(context.getParentRunId());
        event.setCreatedAt(Instant.now());
        try {
            Path path = root.resolve(runId).resolve("trace.jsonl");
            Files.createDirectories(path.getParent());
            com.fasterxml.jackson.databind.JsonNode tree = mapper.valueToTree(event);
            boolean changed = artifactRedactor.redactTree(tree);
            // stamp truthful flags: only mark sensitiveRedacted when a secret
            // was actually replaced, and always record the applied version.
            tree = ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
                    .put("sensitiveRedacted", changed)
                    .put("redactionVersion", artifactRedactor.redactionVersion());
            Files.writeString(path, mapper.writeValueAsString(tree) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot append trace for " + runId + ": " + e.getMessage(), e);
        }
    }

    private List<AgentTraceEvent> readAll(String runId) {
        Path trace = root.resolve(runId).resolve("trace.jsonl");
        List<AgentTraceEvent> events = new ArrayList<>();
        if (!Files.isRegularFile(trace)) {
            return events;
        }
        try {
            for (String line : Files.readAllLines(trace)) {
                if (!line.isBlank()) {
                    events.add(mapper.readValue(line, AgentTraceEvent.class));
                }
            }
        } catch (IOException ignored) {
        }
        return events;
    }

    private String errorCode(Throwable error) {
        if (error instanceof cn.lunalhx.ai.domain.model.valobj.ModelGatewayException exception
                && exception.getErrorCode() != null) {
            return exception.getErrorCode().code();
        }
        return error == null ? null : error.getClass().getSimpleName();
    }
}
