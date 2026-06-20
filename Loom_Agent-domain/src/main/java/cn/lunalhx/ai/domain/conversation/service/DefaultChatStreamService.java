package cn.lunalhx.ai.domain.conversation.service;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.conversation.model.entity.StreamEvent;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.service.OutputFormatValidator;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.model.valobj.StreamEventType;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class DefaultChatStreamService implements ChatStreamService {

    private final ModelGateway modelGateway;
    private final ModelRuntimeProperties properties;
    private final OutputFormatValidator outputFormatValidator;
    private final String defaultModel;
    private final Double defaultTemperature;
    private final Integer defaultMaxTokens;

    public DefaultChatStreamService(ModelGateway modelGateway,
                                    ModelRuntimeProperties properties,
                                    OutputFormatValidator outputFormatValidator,
                                    String defaultModel,
                                    Double defaultTemperature,
                                    Integer defaultMaxTokens) {
        this.modelGateway = modelGateway;
        this.properties = properties;
        this.outputFormatValidator = outputFormatValidator;
        this.defaultModel = defaultModel;
        this.defaultTemperature = defaultTemperature;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    @Override
    public Flux<StreamEvent> stream(ChatPrompt rawPrompt) {
        ChatPrompt prompt = normalize(rawPrompt);
        StringBuilder output = new StringBuilder();
        AtomicBoolean tokenEmitted = new AtomicBoolean(false);
        AtomicReference<String> finishReason = new AtomicReference<>("stop");
        AtomicReference<TokenUsage> usage = new AtomicReference<>();

        Flux<StreamEvent> tokenEvents = guardedModelStream(prompt, tokenEmitted)
                .concatMap(chunk -> toEvents(prompt, chunk, output, tokenEmitted, finishReason, usage))
                .concatWith(Mono.defer(() -> toFinalEvent(prompt, finishReason.get(), usage.get(), output.toString())))
                .onErrorResume(throwable -> Mono.just(toErrorEvent(prompt, throwable)));

        return Flux.concat(Mono.just(toMetaEvent(prompt)), tokenEvents);
    }

    private ChatPrompt normalize(ChatPrompt rawPrompt) {
        String requestId = StringUtils.defaultIfBlank(rawPrompt.getRequestId(), UUID.randomUUID().toString());
        String conversationId = StringUtils.defaultIfBlank(rawPrompt.getConversationId(), UUID.randomUUID().toString());
        String model = properties.normalizeModel(rawPrompt.getModel(), defaultModel);
        OutputFormat outputFormat = rawPrompt.getOutputFormat() == null ? OutputFormat.TEXT : rawPrompt.getOutputFormat();
        return ChatPrompt.builder()
                .requestId(requestId)
                .conversationId(conversationId)
                .message(rawPrompt.getMessage())
                .systemPrompt(rawPrompt.getSystemPrompt())
                .model(model)
                .temperature(rawPrompt.getTemperature() == null ? defaultTemperature : rawPrompt.getTemperature())
                .maxTokens(rawPrompt.getMaxTokens() == null ? defaultMaxTokens : rawPrompt.getMaxTokens())
                .outputFormat(outputFormat)
                .build();
    }

    private Flux<ModelStreamChunk> guardedModelStream(ChatPrompt prompt, AtomicBoolean tokenEmitted) {
        int maxAttempts = Math.max(1, properties.getRetryMaxAttempts());
        Retry retry = Retry.backoff(maxAttempts - 1, Duration.ofMillis(properties.getRetryBackoffInitialMs()))
                .maxBackoff(Duration.ofMillis(properties.getRetryBackoffMaxMs()))
                .jitter(0.2)
                .filter(throwable -> !tokenEmitted.get() && isRetryable(throwable));

        return Flux.defer(() -> modelGateway.stream(prompt))
                .timeout(Duration.ofMillis(properties.getFirstTokenTimeoutMs()),
                        ignored -> Mono.delay(Duration.ofMillis(properties.getStreamTimeoutMs())))
                .retryWhen(retry);
    }

    private boolean isRetryable(Throwable throwable) {
        return throwable instanceof ModelGatewayException && ((ModelGatewayException) throwable).isRetryable();
    }

    private Flux<StreamEvent> toEvents(ChatPrompt prompt,
                                       ModelStreamChunk chunk,
                                       StringBuilder output,
                                       AtomicBoolean tokenEmitted,
                                       AtomicReference<String> finishReason,
                                       AtomicReference<TokenUsage> usage) {
        if (StringUtils.isNotBlank(chunk.getFinishReason())) {
            finishReason.set(chunk.getFinishReason());
        }
        if (chunk.getUsage() != null) {
            usage.set(chunk.getUsage());
        }
        if (StringUtils.isEmpty(chunk.getContent())) {
            return Flux.empty();
        }
        output.append(chunk.getContent());
        return Flux.fromStream(chunk.getContent().codePoints()
                .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                .map(token -> {
                    tokenEmitted.set(true);
                    return StreamEvent.builder()
                            .type(StreamEventType.TOKEN)
                            .requestId(prompt.getRequestId())
                            .conversationId(prompt.getConversationId())
                            .model(prompt.getModel())
                            .token(token)
                            .build();
                }));
    }

    private Mono<StreamEvent> toFinalEvent(ChatPrompt prompt, String finishReason, TokenUsage usage, String output) {
        ModelErrorCode finishError = finishReasonToError(finishReason);
        if (finishError != null) {
            return Mono.just(toErrorEvent(prompt, finishError, finishError.message()));
        }
        try {
            outputFormatValidator.validate(prompt.getOutputFormat(), output);
            return Mono.just(StreamEvent.builder()
                    .type(StreamEventType.DONE)
                    .requestId(prompt.getRequestId())
                    .conversationId(prompt.getConversationId())
                    .model(prompt.getModel())
                    .finishReason(StringUtils.defaultIfBlank(finishReason, "stop"))
                    .usage(usage)
                    .build());
        } catch (ModelGatewayException e) {
            return Mono.just(toErrorEvent(prompt, e));
        }
    }

    private ModelErrorCode finishReasonToError(String finishReason) {
        if ("length".equals(finishReason)) {
            return ModelErrorCode.OUTPUT_TRUNCATED;
        }
        if ("content_filter".equals(finishReason)) {
            return ModelErrorCode.CONTENT_FILTERED;
        }
        if ("insufficient_system_resource".equals(finishReason)) {
            return ModelErrorCode.PROVIDER_UNAVAILABLE;
        }
        return null;
    }

    private StreamEvent toMetaEvent(ChatPrompt prompt) {
        return StreamEvent.builder()
                .type(StreamEventType.META)
                .requestId(prompt.getRequestId())
                .conversationId(prompt.getConversationId())
                .model(prompt.getModel())
                .build();
    }

    private StreamEvent toErrorEvent(ChatPrompt prompt, Throwable throwable) {
        if (throwable instanceof TimeoutException) {
            return toErrorEvent(prompt, ModelErrorCode.TIMEOUT, ModelErrorCode.TIMEOUT.message());
        }
        if (throwable instanceof ModelGatewayException) {
            ModelGatewayException exception = (ModelGatewayException) throwable;
            return toErrorEvent(prompt, exception.getErrorCode(), exception.getMessage());
        }
        return toErrorEvent(prompt, ModelErrorCode.MODEL_ERROR, ModelErrorCode.MODEL_ERROR.message());
    }

    private StreamEvent toErrorEvent(ChatPrompt prompt, ModelErrorCode errorCode, String message) {
        return StreamEvent.builder()
                .type(StreamEventType.ERROR)
                .requestId(prompt.getRequestId())
                .conversationId(prompt.getConversationId())
                .model(prompt.getModel())
                .code(errorCode.code())
                .message(StringUtils.defaultIfBlank(message, errorCode.message()))
                .build();
    }

}
