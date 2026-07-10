package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.infrastructure.gateway.diagnostics.PromptCacheDiagnosticContext;
import cn.lunalhx.ai.infrastructure.gateway.diagnostics.PromptCacheDiagnosticHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Component("springAiModelGateway")
public class SpringAiModelGateway implements ModelGateway {

    private final ChatModel chatModel;
    private final ModelRuntimeProperties runtimeProperties;
    private final ObjectMapper objectMapper;
    private final PromptCacheDiagnosticHook diagnosticHook;
    private final ThreadPoolExecutor executor;

    public SpringAiModelGateway(ChatModel chatModel,
                                ModelRuntimeProperties runtimeProperties,
                                ObjectMapper objectMapper,
                                PromptCacheDiagnosticHook diagnosticHook,
                                ThreadPoolExecutor executor) {
        this.chatModel = chatModel;
        this.runtimeProperties = runtimeProperties;
        this.objectMapper = objectMapper;
        this.diagnosticHook = diagnosticHook != null ? diagnosticHook : new PromptCacheDiagnosticHook(null);
        this.executor = executor;
    }

    @Override
    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
        return Flux.create(sink -> executor.execute(() -> executeStream(prompt, sink)),
                FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
        return Mono.fromCallable(() -> executeComplete(prompt))
                .subscribeOn(Schedulers.fromExecutor(executor));
    }

    private void executeStream(ChatPrompt prompt, FluxSink<ModelStreamChunk> sink) {
        String provider = runtimeProperties.getProvider();
        String resolvedModel = resolvedModel(prompt);

        validateApiKey();

        Prompt springPrompt = toSpringAiPrompt(prompt);
        Object options = buildOptions(prompt, true);
        Prompt fullPrompt = new Prompt(springPrompt.getInstructions(), (org.springframework.ai.chat.prompt.ChatOptions) options);

        List<Map<String, String>> messages = extractMessages(springPrompt);
        String canonicalPayload = safeBuildCanonicalPayload(fullPrompt, (org.springframework.ai.chat.prompt.ChatOptions) options, resolvedModel);

        PromptCacheDiagnosticContext diagnosticCtx = diagnosticHook.beforeSend(
                resolvedModel,
                prompt.getCapability(),
                prompt.getPurpose() == null ? null : prompt.getPurpose().name(),
                prompt.getConversationId(),
                canonicalPayload,
                messages);

        AtomicReference<TokenUsage> lastUsage = new AtomicReference<>();

        try {
            chatModel.stream(fullPrompt)
                    .doOnNext(response -> {
                        if (sink.isCancelled()) return;
                        ModelStreamChunk chunk = mapStreamChunk(response);
                        if (chunk != null) {
                            if (chunk.getUsage() != null) {
                                lastUsage.set(chunk.getUsage());
                            }
                            sink.next(chunk);
                        }
                    })
                    .doOnComplete(sink::complete)
                    .doOnError(error -> {
                        ModelGatewayException mapped = SpringAiErrorMapper.toException(error, provider, resolvedModel);
                        sink.error(mapped);
                    })
                    .subscribe();
        } catch (Exception e) {
            sink.error(SpringAiErrorMapper.toException(e, provider, resolvedModel));
        } finally {
            diagnosticHook.afterSend(diagnosticCtx, lastUsage.get());
        }
    }

    private ModelChatResult executeComplete(ChatPrompt prompt) {
        String provider = runtimeProperties.getProvider();
        String resolvedModel = resolvedModel(prompt);

        validateApiKey();

        Prompt springPrompt = toSpringAiPrompt(prompt);
        Object options = buildOptions(prompt, false);
        Prompt fullPrompt = new Prompt(springPrompt.getInstructions(), (org.springframework.ai.chat.prompt.ChatOptions) options);

        List<Map<String, String>> messages = extractMessages(springPrompt);
        String canonicalPayload = safeBuildCanonicalPayload(fullPrompt, (org.springframework.ai.chat.prompt.ChatOptions) options, resolvedModel);

        PromptCacheDiagnosticContext diagnosticCtx = diagnosticHook.beforeSend(
                resolvedModel,
                prompt.getCapability(),
                prompt.getPurpose() == null ? null : prompt.getPurpose().name(),
                prompt.getConversationId(),
                canonicalPayload,
                messages);

        TokenUsage usage = null;
        try {
            ChatResponse response = chatModel.call(fullPrompt);
            ModelChatResult result = mapChatResult(response, resolvedModel);
            usage = result == null ? null : result.getUsage();
            return result;
        } catch (Exception e) {
            throw SpringAiErrorMapper.toException(e, provider, resolvedModel);
        } finally {
            diagnosticHook.afterSend(diagnosticCtx, usage);
        }
    }

