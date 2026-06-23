package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.AgentApprovalDecisionRequest;
import cn.lunalhx.ai.api.dto.AgentApprovalResponse;
import cn.lunalhx.ai.api.dto.AgentAskRequest;
import cn.lunalhx.ai.api.dto.AgentStreamEvent;
import cn.lunalhx.ai.api.response.Response;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.PendingApproval;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import cn.lunalhx.ai.types.enums.ResponseCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
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

        Disposable disposable = agentLoopService.ask(toQuestion(request))
                .filter(event -> shouldSend(event, request))
                .subscribe(event -> send(emitter, event),
                        throwable -> sendAndComplete(emitter, completed, fallbackError(throwable)),
                        () -> complete(emitter, completed));

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
                .subscribe(event -> send(emitter, event),
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
                .subscribe(event -> send(emitter, event),
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

    private AgentQuestion toQuestion(AgentAskRequest request) {
        return AgentQuestion.builder()
                .question(StringUtils.defaultIfBlank(StringUtils.trim(request.getQuestion()), StringUtils.trim(request.getMessage())))
                .workspace(request.getWorkspace())
                .maxSteps(request.getMaxSteps())
                .includeTrace(request.getIncludeTrace())
                .build();
    }

    private boolean shouldSend(AgentEvent event, AgentAskRequest request) {
        if (Boolean.TRUE.equals(request.getIncludeTrace())) {
            return true;
        }
        return event.getType() == AgentEventType.META
                || event.getType() == AgentEventType.PLAN_UPDATED
                || event.getType() == AgentEventType.REPLAN_STARTED
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
                .build();
    }

    private ApprovalDecision parseApprovalDecision(String value) {
        try {
            return ApprovalDecision.valueOf(StringUtils.upperCase(StringUtils.trim(value)));
        } catch (Exception e) {
            return null;
        }
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
