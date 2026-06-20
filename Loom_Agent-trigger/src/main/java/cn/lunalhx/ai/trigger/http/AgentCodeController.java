package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.AgentAskRequest;
import cn.lunalhx.ai.api.dto.AgentStreamEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
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
        return null;
    }

    private AgentQuestion toQuestion(AgentAskRequest request) {
        return AgentQuestion.builder()
                .question(StringUtils.trim(request.getQuestion()))
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
                .requestId(event.getRequestId())
                .conversationId(event.getConversationId())
                .step(event.getStep())
                .node(event.getNode())
                .nodeInputs(event.getNodeInputs())
                .thought(event.getThought())
                .tool(event.getTool())
                .input(event.getInput())
                .observation(event.getObservation())
                .truncated(event.getTruncated())
                .answer(event.getAnswer())
                .stopReason(event.getStopReason() == null ? null : event.getStopReason().name())
                .stepCount(event.getStepCount())
                .code(event.getCode())
                .message(event.getMessage())
                .build();
    }

}