    private void validateApiKey() {
        ModelRuntimeProperties.ProviderConfig activeProvider = runtimeProperties.activeProvider();
        if (StringUtils.isBlank(activeProvider.getApiKey())) {
            throw new ModelGatewayException(ModelErrorCode.CONFIG_ERROR,
                    "API Key 不能为空", false, null, null);
        }
    }

    private Prompt toSpringAiPrompt(ChatPrompt chatPrompt) {
        List<Message> messages = new ArrayList<>();

        String systemPrompt = chatPrompt.getSystemPrompt();
        if (OutputFormat.JSON_OBJECT == chatPrompt.getOutputFormat()) {
            String jsonInstruction = "请只输出一个合法 JSON 对象，不要使用 Markdown 代码块，也不要输出 JSON 之外的解释文字。";
            systemPrompt = StringUtils.isBlank(systemPrompt) ? jsonInstruction : systemPrompt + "\n" + jsonInstruction;
        }
        if (StringUtils.isNotBlank(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }

        if (chatPrompt.getMessages() != null && !chatPrompt.getMessages().isEmpty()) {
            for (ChatMessage msg : chatPrompt.getMessages()) {
                if (msg != null && StringUtils.isNotBlank(msg.getRole()) && StringUtils.isNotBlank(msg.getContent())) {
                    messages.add(toSpringAiMessage(msg));
                }
            }
        } else {
            messages.add(new UserMessage(chatPrompt.getMessage()));
        }

        return new Prompt(messages);
    }

    private Message toSpringAiMessage(ChatMessage msg) {
        String role = msg.getRole().toLowerCase();
        return switch (role) {
            case "system" -> new SystemMessage(msg.getContent());
            case "user" -> new UserMessage(msg.getContent());
            case "assistant" -> new AssistantMessage(msg.getContent());
            default -> throw new ModelGatewayException(ModelErrorCode.INVALID_REQUEST,
                    "不支持的消息角色: " + msg.getRole(), false, null, null);
        };
    }

    private Object buildOptions(ChatPrompt chatPrompt, boolean stream) {
        String provider = runtimeProperties.getProvider();
        if ("deepseek".equals(provider)) {
            return buildDeepSeekOptions(chatPrompt);
        }
        if ("opencode-go".equals(provider)) {
            return buildOpenAiOptions(chatPrompt, stream);
        }
        throw new ModelGatewayException(ModelErrorCode.CONFIG_ERROR,
                "不支持的 provider: " + provider, false, null, null);
    }

    private DeepSeekChatOptions buildDeepSeekOptions(ChatPrompt chatPrompt) {
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
                .model(resolvedModel(chatPrompt))
                .temperature(chatPrompt.getTemperature() != null ? chatPrompt.getTemperature() : defaultTemperature())
                .maxTokens(chatPrompt.getMaxTokens() != null ? chatPrompt.getMaxTokens() : defaultMaxTokens());

        if (OutputFormat.JSON_OBJECT == chatPrompt.getOutputFormat()) {
            builder.responseFormat(
                    org.springframework.ai.deepseek.api.ResponseFormat.builder()
                            .type(org.springframework.ai.deepseek.api.ResponseFormat.Type.JSON_OBJECT)
                            .build());
        }

        return builder.build();
    }

    private OpenAiChatOptions buildOpenAiOptions(ChatPrompt chatPrompt, boolean stream) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(resolvedModel(chatPrompt))
                .temperature(chatPrompt.getTemperature() != null ? chatPrompt.getTemperature() : defaultTemperature())
                .maxTokens(chatPrompt.getMaxTokens() != null ? chatPrompt.getMaxTokens() : defaultMaxTokens());

        if (stream) {
            builder.streamUsage(true);
        }

        if (OutputFormat.JSON_OBJECT == chatPrompt.getOutputFormat()) {
            builder.responseFormat(org.springframework.ai.openai.api.ResponseFormat.builder()
                    .type(org.springframework.ai.openai.api.ResponseFormat.Type.JSON_OBJECT)
                    .build());
        }

        return builder.build();
    }

