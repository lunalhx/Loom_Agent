package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheRequest;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Direct HTTP model gateway supporting deepseek, openai, anthropic and ollama.
 *
 * <p>Ports the Python loom-code provider clients:
 * <ul>
 *   <li>deepseek: Anthropic Messages protocol at {@code {base}/v1/messages}</li>
 *   <li>openai: OpenAI Responses protocol at {@code {base}/responses}</li>
 *   <li>anthropic: Anthropic Messages protocol at {@code {base}/v1/messages}</li>
 *   <li>ollama: {@code /api/generate}</li>
 * </ul>
 *
 * <p>Prompt-cache protocol: cache fields are written only when the request is
 * enabled (policy != NONE and feature flag on) and the provider/model
 * capability declares support. The stable prefix stays in the {@code system}
 * role; dynamic sections stay as separate user messages. When a provider
 * rejects the cache parameters with a recognizable 4xx, the gateway retries
 * once without cache fields (degradation event, never an infinite loop).
 */
public class HttpModelGateway implements ModelGateway {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HttpModelGateway.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MS = {500L, 1000L};

    private final String provider;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final Double temperature;
    private final Double topP;
    private final long timeoutSeconds;

    private final HttpClient client;

    public HttpModelGateway(String provider, String model, String baseUrl, String apiKey,
                            Double temperature, Double topP, long timeoutSeconds) {
        this.provider = provider;
        this.model = model;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.temperature = temperature;
        this.topP = topP;
        this.timeoutSeconds = timeoutSeconds;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 30)))
                .build();
    }

    public static HttpModelGateway fromProperties(ModelRuntimeProperties properties) {
        ModelRuntimeProperties.ProviderConfig cfg = properties.activeProvider();
        return new HttpModelGateway(
                properties.getProvider(),
                properties.resolvedDefaultModel(),
                cfg.getBaseUrl(),
                cfg.getApiKey(),
                cfg.getTemperature(),
                cfg.getTopP(),
                cfg.getTimeoutSeconds() == null ? 300L : cfg.getTimeoutSeconds());
    }

    @Override
    public reactor.core.publisher.Mono<ModelChatResult> complete(ChatPrompt prompt) {
        return reactor.core.publisher.Mono.fromCallable(() -> execute(prompt));
    }

    @Override
    public reactor.core.publisher.Flux<cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk> stream(ChatPrompt prompt) {
        return reactor.core.publisher.Flux.empty();
    }

    @Override
    public cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability promptCacheCapability() {
        return PromptCacheCapability.fromProviderModel(provider, model, null);
    }

    ModelChatResult execute(ChatPrompt prompt) {
        int maxTokens = prompt.getMaxTokens() == null ? 512 : prompt.getMaxTokens();
        boolean featureEnabled = prompt.getCachePolicy() != null
                && prompt.getCachePolicy() != ChatPrompt.CachePolicy.NONE;
        PromptCacheRequest cacheRequest = ProviderPayloadSerializers.resolveCacheRequest(
                prompt, prompt.getRuntimeProperties(), featureEnabled, provider);

        if (log.isDebugEnabled()) {
            // 脱敏诊断：只含结构长度与 key 前缀，绝不含 prompt 正文。
            log.debug("prompt cache diagnostics: {}",
                    PromptCacheDiagnostics.summary(prompt, cacheRequest));
        }

        switch (provider) {
            case "ollama":
                return callOllama(prompt, maxTokens);
            case "openai":
                return callOpenAI(prompt, maxTokens, cacheRequest);
            case "anthropic":
            case "deepseek":
            default:
                return callAnthropic(prompt, maxTokens, cacheRequest);
        }
    }

    /**
     * 针对明确的"缓存参数不支持"4xx 做一次无缓存回退。只回退一次，
     * 认证、限流、普通 4xx 和网络错误沿用既有策略，绝不造成无限重试。
     * 回退结果携带 fallbackReason，供 trace 观测降级事件。
     */
    private ModelChatResult callWithCacheFallback(java.util.function.Function<PromptCacheRequest, ModelChatResult> call,
                                                  PromptCacheRequest cacheRequest) {
        if (cacheRequest == null || !cacheRequest.enabled()) {
            return call.apply(PromptCacheRequest.none(PromptCacheCapability.UNSUPPORTED));
        }
        try {
            return call.apply(cacheRequest);
        } catch (ModelGatewayTransportException e) {
            if (e.getMessage() != null && isCacheParamRejection(e.getMessage())) {
                ModelChatResult degraded = call.apply(PromptCacheRequest.none(PromptCacheCapability.UNSUPPORTED));
                if (degraded != null) {
                    degraded.setFallbackReason("prompt_cache_parameter_rejected");
                }
                return degraded;
            }
            throw e;
        }
    }

    private boolean isCacheParamRejection(String message) {
        return (message.contains("400") || message.contains("422") || message.contains("invalid_request_error"))
                && (message.contains("cache") || message.contains("prompt_cache"));
    }

    // ==================== Anthropic Messages ====================

    private ModelChatResult callAnthropic(ChatPrompt prompt, int maxTokens, PromptCacheRequest cacheRequest) {
        return callWithCacheFallback(request -> {
            Map<String, Object> payload = ProviderPayloadSerializers.anthropicMessages(
                    prompt, model, maxTokens, temperature, topP, request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey == null ? "" : apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.toJson(payload)))
                    .build();

            String body = sendWithRetry(httpRequest, "Anthropic-compatible");
            Map<String, Object> data = JsonSupport.parseJson(body);
            String error = JsonSupport.asText(data.get("error"));
            if (error != null) {
                throw new ModelGatewayTransportException("Anthropic-compatible error: " + error);
            }
            Object content = data.get("content");
            if (content instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> entry
                            && "text".equals(entry.get("type"))
                            && JsonSupport.asText(entry.get("text")) != null) {
                        Map<?, ?> usage = JsonSupport.asMap(data.get("usage"));
                        return ModelChatResult.builder()
                                .content(JsonSupport.asText(entry.get("text")))
                                .finishReason("stop")
                                .actualModel(model)
                                .usage(usage(usage))
                                .build();
                    }
                }
            }
            throw new ModelGatewayTransportException("Anthropic-compatible error: could not extract text from response");
        }, cacheRequest);
    }

    // ==================== OpenAI Responses ====================

    private ModelChatResult callOpenAI(ChatPrompt prompt, int maxTokens, PromptCacheRequest cacheRequest) {
        return callWithCacheFallback(request -> {
            Map<String, Object> payload = ProviderPayloadSerializers.openAIResponses(
                    prompt, model, maxTokens, temperature, topP, request);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/responses"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "loom-code/0.1")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.toJson(payload)));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            String body = sendWithRetry(builder.build(), "OpenAI-compatible");
            Map<String, Object> data = JsonSupport.parseJson(body);
            String error = JsonSupport.asText(data.get("error"));
            if (error != null) {
                throw new ModelGatewayTransportException("OpenAI-compatible error: " + error);
            }
            String textContent = extractOpenAIText(data);
            if (textContent == null || textContent.isBlank()) {
                throw new ModelGatewayTransportException(
                        "OpenAI-compatible error: could not extract text from response");
            }
            return ModelChatResult.builder()
                    .content(textContent)
                    .finishReason("stop")
                    .actualModel(model)
                    .usage(usage(JsonSupport.asMap(data.get("usage"))))
                    .build();
        }, cacheRequest);
    }

    private String extractOpenAIText(Map<String, Object> data) {
        String outputText = JsonSupport.asText(data.get("output_text"));
        if (outputText != null) {
            return outputText;
        }
        Object output = data.get("output");
        if (output instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> entry) {
                    String text = extractContentText(entry.get("content"));
                    if (text != null) {
                        return text;
                    }
                }
            }
        }
        Object choices = data.get("choices");
        if (choices instanceof List<?> choiceList && !choiceList.isEmpty()) {
            Object first = choiceList.get(0);
            if (first instanceof Map<?, ?> choice) {
                Object message = choice.get("message");
                if (message instanceof Map<?, ?> msg) {
                    Object content = msg.get("content");
                    if (content instanceof String s) {
                        return s;
                    }
                    String text = extractContentText(content);
                    if (text != null) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private String extractContentText(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> entry) {
                    String text = JsonSupport.asText(entry.get("text"));
                    if (text != null) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    // ==================== Ollama ====================

    private ModelChatResult callOllama(ChatPrompt prompt, int maxTokens) {
        Map<String, Object> payload = ProviderPayloadSerializers.ollama(
                prompt, model, maxTokens, temperature, topP);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(JsonSupport.toJson(payload)))
                .build();

        String body = sendOnce(request, "Ollama");
        Map<String, Object> data = JsonSupport.parseJson(body);
        String error = JsonSupport.asText(data.get("error"));
        if (error != null) {
            throw new ModelGatewayTransportException("Ollama error: " + error);
        }
        return ModelChatResult.builder()
                .content(JsonSupport.asText(data.get("response")) == null ? "" : JsonSupport.asText(data.get("response")))
                .finishReason("stop")
                .actualModel(model)
                .usage(null)
                .build();
    }

    // ==================== HTTP plumbing ====================

    private String sendWithRetry(HttpRequest request, String backend) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 500 && attempt < MAX_ATTEMPTS - 1) {
                    sleep(BACKOFF_MS[attempt]);
                    continue;
                }
                if (response.statusCode() >= 400) {
                    throw new ModelGatewayTransportException(
                            backend + " request failed with HTTP " + response.statusCode()
                                    + ": " + response.body());
                }
                return response.body();
            } catch (ModelGatewayTransportException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelGatewayTransportException(
                        backend + " request interrupted", e);
            } catch (Exception e) {
                if (attempt < MAX_ATTEMPTS - 1) {
                    sleep(BACKOFF_MS[attempt]);
                    continue;
                }
                throw new ModelGatewayTransportException(
                        "Could not reach the " + backend + " backend. Base URL: " + baseUrl
                                + " Model: " + model,
                        e);
            }
        }
        throw new ModelGatewayTransportException("Could not reach the " + backend + " backend.");
    }

    private String sendOnce(HttpRequest request, String backend) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ModelGatewayTransportException(
                        backend + " request failed with HTTP " + response.statusCode()
                                + ": " + response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayTransportException(backend + " request interrupted", e);
        } catch (ModelGatewayTransportException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelGatewayTransportException(
                    "Could not reach Ollama. Make sure `ollama serve` is running. Host: " + baseUrl
                            + " Model: " + model,
                    e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Normalize provider usage into {@link TokenUsage} with tri-state cache
     * status. cache read/create tokens are recorded separately; providers that
     * do not report cache fields stay UNKNOWN (never guessed as miss).
     */
    private TokenUsage usage(Map<?, ?> usage) {
        if (usage == null || usage.isEmpty()) {
            return null;
        }
        Object inputTokens = usage.get("input_tokens");
        Object promptTokens = usage.get("prompt_tokens");
        Object outputTokens = usage.get("output_tokens");
        Object completionTokens = usage.get("completion_tokens");
        Object totalTokens = usage.get("total_tokens");
        // DeepSeek (Anthropic protocol) reports cache fields on usage itself.
        Object cacheRead = usage.get("cache_read_input_tokens");
        Object cacheCreation = usage.get("cache_creation_input_tokens");
        // OpenAI reports them under prompt_tokens_details.cached_tokens.
        if (cacheRead == null && usage.get("prompt_tokens_details") instanceof Map<?, ?> details) {
            cacheRead = details.get("cached_tokens");
        }
        Integer cacheReadTokens = optionalInt(cacheRead);
        Integer cacheCreationTokens = optionalInt(cacheCreation);
        TokenUsage.TokenUsageBuilder builder = TokenUsage.builder()
                .promptTokens(optionalInt(inputTokens != null ? inputTokens : promptTokens))
                .completionTokens(optionalInt(outputTokens != null ? outputTokens : completionTokens))
                .totalTokens(optionalInt(totalTokens))
                .promptCacheHitTokens(cacheReadTokens)
                .promptCacheMissTokens(cacheCreationTokens);
        if (cacheReadTokens != null && cacheReadTokens > 0) {
            builder.cacheHit(true);
        }
        // capability classification: only providers that actually returned
        // cache fields are marked cache-capable; Anthropic/Ollama stay
        // unsupported unless their usage carries the fields.
        builder.cacheCapability(cacheReadTokens != null || cacheCreationTokens != null
                ? "supported" : "unsupported");
        builder.cacheStatus(PromptCacheStatus.fromUsage(builder.build()));
        return builder.build();
    }

    private static Integer optionalInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String normalizeBaseUrl(String raw) {
        String base = raw == null ? "" : raw.strip();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    public static class ModelGatewayTransportException extends RuntimeException {
        public ModelGatewayTransportException(String message) {
            super(message);
        }

        public ModelGatewayTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
