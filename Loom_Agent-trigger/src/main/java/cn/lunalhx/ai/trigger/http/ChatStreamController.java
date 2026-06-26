package cn.lunalhx.ai.trigger.http;

import cn.lunalhx.ai.api.dto.ChatStreamEvent;
import cn.lunalhx.ai.api.dto.ChatStreamRequest;
import cn.lunalhx.ai.api.dto.ResponseFormat;
import cn.lunalhx.ai.api.dto.TokenUsageDTO;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.StreamEvent;
import cn.lunalhx.ai.domain.conversation.service.ChatStreamService;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.model.valobj.StreamEventType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/chat")
public class ChatStreamController {

    private final ChatStreamService chatStreamService;
    private final ModelRuntimeProperties modelRuntimeProperties;
    private final Validator validator;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final StreamRequestLimiter streamRequestLimiter;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody(required = false) ChatStreamRequest request,
                              HttpServletRequest httpRequest) {
        SseEmitter emitter = new SseEmitter(modelRuntimeProperties.getStreamTimeoutMs() + 5000L);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Runnable> terminateRef = new AtomicReference<>();

        StreamEvent validationError = validateRequest(request);
        if (validationError != null) {
            threadPoolExecutor.execute(() -> sendAndComplete(emitter, completed, validationError));
            return emitter;
        }

        String clientKey = streamRequestLimiter.resolveClientKey(httpRequest);
        StreamRequestLimiter.Lease lease = streamRequestLimiter.tryAcquire(clientKey, "chat-stream");
        if (!lease.isAllowed()) {
            StreamEvent rejectEvent = StreamEvent.builder()
                    .type(StreamEventType.ERROR)
                    .requestId(UUID.randomUUID().toString())
                    .code(lease.rejectCode())
                    .message(lease.rejectMessage())
                    .build();
            sendAndComplete(emitter, completed, rejectEvent);
            return emitter;
        }
        terminateRef.set(lease::release);

        String requestId = UUID.randomUUID().toString();
        MDC.put("request_id", requestId);
        MDC.put("conversation_id", StringUtils.defaultString(request.getConversationId()));
        Disposable disposable = chatStreamService.stream(toPrompt(request, requestId))
                .subscribe(event -> withMdc(event, () -> send(emitter, event)),
                        throwable -> {
                            sendAndComplete(emitter, completed, fallbackError(request, throwable));
                            Runnable t = terminateRef.get();
                            if (t != null) t.run();
                        },
                        () -> {
                            complete(emitter, completed);
                            Runnable t = terminateRef.get();
                            if (t != null) t.run();
                        });
        MDC.clear();

        emitter.onCompletion(() -> {
            disposable.dispose();
            Runnable t = terminateRef.get();
            if (t != null) t.run();
        });
        emitter.onTimeout(() -> {
            disposable.dispose();
            sendAndComplete(emitter, completed, timeoutError(request));
            Runnable t = terminateRef.get();
            if (t != null) t.run();
        });
        emitter.onError(throwable -> {
            disposable.dispose();
            log.warn("SSE connection closed with error: {}", throwable.getMessage());
            Runnable t = terminateRef.get();
            if (t != null) t.run();
        });
        return emitter;
    }

    private StreamEvent validateRequest(ChatStreamRequest request) {
        String requestId = UUID.randomUUID().toString();
        if (request == null) {
            return StreamEvent.builder()
                    .type(StreamEventType.ERROR)
                    .requestId(requestId)
                    .code(ModelErrorCode.INVALID_REQUEST.code())
                    .message("请求体不能为空")
                    .build();
        }
        Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return null;
        }
        String message = violations.stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("; "));
        return StreamEvent.builder()
                .type(StreamEventType.ERROR)
                .requestId(requestId)
                .conversationId(request.getConversationId())
                .model(request.getModel())
                .code(ModelErrorCode.INVALID_REQUEST.code())
                .message(message)
                .build();
    }

    private ChatPrompt toPrompt(ChatStreamRequest request, String requestId) {
        ResponseFormat responseFormat = request.getResponseFormat() == null ? ResponseFormat.TEXT : request.getResponseFormat();
        return ChatPrompt.builder()
                .requestId(requestId)
                .conversationId(request.getConversationId())
                .message(StringUtils.trim(request.getMessage()))
                .systemPrompt(request.getSystemPrompt())
                .model(request.getModel())
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .outputFormat(OutputFormat.valueOf(responseFormat.name()))
                .build();
    }

    private void withMdc(StreamEvent event, Runnable runnable) {
        if (event != null) {
            MDC.put("request_id", StringUtils.defaultString(event.getRequestId()));
            MDC.put("conversation_id", StringUtils.defaultString(event.getConversationId()));
        }
        try {
            runnable.run();
        } finally {
            MDC.clear();
        }
    }

    private void send(SseEmitter emitter, StreamEvent event) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name(event.getType().eventName())
                        .data(toDto(event), MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
        }
    }

    private void sendAndComplete(SseEmitter emitter, AtomicBoolean completed, StreamEvent event) {
        send(emitter, event);
        complete(emitter, completed);
    }

    private void complete(SseEmitter emitter, AtomicBoolean completed) {
        if (completed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private StreamEvent timeoutError(ChatStreamRequest request) {
        return StreamEvent.builder()
                .type(StreamEventType.ERROR)
                .conversationId(request == null ? null : request.getConversationId())
                .model(request == null ? null : request.getModel())
                .code(ModelErrorCode.TIMEOUT.code())
                .message(ModelErrorCode.TIMEOUT.message())
                .build();
    }

    private StreamEvent fallbackError(ChatStreamRequest request, Throwable throwable) {
        log.warn("Chat stream failed: {}", throwable.getMessage(), throwable);
        return StreamEvent.builder()
                .type(StreamEventType.ERROR)
                .conversationId(request == null ? null : request.getConversationId())
                .model(request == null ? null : request.getModel())
                .code(ModelErrorCode.MODEL_ERROR.code())
                .message(ModelErrorCode.MODEL_ERROR.message())
                .build();
    }

    private ChatStreamEvent toDto(StreamEvent event) {
        return ChatStreamEvent.builder()
                .type(event.getType().eventName())
                .requestId(event.getRequestId())
                .conversationId(event.getConversationId())
                .model(event.getModel())
                .token(event.getToken())
                .finishReason(event.getFinishReason())
                .usage(event.getUsage() == null ? null : TokenUsageDTO.builder()
                        .promptTokens(event.getUsage().getPromptTokens())
                        .completionTokens(event.getUsage().getCompletionTokens())
                        .totalTokens(event.getUsage().getTotalTokens())
                        .build())
                .code(event.getCode())
                .message(event.getMessage())
                .build();
    }

}
