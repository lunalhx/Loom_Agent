package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.AgentApprovalDecisionRequest;
import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.dto.AgentAskRequest;
import cn.lunalhx.ai.api.dto.AgentReplayEventDTO;
import cn.lunalhx.ai.api.dto.AgentReplayResponse;
import cn.lunalhx.ai.api.dto.AgentReplayStreamRequest;
import cn.lunalhx.ai.api.dto.AgentStreamEvent;
import cn.lunalhx.ai.api.dto.AgentTraceEventDTO;
import cn.lunalhx.ai.api.dto.AgentTraceTimelineResponse;
import cn.lunalhx.ai.api.dto.TokenUsageDTO;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.AgentReplayTimeline;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.ReplayService;
import cn.lunalhx.ai.types.enums.ResponseCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agent/code")
public class AgentCodeController {

    private final AgentLoopService agentLoopService;
    private final ApprovalStore approvalStore;
    private final AgentRunRepository agentRunRepository;
    private final TraceRecorder traceRecorder;
    private final ReplayService replayService;
    private final AgentRuntimeProperties agentRuntimeProperties;
    private final Validator validator;
    private final ThreadPoolExecutor threadPoolExecutor;

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody(required = false) AgentAskRequest request) {
        SseEmitter emitter = new SseEmitter(agentRuntimeProperties.getTotalTimeoutMs() + 5000L);
        AtomicBoolean completed = new AtomicBoolean(false);

        AgentEvent validationError = validateRequest(request);
        if (validationError != null) {
            threadPoolExecutor.execute(() -> sendAndComplete(emitter, completed, validationError));
            return emitter;
        }

        String requestId = UUID.randomUUID().toString();
        MDC.put("request_id", requestId);
        AgentQuestion question = toQuestion(request, requestId);
        Disposable disposable = agentLoopService.ask(question)
                .filter(event -> shouldSend(event, request))
                .subscribe(event -> withMdc(event, () -> send(emitter, event)),
                        throwable -> sendAndComplete(emitter, completed, fallbackError(throwable)),
                        () -> complete(emitter, completed));
        MDC.clear();

        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            sendAndComplete(emitter, completed, timeoutError());
        });
        emitter.onError(throwable -> {
            disposable.dispose();
            log.warn("Agent SSE connection closed with error: {}", throwable.getMessage());
        });
        return emitter;
    }

    @PostMapping(value = "/approvals/{approvalId}/decide/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter decide(@PathVariable String approvalId,
                             @RequestBody(required = false) AgentApprovalDecisionRequest request) {
        SseEmitter emitter = new SseEmitter(agentRuntimeProperties.getTotalTimeoutMs() + 5000L);
        AtomicBoolean completed = new AtomicBoolean(false);

        AgentEvent validationError = validateApprovalRequest(request);
        if (validationError != null) {
            threadPoolExecutor.execute(() -> sendAndComplete(emitter, completed, validationError));
            return emitter;
        }

        ApprovalDecision decision = parseApprovalDecision(request.getDecision());
        if (decision == null) {
            threadPoolExecutor.execute(() -> sendAndComplete(emitter, completed, error("invalid_request", "decision 只能是 APPROVE 或 REJECT")));
            return emitter;
        }

        Disposable disposable = agentLoopService.resume(approvalId, decision, request.getReason())
                .subscribe(event -> withMdc(event, () -> send(emitter, event)),
                        throwable -> sendAndComplete(emitter, completed, fallbackError(throwable)),
                        () -> complete(emitter, completed));

        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            sendAndComplete(emitter, completed, timeoutError());
        });
        emitter.onError(throwable -> {
            disposable.dispose();
            log.warn("Agent approval SSE connection closed with error: {}", throwable.getMessage());
        });
        return emitter;
    }

    @GetMapping("/approvals/{approvalId}")
    public Response<AgentApprovalResponse> approval(@PathVariable String approvalId) {
        return approvalStore.find(approvalId)
                .map(approval -> Response.<AgentApprovalResponse>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info(ResponseCode.SUCCESS.getInfo())
                        .data(toApprovalResponse(approval))
                        .build())
                .orElseGet(() -> Response.<AgentApprovalResponse>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("审批不存在或已过期")
                .build());
    }

    @PostMapping(value = "/runs/{runId}/resume/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeRun(@PathVariable String runId) {
        SseEmitter emitter = new SseEmitter(agentRuntimeProperties.getTotalTimeoutMs() + 5000L);
        AtomicBoolean completed = new AtomicBoolean(false);

        if (StringUtils.isBlank(runId)) {
            threadPoolExecutor.execute(() -> sendAndComplete(emitter, completed, error("invalid_request", "runId 不能为空")));
            return emitter;
        }

        Disposable disposable = agentLoopService.resumeRun(runId)
                .filter(event -> event.getType() != AgentEventType.CHECKPOINT_SAVED)
                .subscribe(event -> withMdc(event, () -> send(emitter, event)),
                        throwable -> sendAndComplete(emitter, completed, fallbackError(throwable)),
                        () -> complete(emitter, completed));

        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            sendAndComplete(emitter, completed, timeoutError());
        });
        emitter.onError(throwable -> {
            disposable.dispose();
            log.warn("Agent run resume SSE connection closed with error: {}", throwable.getMessage());
        });
        return emitter;
    }

    @GetMapping("/runs/{runId}/trace")
    public Response<AgentTraceTimelineResponse> trace(@PathVariable String runId) {
        if (StringUtils.isBlank(runId)) {
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
        AgentTraceEvent first = events.get(0);
        return Response.<AgentTraceTimelineResponse>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(AgentTraceTimelineResponse.builder()
                        .runId(runId)
                        .traceId(first.getTraceId())
                        .rootRunId(first.getRootRunId())
                        .events(events.stream().map(this::toTraceDto).toList())
                        .build())
                .build();
    }

    @GetMapping("/runs/{runId}/replay")
    public Response<AgentReplayResponse> replay(@PathVariable String runId,
                                                @RequestParam(required = false) Boolean includeChildren) {
        if (StringUtils.isBlank(runId)) {
            return replayError("runId 不能为空");
        }
        AgentReplayTimeline timeline = replayService.replayRun(runId, includeChildren(includeChildren, null));
        if (timeline.getEvents().isEmpty()) {
            return replayError("未找到可 replay 的 trace");
        }
        return Response.<AgentReplayResponse>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(toReplayResponse(timeline))
                .build();
    }

    @PostMapping(value = "/runs/{runId}/replay/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter replayStream(@PathVariable String runId,
                                   @RequestParam(required = false) Boolean includeChildren,
                                   @RequestBody(required = false) AgentReplayStreamRequest request) {
        SseEmitter emitter = new SseEmitter(agentRuntimeProperties.getTotalTimeoutMs() + 5000L);
        AtomicBoolean completed = new AtomicBoolean(false);
        threadPoolExecutor.execute(() -> {
            boolean resolvedIncludeChildren = includeChildren(includeChildren, request);
            if (StringUtils.isBlank(runId)) {
                sendReplayAndComplete(emitter, completed, "error", Map.of(
                        "type", "error",
                        "code", "invalid_request",
                        "message", "runId 不能为空"));
                return;
            }
            try {
                AgentReplayTimeline timeline = replayService.replayRun(runId, resolvedIncludeChildren);
                sendReplay(emitter, "replay_started", Map.of(
                        "type", "replay_started",
                        "mode", AgentReplayTimeline.MODE,
                        "runId", runId,
                        "traceId", StringUtils.defaultString(timeline.getTraceId()),
                        "rootRunId", StringUtils.defaultString(timeline.getRootRunId()),
                        "includeChildren", resolvedIncludeChildren,
                        "costGenerated", false));
                for (AgentTraceEvent event : timeline.getEvents()) {
                    sendReplay(emitter, "replay_event", toReplayDto(event));
                }
                sendReplayAndComplete(emitter, completed, "replay_done", Map.of(
                        "type", "replay_done",
                        "mode", AgentReplayTimeline.MODE,
                        "runId", runId,
                        "eventCount", timeline.getEvents().size(),
                        "costGenerated", false));
            } catch (Exception e) {
                log.warn("Replay stream failed, runId={}, message={}", runId, e.getMessage(), e);
                sendReplayAndComplete(emitter, completed, "error", Map.of(
                        "type", "error",
                        "code", "replay_failed",
                        "message", "Replay 失败"));
            }
        });
        return emitter;
    }

    private AgentEvent validateRequest(AgentAskRequest request) {
        if (request == null) {
            return error("invalid_request", "请求体不能为空");
        }
        if (!Boolean.TRUE.equals(agentRuntimeProperties.getEnabled())) {
            return error("agent_disabled", "Agent 功能未启用");
        }
        Set<ConstraintViolation<AgentAskRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .distinct()
                    .collect(Collectors.joining("; "));
            return error("invalid_request", message);
        }
        if (StringUtils.isBlank(request.getQuestion()) && StringUtils.isBlank(request.getMessage())) {
            return error("invalid_request", "question 不能为空");
        }
        return null;
    }

    private AgentEvent validateApprovalRequest(AgentApprovalDecisionRequest request) {
        if (request == null) {
            return error("invalid_request", "请求体不能为空");
        }
        if (!Boolean.TRUE.equals(agentRuntimeProperties.getEnabled())) {
            return error("agent_disabled", "Agent 功能未启用");
        }
        Set<ConstraintViolation<AgentApprovalDecisionRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .distinct()
                    .collect(Collectors.joining("; "));
            return error("invalid_request", message);
        }
        return null;
    }

    private AgentQuestion toQuestion(AgentAskRequest request, String requestId) {
        return AgentQuestion.builder()
                .requestId(requestId)
                .question(StringUtils.defaultIfBlank(StringUtils.trim(request.getQuestion()), StringUtils.trim(request.getMessage())))
                .workspace(request.getWorkspace())
                .maxSteps(request.getMaxSteps())
                .includeTrace(request.getIncludeTrace())
                .build();
    }

    private void withMdc(AgentEvent event, Runnable runnable) {
        putEventMdc(event);
        try {
            runnable.run();
        } finally {
            MDC.clear();
        }
    }

    private void putEventMdc(AgentEvent event) {
        if (event == null) {
            return;
        }
        MDC.put("run_id", StringUtils.defaultString(event.getRunId()));
        MDC.put("request_id", StringUtils.defaultString(event.getRequestId()));
        MDC.put("conversation_id", StringUtils.defaultString(event.getConversationId()));
        MDC.put("node", StringUtils.defaultString(event.getNode()));
    }

    private boolean shouldSend(AgentEvent event, AgentAskRequest request) {
        if (Boolean.TRUE.equals(request.getIncludeTrace())) {
            return true;
        }
        return event.getType() == AgentEventType.META
                || event.getType() == AgentEventType.PLAN_UPDATED
                || event.getType() == AgentEventType.REPLAN_STARTED
                || event.getType() == AgentEventType.CONTEXT_COMPACTED
                || event.getType() == AgentEventType.RESUME_STARTED
                || event.getType() == AgentEventType.SUB_AGENT_STARTED
                || event.getType() == AgentEventType.SUB_AGENT_COMPLETED
                || event.getType() == AgentEventType.SUB_AGENT_FAILED
                || event.getType() == AgentEventType.SUB_AGENT_SUMMARY
                || event.getType() == AgentEventType.APPROVAL_REQUIRED
                || event.getType() == AgentEventType.POLICY_DENIED
                || event.getType() == AgentEventType.ANSWER
                || event.getType() == AgentEventType.DONE
                || event.getType() == AgentEventType.ERROR;
    }

    private void send(SseEmitter emitter, AgentEvent event) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name(event.getType().eventName())
                        .data(toDto(event), MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send agent SSE event: {}", e.getMessage());
        }
    }

    private void sendAndComplete(SseEmitter emitter, AtomicBoolean completed, AgentEvent event) {
        send(emitter, event);
        complete(emitter, completed);
    }

    private void complete(SseEmitter emitter, AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private AgentEvent timeoutError() {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .code("agent_timeout")
                .message("Agent 执行超时")
                .build();
    }

    private AgentEvent fallbackError(Throwable throwable) {
        log.warn("Agent loop failed: {}", throwable.getMessage(), throwable);
        return error("agent_error", "Agent 执行失败");
    }

    private AgentEvent error(String code, String message) {
        return AgentEvent.builder()
                .type(AgentEventType.ERROR)
                .requestId(UUID.randomUUID().toString())
                .stopReason(AgentStopReason.MODEL_ERROR)
                .code(code)
                .message(message)
                .build();
    }

    private AgentStreamEvent toDto(AgentEvent event) {
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

    private ApprovalDecision parseApprovalDecision(String value) {
        try {
            return ApprovalDecision.valueOf(StringUtils.upperCase(StringUtils.trim(value)));
        } catch (Exception e) {
            return null;
        }
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

    private AgentReplayResponse toReplayResponse(AgentReplayTimeline timeline) {
        return AgentReplayResponse.builder()
                .mode(timeline.getMode())
                .traceId(timeline.getTraceId())
                .rootRunId(timeline.getRootRunId())
                .runId(timeline.getRunId())
                .includeChildren(timeline.getIncludeChildren())
                .events(timeline.getEvents().stream().map(this::toReplayDto).toList())
                .costGenerated(timeline.getCostGenerated())
                .build();
    }

    private AgentReplayEventDTO toReplayDto(AgentTraceEvent event) {
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
                .tokenUsage(toTokenUsageDto(event.getTokenUsage()))
                .cost(toCostMap(event.getCost()))
                .metadata(event.getMetadata())
                .replayable(event.getReplayable())
                .sensitiveRedacted(event.getSensitiveRedacted())
                .createdAt(event.getCreatedAt() == null ? null : event.getCreatedAt().toString())
                .build();
    }

    private AgentTraceEventDTO toTraceDto(AgentTraceEvent event) {
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
                .tokenUsage(toTokenUsageDto(event.getTokenUsage()))
                .cost(toCostMap(event.getCost()))
                .metadata(event.getMetadata())
                .replayable(event.getReplayable())
                .sensitiveRedacted(event.getSensitiveRedacted())
                .createdAt(event.getCreatedAt() == null ? null : event.getCreatedAt().toString())
                .build();
    }

    private TokenUsageDTO toTokenUsageDto(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        return TokenUsageDTO.builder()
                .promptTokens(usage.getPromptTokens())
                .completionTokens(usage.getCompletionTokens())
                .totalTokens(usage.getTotalTokens())
                .build();
    }

    private Map<String, Object> toCostMap(TraceCost cost) {
        if (cost == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputCost", cost.getInputCost());
        result.put("outputCost", cost.getOutputCost());
        result.put("totalCost", cost.getTotalCost());
        return result;
    }

    private boolean includeChildren(Boolean queryValue, AgentReplayStreamRequest request) {
        if (queryValue != null) {
            return queryValue;
        }
        if (request != null && request.getIncludeChildren() != null) {
            return request.getIncludeChildren();
        }
        return true;
    }

    private void sendReplay(SseEmitter emitter, String eventName, Object data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send replay SSE event: {}", e.getMessage());
        }
    }

    private void sendReplayAndComplete(SseEmitter emitter, AtomicBoolean completed, String eventName, Object data) {
        sendReplay(emitter, eventName, data);
        complete(emitter, completed);
    }

    private AgentApprovalResponse toApprovalResponse(PendingApproval approval) {
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
                .expiresAt(approval.getExpiresAt() == null ? null : approval.getExpiresAt().toString())
                .build();
    }

}
