package cn.lunalhx.ai.trigger.http.chat;

import cn.lunalhx.ai.api.dto.ChatStreamEvent;
import cn.lunalhx.ai.api.dto.ChatStreamRequest;
import cn.lunalhx.ai.api.dto.TokenUsageDTO;
import cn.lunalhx.ai.domain.conversation.model.entity.StreamEvent;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.StreamEventType;
import cn.lunalhx.ai.trigger.http.sse.SseResponder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSseResponder {

    private final ModelRuntimeProperties modelRuntimeProperties;
    private final ThreadPoolExecutor threadPoolExecutor;

    private final SseResponder sseResponder = new SseResponder();

    public SseEmitter completedError(StreamEvent errorEvent) {
        SseResponder.Session session = sseResponder.open(
                modelRuntimeProperties.getStreamTimeoutMs() + 5000L, (Runnable) null);
        threadPoolExecutor.execute(() -> {
            session.sendAndComplete(errorEvent.getType().eventName(), toDto(errorEvent));
        });
        return session.emitter();
    }

    public SseEmitter stream(ChatStreamRequest request, String requestId,
                             Supplier<Flux<StreamEvent>> sourceSupplier,
                             Runnable onTerminate) {
        long timeoutMs = modelRuntimeProperties.getStreamTimeoutMs() + 5000L;
        ChatStreamEvent timeoutDto = toDto(timeoutError(request));
        SseResponder.TimeoutEvent timeoutEvent = new SseResponder.TimeoutEvent(
                StreamEventType.ERROR.eventName(), timeoutDto);

        SseResponder.Session session = sseResponder.open(timeoutMs, () -> timeoutEvent, onTerminate);

        MDC.put("request_id", requestId);
        MDC.put("conversation_id", StringUtils.defaultString(request.getConversationId()));
        try {
            Flux<StreamEvent> flux;
            try {
                flux = sourceSupplier.get();
            } catch (Exception e) {
                log.warn("Chat stream source supplier threw exception: {}", e.getMessage(), e);
                session.sendAndComplete(
                        StreamEventType.ERROR.eventName(),
                        toDto(fallbackError(request, e)));
                return session.emitter();
            }

            Disposable disposable = flux.subscribe(
                    event -> {
                        MDC.put("request_id", StringUtils.defaultString(event.getRequestId()));
                        MDC.put("conversation_id", StringUtils.defaultString(event.getConversationId()));
                        try {
                            session.send(event.getType().eventName(), toDto(event));
                        } finally {
                            MDC.clear();
                        }
                    },
                    throwable -> session.sendAndComplete(
                            StreamEventType.ERROR.eventName(),
                            toDto(fallbackError(request, throwable))),
                    session::complete
            );
            session.bind(disposable);
        } finally {
            MDC.clear();
        }

        return session.emitter();
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
}
