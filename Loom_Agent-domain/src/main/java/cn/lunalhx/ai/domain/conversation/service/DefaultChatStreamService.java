package cn.lunalhx.ai.domain.conversation.service;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.conversation.model.entity.StreamEvent;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.service.OutputFormatValidator;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.model.valobj.StreamEventType;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
        AtomicReference<TokenUsage> usage = new AtomicReference<>();
        AtomicBoolean tokenEmitted = new AtomicBoolean(false);
        Flux<StreamEvent> tokenEvents = streamAttempt(prompt, output, tokenEmitted, usage, false, 0)
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
                .capability(StringUtils.defaultIfBlank(rawPrompt.getCapability(), ModelCapabilities.STREAM_CHAT))
                .purpose(rawPrompt.getPurpose() == null ? ModelCallPurpose.FINAL_TEXT : rawPrompt.getPurpose())
                .deadlineEpochMs(rawPrompt.getDeadlineEpochMs() == null
                        ? System.currentTimeMillis() + properties.getStreamTimeoutMs()
                        : rawPrompt.getDeadlineEpochMs())
                .outputFormat(outputFormat)
                .build();
    }

    private Flux<StreamEvent> streamAttempt(ChatPrompt prompt,
                                            StringBuilder output,
                                            AtomicBoolean tokenEmitted,
                                            AtomicReference<TokenUsage> usage,
                                            boolean escalated,
                                            int continuationCount) {
        AtomicReference<String> finishReason = new AtomicReference<>("stop");
        int outputStart = output.length();
        return guardedModelStream(prompt, tokenEmitted)
                .concatMap(chunk -> toEvents(prompt, chunk, output, tokenEmitted, finishReason, usage))
                .concatWith(Flux.defer(() -> {
                    if (!"length".equalsIgnoreCase(finishReason.get())) {
                        return toFinalEvent(prompt, finishReason.get(), usage.get(), output.toString()).flux();
                    }
                    if (output.length() == outputStart && !escalated) {
                        return streamAttempt(copyPrompt(prompt, 64000, null), output, tokenEmitted, usage, true, continuationCount);
                    }
                    if (prompt.getPurpose() == null
                            || !prompt.getPurpose().isContinuationAllowed()
                            || prompt.getOutputFormat() != OutputFormat.TEXT
                            || continuationCount >= 3) {
                        return Mono.just(toErrorEvent(prompt, ModelErrorCode.OUTPUT_TRUNCATED,
                                ModelErrorCode.OUTPUT_TRUNCATED.message())).flux();
                    }
                    ChatPrompt continuation = copyPrompt(prompt, 64000, java.util.List.of(
                            ChatMessage.builder().role("user").content(prompt.getMessage()).build(),
                            ChatMessage.builder().role("assistant").content(output.toString()).build(),
                            ChatMessage.builder().role("user")
                                    .content("Continue exactly where the previous output stopped. No apology, no recap.")
                                    .build()));
                    return streamAttempt(continuation, output, tokenEmitted, usage, true, continuationCount + 1);
                }));
    }

    private ChatPrompt copyPrompt(ChatPrompt source, int maxTokens, java.util.List<ChatMessage> messages) {
        return ChatPrompt.builder()
                .requestId(source.getRequestId())
                .conversationId(source.getConversationId())
                .message(source.getMessage())
                .systemPrompt(source.getSystemPrompt())
                .model(source.getModel())
                .temperature(source.getTemperature())
                .maxTokens(maxTokens)
                .outputFormat(source.getOutputFormat())
                .capability(source.getCapability())
                .purpose(source.getPurpose())
                .deadlineEpochMs(source.getDeadlineEpochMs())
                .messages(messages)
                .build();
    }

    private Flux<ModelStreamChunk> guardedModelStream(ChatPrompt prompt, AtomicBoolean tokenEmitted) {
        long remainingMs = prompt.getDeadlineEpochMs() == null
                ? properties.getStreamTimeoutMs()
                : Math.max(1L, prompt.getDeadlineEpochMs() - System.currentTimeMillis());
        return Flux.defer(() -> modelGateway.stream(prompt))
                .timeout(Duration.ofMillis(Math.min(properties.getFirstTokenTimeoutMs(), remainingMs)),
                        ignored -> Mono.delay(Duration.ofMillis(remainingMs)));
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