    private ModelStreamChunk mapStreamChunk(ChatResponse response) {
        List<Generation> generations = response.getResults();
        TokenUsage usage = mapUsage(response.getMetadata() != null ? response.getMetadata().getUsage() : null);

        if (generations == null || generations.isEmpty()) {
            return usage == null ? null : ModelStreamChunk.builder().usage(usage).build();
        }

        Generation gen = generations.get(0);
        String content = gen.getOutput() != null ? gen.getOutput().getText() : null;
        String finishReason = gen.getMetadata() != null ? gen.getMetadata().getFinishReason() : null;

        if (StringUtils.isEmpty(content) && StringUtils.isBlank(finishReason) && usage == null) {
            return null;
        }

        return ModelStreamChunk.builder()
                .content(content)
                .finishReason(finishReason)
                .usage(usage)
                .build();
    }

    private ModelChatResult mapChatResult(ChatResponse response, String resolvedModel) {
        List<Generation> generations = response.getResults();
        if (generations == null || generations.isEmpty()) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_ERROR, "模型响应缺少 choices", false, null, null);
        }

        Generation gen = generations.get(0);
        return ModelChatResult.builder()
                .content(gen.getOutput() != null ? gen.getOutput().getText() : null)
                .finishReason(gen.getMetadata() != null ? gen.getMetadata().getFinishReason() : null)
                .usage(mapUsage(response.getMetadata() != null ? response.getMetadata().getUsage() : null))
                .actualModel(response.getMetadata() != null ? response.getMetadata().getModel() : resolvedModel)
                .build();
    }

    private TokenUsage mapUsage(org.springframework.ai.chat.metadata.Usage springUsage) {
        if (springUsage == null) return null;

        TokenUsage.TokenUsageBuilder builder = TokenUsage.builder()
                .promptTokens(springUsage.getPromptTokens())
                .completionTokens(springUsage.getCompletionTokens())
                .totalTokens(springUsage.getTotalTokens());

        Object nativeUsage = springUsage.getNativeUsage();
        if (nativeUsage != null) {
            Integer cachedTokens = extractCachedTokens(nativeUsage);
            if (cachedTokens != null) {
                builder.promptCacheHitTokens(cachedTokens);
            }
        }

        return builder.build();
    }

    private Integer extractCachedTokens(Object nativeUsage) {
        try {
            java.lang.reflect.Method detailsMethod = nativeUsage.getClass().getMethod("promptTokensDetails");
            Object details = detailsMethod.invoke(nativeUsage);
            if (details == null) return null;
            java.lang.reflect.Method cachedMethod = details.getClass().getMethod("cachedTokens");
            return (Integer) cachedMethod.invoke(details);
        } catch (Exception e) {
            return null;
        }
    }

    private String safeBuildCanonicalPayload(Prompt prompt, org.springframework.ai.chat.prompt.ChatOptions options, String model) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("model", model);
            List<Map<String, String>> msgList = new ArrayList<>();
            for (Message msg : prompt.getInstructions()) {
                msgList.add(Map.of("role", msg.getMessageType().name().toLowerCase(), "content", msg.getText()));
            }
            canonical.put("messages", msgList);
            if (options != null) {
                if (options.getTemperature() != null) canonical.put("temperature", options.getTemperature());
                if (options.getMaxTokens() != null) canonical.put("maxTokens", options.getMaxTokens());
            }
            return objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            return prompt.toString();
        }
    }

    private List<Map<String, String>> extractMessages(Prompt prompt) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Message msg : prompt.getInstructions()) {
            result.add(Map.of("role", msg.getMessageType().name().toLowerCase(), "content", msg.getText()));
        }
        return result;
    }

    private String resolvedModel(ChatPrompt chatPrompt) {
        return StringUtils.defaultIfBlank(chatPrompt.getModel(), defaultModel());
    }

    private String defaultModel() {
        return runtimeProperties.activeProvider().getDefaultModel();
    }

    private Double defaultTemperature() {
        return runtimeProperties.activeProvider().getTemperature();
    }

    private Integer defaultMaxTokens() {
        return runtimeProperties.activeProvider().getMaxTokens();
    }

}
