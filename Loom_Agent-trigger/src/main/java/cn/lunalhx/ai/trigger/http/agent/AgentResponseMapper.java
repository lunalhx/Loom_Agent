package cn.lunalhx.ai.trigger.http.agent;

import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.dto.AgentReplayEventDTO;
import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentStreamEvent;
import cn.lunalhx.ai.api.dto.AgentTraceEventDTO;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.dto.DiffHunkPayload;
import cn.lunalhx.ai.api.dto.DiffLinePayload;
import cn.lunalhx.ai.api.dto.DiffPayload;
import cn.lunalhx.ai.api.dto.DiffStatsPayload;
import cn.lunalhx.ai.api.dto.InlineDiffPartPayload;
import cn.lunalhx.ai.api.dto.TokenUsageDTO;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.domain.tool.model.ApprovalDiff;
import cn.lunalhx.ai.domain.tool.model.DiffHunk;
import cn.lunalhx.ai.domain.tool.model.DiffLine;
import cn.lunalhx.ai.domain.tool.model.DiffStats;
import cn.lunalhx.ai.domain.tool.model.InlineDiffPart;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentResponseMapper {

    public AgentStreamEvent toStreamEvent(AgentEvent event) {
        return AgentStreamEvent.builder()
                .type(event.getType().eventName())
                .runId(event.getRunId())
                .requestId(event.getRequestId())
                .conversationId(event.getConversationId())
                .workspace(event.getWorkspace())
                .parentRunId(event.getParentRunId())
                .subAgentRunId(event.getSubAgentRunId())
                .subAgentTaskId(event.getSubAgentTaskId())
                .subAgentRole(event.getSubAgentRole())
                .subAgentStatus(event.getSubAgentStatus())
                .elapsedMs(event.getElapsedMs())
                .step(event.getStep())
                .node(event.getNode())
                .nodeInputs(event.getNodeInputs())
                .thought(event.getThought())
                .tool(event.getTool())
                .input(event.getInput())
                .approvalId(event.getApprovalId())
                .permissionLevel(event.getPermissionLevel())
                .riskReason(event.getRiskReason())
                .operationPreview(event.getOperationPreview())
                .diff(toDiffPayload(event.getDiff()))
                .expiresAt(event.getExpiresAt() == null ? null : event.getExpiresAt().toString())
                .observation(event.getObservation())
                .truncated(event.getTruncated())
                .answer(event.getAnswer())
                .stopReason(event.getStopReason() == null ? null : event.getStopReason().name())
                .stepCount(event.getStepCount())
                .code(event.getCode())
                .message(event.getMessage())
                .plan(event.getPlan())
                .checkpointVersion(event.getCheckpointVersion())
                .metadata(event.getMetadata())
                .build();
    }

    public AgentApprovalResponse toApprovalResponse(PendingApproval approval) {
        return AgentApprovalResponse.builder()
                .approvalId(approval.getApprovalId())
                .runId(approval.getRunId())
                .status("PENDING")
                .requestId(approval.getRequestId())
                .conversationId(approval.getConversationId())
                .workspace(approval.getWorkspaceDisplayName())
                .tool(approval.getTool())
                .input(approval.getInput())
                .permissionLevel(approval.getPermissionLevel() == null ? null : approval.getPermissionLevel().name())
                .riskReason(approval.getRiskReason())
                .operationPreview(approval.getOperationPreview())
                .diff(toDiffPayload(approval.getDiff()))
                .expiresAt(approval.getExpiresAt() == null ? null : approval.getExpiresAt().toString())
                .build();
    }

    public AgentTraceTimelineResponse toTraceTimeline(String runId, List<AgentTraceEvent> events) {
        AgentTraceEvent first = events.get(0);
        return AgentTraceTimelineResponse.builder()
                .runId(runId)
                .traceId(first.getTraceId())
                .rootRunId(first.getRootRunId())
                .events(events.stream().map(this::toTraceEvent).toList())
                .build();
    }

    public AgentReplayResponse toReplayResponse(AgentReplayTimeline timeline) {
        return AgentReplayResponse.builder()
                .mode(timeline.getMode())
                .traceId(timeline.getTraceId())
                .rootRunId(timeline.getRootRunId())
                .runId(timeline.getRunId())
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

    private DiffPayload toDiffPayload(ApprovalDiff diff) {
        if (diff == null) {
            return null;
        }
        return DiffPayload.builder()
                .format(diff.getFormat())
                .path(diff.getPath())
                .oldText(diff.getOldText())
                .newText(diff.getNewText())
                .unifiedDiff(diff.getUnifiedDiff())
                .editable(diff.getEditable())
                .hunks(toDiffHunks(diff.getHunks()))
                .stats(toDiffStats(diff.getStats()))
                .build();
    }

    private List<DiffHunkPayload> toDiffHunks(List<DiffHunk> hunks) {
        if (hunks == null) {
            return null;
        }
        return hunks.stream()
                .map(hunk -> DiffHunkPayload.builder()
                        .oldStart(hunk.getOldStart())
                        .oldLines(hunk.getOldLines())
                        .newStart(hunk.getNewStart())
                        .newLines(hunk.getNewLines())
                        .lines(toDiffLines(hunk.getLines()))
                        .build())
                .toList();
    }

    private List<DiffLinePayload> toDiffLines(List<DiffLine> lines) {
        if (lines == null) {
            return null;
        }
        return lines.stream()
                .map(line -> DiffLinePayload.builder()
                        .type(line.getType())
                        .oldLineNumber(line.getOldLineNumber())
                        .newLineNumber(line.getNewLineNumber())
                        .text(line.getText())
                        .pairId(line.getPairId())
                        .foldedCount(line.getFoldedCount())
                        .inlineDiff(toInlineDiff(line.getInlineDiff()))
                        .build())
                .toList();
    }

    private List<InlineDiffPartPayload> toInlineDiff(List<InlineDiffPart> parts) {
        if (parts == null) {
            return null;
        }
        return parts.stream()
                .map(part -> InlineDiffPartPayload.builder()
                        .type(part.getType())
                        .text(part.getText())
                        .build())
                .toList();
    }

    private DiffStatsPayload toDiffStats(DiffStats stats) {
        if (stats == null) {
            return null;
        }
        return DiffStatsPayload.builder()
                .added(stats.getAdded())
                .removed(stats.getRemoved())
                .modified(stats.getModified())
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
