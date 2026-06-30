package cn.lunalhx.ai.infrastructure.adapter.repository;

import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.infrastructure.dao.AgentTraceEventDao;
import cn.lunalhx.ai.infrastructure.dao.po.AgentTraceEventPO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.List;

public class MybatisTraceRecorder implements TraceRecorder {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AgentTraceEventDao dao;
    private final ObjectMapper objectMapper;

    public MybatisTraceRecorder(AgentTraceEventDao dao, ObjectMapper objectMapper) {
        this.dao = dao;
        this.objectMapper = objectMapper;
    }

    @Override
    public String recordNodeStart(AgentContext context, AgentNode node, String parentSpanId) {
        if (context == null || node == null) {
            return null;
        }
        String spanId = UUID.randomUUID().toString();
        insert(context, AgentTraceEvent.builder()
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
        insert(context, AgentTraceEvent.builder()
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
        insert(context, AgentTraceEvent.builder()
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
        insert(context, AgentTraceEvent.builder()
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
        insert(context, AgentTraceEvent.builder()
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
        insert(context, AgentTraceEvent.builder()
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
        return dao.selectByRunId(runId).stream()
                .map(this::toEntity)
                .toList();
    }

    @Override
    public List<AgentTraceEvent> timelineByTraceId(String traceId) {
        if (StringUtils.isBlank(traceId)) {
            return List.of();
        }
        return dao.selectByTraceId(traceId).stream()
                .map(this::toEntity)
                .toList();
    }

    private synchronized void insert(AgentContext context, AgentTraceEvent event) {
        String runId = context.getRunId();
        if (StringUtils.isBlank(runId)) {
            return;
        }
        event.setTraceId(StringUtils.defaultIfBlank(context.getTraceId(), StringUtils.defaultIfBlank(context.getRootRunId(), runId)));
        event.setRootRunId(StringUtils.defaultIfBlank(context.getRootRunId(), runId));
        event.setRunId(runId);
        event.setParentRunId(context.getParentRunId());
        event.setCreatedAt(Instant.now());
        long sequenceNo = dao.insertNext(toPo(event));
        event.setSequenceNo(sequenceNo);
        context.setTraceSequenceNo(sequenceNo);
    }

    private AgentTraceEventPO toPo(AgentTraceEvent event) {
        AgentTraceEventPO po = new AgentTraceEventPO();
        po.setTraceId(event.getTraceId());
        po.setRootRunId(event.getRootRunId());
        po.setRunId(event.getRunId());
        po.setParentRunId(event.getParentRunId());
        po.setSpanId(event.getSpanId());
        po.setParentSpanId(event.getParentSpanId());
        po.setSequenceNo(event.getSequenceNo());
        po.setEventType(event.getEventType());
        po.setNode(event.getNode());
        po.setStatus(event.getStatus());
        po.setDurationMs(event.getDurationMs());
        po.setSummary(event.getSummary());
        po.setErrorCode(event.getErrorCode());
        po.setErrorMessage(event.getErrorMessage());
        po.setTokenUsageJson(toJson(event.getTokenUsage()));
        po.setCostJson(toJson(event.getCost()));
        po.setMetadataJson(toJson(event.getMetadata()));
        po.setReplayable(event.getReplayable());
        po.setSensitiveRedacted(event.getSensitiveRedacted());
        return po;
    }

    private AgentTraceEvent toEntity(AgentTraceEventPO po) {
        return AgentTraceEvent.builder()
                .id(po.getId())
                .traceId(po.getTraceId())
                .rootRunId(po.getRootRunId())
                .runId(po.getRunId())
                .parentRunId(po.getParentRunId())
                .spanId(po.getSpanId())
                .parentSpanId(po.getParentSpanId())
                .sequenceNo(po.getSequenceNo())
                .eventType(po.getEventType())
                .node(po.getNode())
                .status(po.getStatus())
                .durationMs(po.getDurationMs())
                .summary(po.getSummary())
                .errorCode(po.getErrorCode())
                .errorMessage(po.getErrorMessage())
                .tokenUsage(fromJson(po.getTokenUsageJson(), TokenUsage.class))
                .cost(fromJson(po.getCostJson(), TraceCost.class))
                .metadata(fromMapJson(po.getMetadataJson()))
                .replayable(po.getReplayable())
                .sensitiveRedacted(po.getSensitiveRedacted())
                .createdAt(po.getCreateTime())
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (StringUtils.isBlank(json) || "{}".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> fromMapJson(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String errorCode(Throwable error) {
        if (error instanceof cn.lunalhx.ai.domain.model.valobj.ModelGatewayException exception
                && exception.getErrorCode() != null) {
            return exception.getErrorCode().code();
        }
        return error == null ? null : error.getClass().getSimpleName();
    }

}
