package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.dto.AgentReplayEventDTO;
import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentStreamEvent;
import cn.lunalhx.ai.api.dto.AgentTraceEventDTO;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.dto.TokenUsageDTO;
import cn.lunalhx.ai.api.dto.AgentUsageSummaryDTO;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentResponseMapper {

    public AgentStreamEvent toStreamEvent(AgentEvent event) {
        return toStreamEvent(event, null);
    }

    public AgentStreamEvent toStreamEvent(AgentEvent event, AgentUsageSummaryDTO usage) {
        return AgentStreamEvent.builder()
                .type(event.getType().eventName())
                .runId(event.getRunId())
                .requestId(event.getRequestId())
                .conversationId(event.getConversationId())
                .workspace(event.getWorkspace())
                .parentRunId(event.getParentRunId())
                .elapsedMs(event.getElapsedMs())
                .toolSteps(event.getToolSteps())
                .modelAttempts(event.getModelAttempts())
                .node(event.getNode())
                .nodeInputs(event.getNodeInputs())
                .thought(event.getThought())
                .tool(event.getTool())
                .toolCallId(event.getToolCallId())
                .input(event.getInput())
                .approvalId(event.getApprovalId())
                .riskReason(event.getRiskReason())
                .operationPreview(event.getOperationPreview())
                .expiresAt(event.getExpiresAt() == null ? null : event.getExpiresAt().toString())
                .observation(event.getObservation())
                .truncated(event.getTruncated())
                .answer(event.getAnswer())
                .stopReason(event.getStopReason() == null ? null : event.getStopReason().name())
                .lastTool(event.getLastTool())
                .maxToolSteps(event.getMaxToolSteps())
                .maxAttempts(event.getMaxAttempts())
                .code(event.getCode())
                .message(event.getMessage())
                .checkpointVersion(event.getCheckpointVersion())
                .recoverable(event.getRecoverable())
                .metadata(event.getMetadata())
                .usage(usage)
                .build();
    }

    public AgentApprovalResponse toApprovalResponse(PendingApproval approval) {
        return AgentApprovalResponse.builder()
                .approvalId(approval.getApprovalId())
                .runId(approval.getRunId())
                .status(approval.getState() == null
                        ? "PENDING" : approval.getState().name())
                .requestId(approval.getRequestId())
                .conversationId(approval.getConversationId())
                .workspace(approval.getWorkspaceDisplayName())
                .tool(approval.getTool())
                .input(approval.getInput())
                .riskReason(approval.getRiskReason())
                .operationPreview(approval.getOperationPreview())
                .metadata(approval.getMetadata())
                .expiresAt(approval.getExpiresAt() == null ? null : approval.getExpiresAt().toString())
                .build();
    }

    public AgentTraceTimelineResponse toTraceTimeline(String runId, List<AgentTraceEvent> events) {
        return toTraceTimeline(runId, null, events);
    }

    public AgentTraceTimelineResponse toTraceTimeline(
            String runId, String status, List<AgentTraceEvent> events) {
        AgentTraceEvent first = events.get(0);
        return AgentTraceTimelineResponse.builder()
                .runId(runId)
                .status(status)
                .traceId(first.getTraceId())
                .rootRunId(first.getRootRunId())
                .events(events.stream().map(this::toTraceEvent).toList())
                .build();
    }

    public AgentReplayResponse toReplayResponse(AgentReplayTimeline timeline) {
        return toReplayResponse(timeline, null);
    }

    public AgentReplayResponse toReplayResponse(
            AgentReplayTimeline timeline, String status) {
        return AgentReplayResponse.builder()
                .mode(timeline.getMode())
                .traceId(timeline.getTraceId())
                .rootRunId(timeline.getRootRunId())
                .runId(timeline.getRunId())
                .status(status)
                .includeChildren(timeline.getIncludeChildren())
                .events(timeline.getEvents().stream().map(this::toReplayEvent).toList())
                .costGenerated(timeline.getCostGenerated())
                .build();
    }

    public AgentTraceEventDTO toTraceEvent(AgentTraceEvent event) {
        return AgentTraceEventDTO.builder()
                .id(event.getId())
                .traceId(event.getTraceId())
                .rootRunId(event.getRootRunId())
                .runId(event.getRunId())
                .parentRunId(event.getParentRunId())
                .spanId(event.getSpanId())
                .parentSpanId(event.getParentSpanId())
                .sequenceNo(event.getSequenceNo())
                .eventType(event.getEventType())
                .node(event.getNode())
                .status(event.getStatus())
                .durationMs(event.getDurationMs())
                .summary(event.getSummary())
                .errorCode(event.getErrorCode())
                .errorMessage(event.getErrorMessage())
                .tokenUsage(toTokenUsage(event.getTokenUsage()))
                .cost(toCostMap(event.getCost()))
                .metadata(event.getMetadata())
                .replayable(event.getReplayable())
                .sensitiveRedacted(event.getSensitiveRedacted())
                .createdAt(event.getCreatedAt() == null ? null : event.getCreatedAt().toString())
                .build();
    }

    public AgentReplayEventDTO toReplayEvent(AgentTraceEvent event) {
        return AgentReplayEventDTO.builder()
                .eventId(event.getId())
                .sequenceNo(event.getSequenceNo())
                .eventType(event.getEventType())
                .runId(event.getRunId())
                .parentRunId(event.getParentRunId())
                .spanId(event.getSpanId())
                .parentSpanId(event.getParentSpanId())
                .nodeName(event.getNode())
                .status(event.getStatus())
                .summary(event.getSummary())
                .durationMs(event.getDurationMs())
                .errorCode(event.getErrorCode())
                .errorMessage(event.getErrorMessage())
                .tokenUsage(toTokenUsage(event.getTokenUsage()))
                .cost(toCostMap(event.getCost()))
                .metadata(event.getMetadata())
                .replayable(event.getReplayable())
                .sensitiveRedacted(event.getSensitiveRedacted())
                .createdAt(event.getCreatedAt() == null ? null : event.getCreatedAt().toString())
                .build();
    }

    public TokenUsageDTO toTokenUsage(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        return TokenUsageDTO.builder()
                .promptTokens(usage.getPromptTokens())
                .completionTokens(usage.getCompletionTokens())
                .totalTokens(usage.getTotalTokens())
                .promptCacheHitTokens(usage.getPromptCacheHitTokens())
                .promptCacheMissTokens(usage.getPromptCacheMissTokens())
                .build();
    }

    public Map<String, Object> toCostMap(TraceCost cost) {
        if (cost == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputCost", cost.getInputCost());
        result.put("outputCost", cost.getOutputCost());
        result.put("totalCost", cost.getTotalCost());
        return result;
    }

    public Map<String, Object> replayStarted(String runId, boolean includeChildren, AgentReplayTimeline timeline) {
        return Map.of(
                "type", "replay_started",
                "mode", AgentReplayTimeline.MODE,
                "runId", runId,
                "traceId", timeline.getTraceId() == null ? "" : timeline.getTraceId(),
                "rootRunId", timeline.getRootRunId() == null ? "" : timeline.getRootRunId(),
                "includeChildren", includeChildren,
                "costGenerated", false
        );
    }

    public Map<String, Object> replayDone(String runId, int eventCount) {
        return Map.of(
                "type", "replay_done",
                "mode", AgentReplayTimeline.MODE,
                "runId", runId,
                "eventCount", eventCount,
                "costGenerated", false
        );
    }

    public Map<String, Object> replayError(String code, String message) {
        return Map.of(
                "type", "error",
                "code", code,
                "message", message
        );
    }
}
