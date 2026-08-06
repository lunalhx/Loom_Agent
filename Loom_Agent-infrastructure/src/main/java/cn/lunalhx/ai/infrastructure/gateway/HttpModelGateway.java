package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
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
 * <p>OpenAI/Anthropic-compatible requests retry network errors and 5xx up to
 * three times with 0.5/1s backoff; ollama stays single-shot.
 */
public class HttpModelGateway implements ModelGateway {

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

    ModelChatResult execute(ChatPrompt prompt) {
        String text = flattenPrompt(prompt);
        int maxTokens = prompt.getMaxTokens() == null ? 512 : prompt.getMaxTokens();

        switch (provider) {
            case "ollama":
                return callOllama(text, maxTokens);
            case "openai":
                return callOpenAI(text, maxTokens);
            case "anthropic":
            case "deepseek":
            default:
                return callAnthropic(text, maxTokens);
        }
    }

    // ==================== Anthropic Messages ====================

    private ModelChatResult callAnthropic(String text, int maxTokens) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", maxTokens);
        payload.put("stream", false);
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        if (topP != null) {
            payload.put("top_p", topP);
        }
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(Map.of("type", "text", "text", text)));
        payload.put("messages", List.of(message));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey == null ? "" : apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
                .build();

        String body = sendWithRetry(request, "Anthropic-compatible");
        Map<String, Object> data = parseJson(body);
        String error = asText(data.get("error"));
        if (error != null) {
            throw new ModelGatewayTransportException("Anthropic-compatible error: " + error);
        }
        Object content = data.get("content");
        if (content instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> entry
                        && "text".equals(entry.get("type"))
                        && asText(entry.get("text")) != null) {
                    Map<?, ?> usage = asMap(data.get("usage"));
                    return ModelChatResult.builder()
                            .content(asText(entry.get("text")))
                            .finishReason("stop")
                            .actualModel(model)
                            .usage(usage(usage))
                            .build();
                }
            }
        }
        throw new ModelGatewayTransportException("Anthropic-compatible error: could not extract text from response");
    }

    // ==================== OpenAI Responses ====================

    private ModelChatResult callOpenAI(String text, int maxTokens) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of("type", "input_text", "text", text)))));
        payload.put("max_output_tokens", maxTokens);
        payload.put("stream", false);
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        if (topP != null) {
            payload.put("top_p", topP);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/responses"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "loom-code/0.1")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        String body = sendWithRetry(builder.build(), "OpenAI-compatible");
        Map<String, Object> data = parseJson(body);
        String error = asText(data.get("error"));
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
                .usage(usage(asMap(data.get("usage"))))
                .build();
    }

    private String extractOpenAIText(Map<String, Object> data) {
        String outputText = asText(data.get("output_text"));
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
                    String text = asText(entry.get("text"));
                    if (text != null) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    // ==================== Ollama ====================

    private ModelChatResult callOllama(String text, int maxTokens) {
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("num_predict", maxTokens);
        if (temperature != null) {
            options.put("temperature", temperature);
        }
        if (topP != null) {
            options.put("top_p", topP);
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", model);
        payload.put("prompt", text);
        payload.put("stream", false);
        payload.put("raw", false);
        payload.put("think", false);
        payload.put("options", options);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
                .build();

        String body = sendOnce(request, "Ollama");
        Map<String, Object> data = parseJson(body);
        String error = asText(data.get("error"));
        if (error != null) {
            throw new ModelGatewayTransportException("Ollama error: " + error);
        }
        return ModelChatResult.builder()
                .content(asText(data.get("response")) == null ? "" : asText(data.get("response")))
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

    private static String flattenPrompt(ChatPrompt prompt) {
        StringBuilder sb = new StringBuilder();
        if (prompt.getSystemPrompt() != null && !prompt.getSystemPrompt().isBlank()) {
            sb.append(prompt.getSystemPrompt()).append("\n\n");
        }
        if (prompt.getMessages() != null) {
            for (ChatMessage message : prompt.getMessages()) {
                if (message != null && message.getContent() != null && !message.getContent().isBlank()) {
                    sb.append(message.getContent()).append("\n\n");
                }
            }
        }
        if (prompt.getMessage() != null && !prompt.getMessage().isBlank()) {
            sb.append(prompt.getMessage());
        }
        return sb.toString().strip();
    }

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

    private static String toJson(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            sb.append(jsonValue(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String jsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(jsonValue(String.valueOf(e.getKey()))).append(':')
                        .append(jsonValue(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(jsonValue(item));
            }
            return sb.append(']').toString();
        }
        return jsonValue(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
        } catch (Exception e) {
            throw new ModelGatewayTransportException(
                    "backend returned non-JSON content that could not be parsed: " + body);
        }
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
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
