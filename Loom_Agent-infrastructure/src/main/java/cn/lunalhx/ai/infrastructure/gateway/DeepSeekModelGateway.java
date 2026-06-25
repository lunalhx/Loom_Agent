package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Component
public class DeepSeekModelGateway implements ModelGateway {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final ModelRuntimeProperties runtimeProperties;
    private final ThreadPoolExecutor executor;
    private final HttpClient httpClient;

    public DeepSeekModelGateway(Environment environment,
                                ObjectMapper objectMapper,
                                ModelRuntimeProperties runtimeProperties,
                                ThreadPoolExecutor executor) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.runtimeProperties = runtimeProperties;
        this.executor = executor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(runtimeProperties.getConnectTimeoutMs()))
                .executor(executor)
                .build();
    }

    @Override
    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
        return Flux.create(sink -> executor.execute(() -> executeStream(prompt, sink)), FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
        return Mono.fromCallable(() -> executeComplete(prompt))
                .subscribeOn(Schedulers.fromExecutor(executor));
    }

    private void executeStream(ChatPrompt prompt, FluxSink<ModelStreamChunk> sink) {
        try {
            String apiKey = apiKey();
            if (StringUtils.isBlank(apiKey)) {
                sink.error(new ModelGatewayException(ModelErrorCode.CONFIG_ERROR, "DEEPSEEK_API_KEY 不能为空", false, null, null));
                return;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(requestTimeout(prompt))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(toRequestBody(prompt, true), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                sink.error(toHttpException(response.statusCode(), responseBody, response.headers(), resolvedModel(prompt)));
                return;
            }
            consumeSse(response.body(), sink);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.error(interruptedException(e));
        } catch (IOException e) {
            sink.error(new ModelGatewayException(ModelErrorCode.PROVIDER_UNAVAILABLE, "模型服务网络异常", true, null, e));
        } catch (Exception e) {
            sink.error(new ModelGatewayException(ModelErrorCode.MODEL_ERROR, ModelErrorCode.MODEL_ERROR.message(), false, null, e));
        }
    }

    private ModelChatResult executeComplete(ChatPrompt prompt) throws IOException {
        String apiKey = apiKey();
        if (StringUtils.isBlank(apiKey)) {
            throw new ModelGatewayException(ModelErrorCode.CONFIG_ERROR, "DEEPSEEK_API_KEY 不能为空", false, null, null);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .timeout(requestTimeout(prompt))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(toRequestBody(prompt, false), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interruptedException(e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw toHttpException(response.statusCode(), response.body(), response.headers(), resolvedModel(prompt));
        }
        return parseChatResult(response.body());
    }

    private ModelGatewayException interruptedException(InterruptedException cause) {
        return new ModelGatewayException(
                ModelErrorCode.MODEL_ERROR, "模型调用线程被中断", false, null, cause);
    }

    private void consumeSse(InputStream inputStream, FluxSink<ModelStreamChunk> sink) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.isCancelled()) {
                    return;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (StringUtils.isBlank(data)) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    sink.complete();
                    return;
                }
                ModelStreamChunk chunk = parseChunk(data);
                if (chunk != null) {
                    sink.next(chunk);
                }
            }
            sink.complete();
        }
    }

    private ModelStreamChunk parseChunk(String data) throws IOException {
        JsonNode root = objectMapper.readTree(data);
        JsonNode choices = root.path("choices");
        JsonNode usageNode = root.path("usage");
        TokenUsage usage = parseUsage(usageNode);

        if (!choices.isArray() || choices.size() == 0) {
            return usage == null ? null : ModelStreamChunk.builder().usage(usage).build();
        }

        JsonNode choice = choices.get(0);
        String content = textOrNull(choice.path("delta").path("content"));
        String finishReason = textOrNull(choice.path("finish_reason"));
        if (StringUtils.isEmpty(content) && StringUtils.isBlank(finishReason) && usage == null) {
            return null;
        }
        return ModelStreamChunk.builder()
                .content(content)
                .finishReason(finishReason)
                .usage(usage)
                .build();
    }

    private TokenUsage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        return TokenUsage.builder()
                .promptTokens(integerOrNull(usageNode.path("prompt_tokens")))
                .completionTokens(integerOrNull(usageNode.path("completion_tokens")))
                .totalTokens(integerOrNull(usageNode.path("total_tokens")))
                .build();
    }

    private ModelChatResult parseChatResult(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_ERROR, "模型响应缺少 choices", false, null, null);
        }
        JsonNode choice = choices.get(0);
        return ModelChatResult.builder()
                .content(textOrNull(choice.path("message").path("content")))
                .finishReason(textOrNull(choice.path("finish_reason")))
                .usage(parseUsage(root.path("usage")))
                .actualModel(textOrNull(root.path("model")))
                .build();
    }

    private String toRequestBody(ChatPrompt prompt, boolean stream) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolvedModel(prompt));
        body.put("messages", messages(prompt));
        body.put("stream", stream);
        if (stream) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        body.put("temperature", prompt.getTemperature() == null ? defaultTemperature() : prompt.getTemperature());
        body.put("max_tokens", prompt.getMaxTokens() == null ? defaultMaxTokens() : prompt.getMaxTokens());
        if (OutputFormat.JSON_OBJECT == prompt.getOutputFormat()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return objectMapper.writeValueAsString(body);
    }

    private List<Map<String, String>> messages(ChatPrompt prompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        String systemPrompt = prompt.getSystemPrompt();
        if (OutputFormat.JSON_OBJECT == prompt.getOutputFormat()) {
            String jsonInstruction = "请只输出一个合法 JSON 对象，不要使用 Markdown 代码块，也不要输出 JSON 之外的解释文字。";
            systemPrompt = StringUtils.isBlank(systemPrompt) ? jsonInstruction : systemPrompt + "\n" + jsonInstruction;
        }
        if (StringUtils.isNotBlank(systemPrompt)) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        if (prompt.getMessages() != null && !prompt.getMessages().isEmpty()) {
            for (ChatMessage message : prompt.getMessages()) {
                if (message != null && StringUtils.isNotBlank(message.getRole())
                        && StringUtils.isNotBlank(message.getContent())) {
                    messages.add(Map.of("role", message.getRole(), "content", message.getContent()));
                }
            }
            return messages;
        }
        messages.add(Map.of("role", "user", "content", prompt.getMessage()));
        return messages;
    }

    private ModelGatewayException toHttpException(int statusCode,
                                                  String responseBody,
                                                  HttpHeaders headers,
                                                  String model) {
        ModelErrorCode errorCode;
        boolean retryable = false;
        String providerMessage = extractErrorMessage(responseBody);
        if (statusCode == 401) {
            errorCode = ModelErrorCode.AUTHENTICATION_FAILED;
        } else if (statusCode == 402) {
            errorCode = ModelErrorCode.INSUFFICIENT_BALANCE;
        } else if (statusCode == 429) {
            errorCode = ModelErrorCode.RATE_LIMITED;
            retryable = true;
        } else if (statusCode == 400) {
            errorCode = isContextOverflowMessage(providerMessage)
                    ? ModelErrorCode.CONTEXT_OVERFLOW
                    : ModelErrorCode.BAD_REQUEST;
        } else if (statusCode == 422) {
            errorCode = isContextOverflowMessage(providerMessage)
                    ? ModelErrorCode.CONTEXT_OVERFLOW
                    : ModelErrorCode.INVALID_PARAMETER;
        } else if (statusCode == 503 || statusCode == 529) {
            errorCode = ModelErrorCode.PROVIDER_OVERLOADED;
            retryable = true;
        } else if (statusCode == 500) {
            errorCode = ModelErrorCode.PROVIDER_UNAVAILABLE;
            retryable = true;
        } else {
            errorCode = ModelErrorCode.MODEL_ERROR;
        }
        String message = StringUtils.defaultIfBlank(providerMessage, errorCode.message());
        log.warn("DeepSeek API returned status {}, model={}, errorCode={}, message={}",
                statusCode, model, errorCode.code(), message);
        return new ModelGatewayException(errorCode, message, retryable, statusCode,
                retryAfterMs(headers), model, null);
    }

    private boolean isContextOverflowMessage(String message) {
        String normalized = StringUtils.lowerCase(message);
        return StringUtils.contains(normalized, "context length")
                || StringUtils.contains(normalized, "prompt too long")
                || StringUtils.contains(normalized, "prompt_too_long")
                || StringUtils.contains(normalized, "tokens exceed")
                || StringUtils.contains(normalized, "context_length_exceeded")
                || StringUtils.contains(normalized, "too many tokens");
    }

    private Long retryAfterMs(HttpHeaders headers) {
        String value = headers == null ? null : headers.firstValue("Retry-After").orElse(null);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()) * 1000L);
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0L, Duration.between(Instant.now(), retryAt).toMillis());
            } catch (Exception ignoredDate) {
                return null;
            }
        }
    }

    private Duration requestTimeout(ChatPrompt prompt) {
        long configured = Math.max(1L, runtimeProperties.getStreamTimeoutMs());
        if (prompt.getDeadlineEpochMs() == null) {
            return Duration.ofMillis(configured);
        }
        long remaining = Math.max(1L, prompt.getDeadlineEpochMs() - System.currentTimeMillis());
        return Duration.ofMillis(Math.min(configured, remaining));
    }

    private String resolvedModel(ChatPrompt prompt) {
        return StringUtils.defaultIfBlank(prompt.getModel(), defaultModel());
    }

    private String extractErrorMessage(String responseBody) {
        if (StringUtils.isBlank(responseBody)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String message = textOrNull(root.path("error").path("message"));
            return StringUtils.abbreviate(message, 300);
        } catch (Exception ignored) {
            return StringUtils.abbreviate(responseBody, 300);
        }
    }

    private String endpoint() {
        String baseUrl = environment.getProperty("spring.ai.deepseek.chat.base-url",
                environment.getProperty("spring.ai.deepseek.base-url", "https://api.deepseek.com"));
        return StringUtils.removeEnd(baseUrl, "/") + CHAT_COMPLETIONS_PATH;
    }

    private String apiKey() {
        return environment.getProperty("spring.ai.deepseek.chat.api-key",
                environment.getProperty("spring.ai.deepseek.api-key", ""));
    }

    private String defaultModel() {
        return environment.getProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash");
    }

    private Double defaultTemperature() {
        return environment.getProperty("spring.ai.deepseek.chat.temperature", Double.class, 0.2D);
    }

    private Integer defaultMaxTokens() {
        return environment.getProperty("spring.ai.deepseek.chat.max-tokens", Integer.class, 2048);
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private Integer integerOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asInt();
    }

}
