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
import cn.lunalhx.ai.infrastructure.gateway.diagnostics.PromptCacheDiagnosticContext;
import cn.lunalhx.ai.infrastructure.gateway.diagnostics.PromptCacheDiagnosticHook;
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
    private final PromptCacheDiagnosticHook diagnosticHook;

    public DeepSeekModelGateway(Environment environment,
                                ObjectMapper objectMapper,
                                ModelRuntimeProperties runtimeProperties,
                                ThreadPoolExecutor executor) {
        this(environment, objectMapper, runtimeProperties, executor,
                new PromptCacheDiagnosticHook(null));
    }

    public DeepSeekModelGateway(Environment environment,
                                ObjectMapper objectMapper,
                                ModelRuntimeProperties runtimeProperties,
                                ThreadPoolExecutor executor,
                                PromptCacheDiagnosticHook diagnosticHook) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.runtimeProperties = runtimeProperties;
        this.executor = executor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(runtimeProperties.getConnectTimeoutMs()))
                .executor(executor)
                .build();
        // 诊断默认关闭：未启用时 hook.beforeSend / afterSend 都是 fast-return，不分配对象。
        this.diagnosticHook = diagnosticHook != null
                ? diagnosticHook
                : new PromptCacheDiagnosticHook(null);
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
        String apiKey = apiKey();
        if (StringUtils.isBlank(apiKey)) {
            sink.error(new ModelGatewayException(ModelErrorCode.CONFIG_ERROR, "DEEPSEEK_API_KEY 不能为空", false, null, null));
            return;
        }

        // 1) 序列化 body 一次：buildRequestPayload 同时返回 body 字符串和 messages 列表
        RequestPayload requestPayload;
        try {
            requestPayload = buildRequestPayload(prompt, true);
        } catch (IOException e) {
            sink.error(new ModelGatewayException(ModelErrorCode.MODEL_ERROR,
                    ModelErrorCode.MODEL_ERROR.defaultMessage(), false, null, e));
            return;
        }

        // 2) 诊断前置：与 BodyPublisher 共享同一字符串引用，绝不二次序列化
        String resolvedModel = resolvedModel(prompt);
        PromptCacheDiagnosticContext diagnosticContext = diagnosticHook.beforeSend(
                resolvedModel,
                prompt.getCapability(),
                prompt.getPurpose() == null ? null : prompt.getPurpose().name(),
                prompt.getConversationId(),
                requestPayload.body(),
                requestPayload.messages());
        TokenUsage lastUsage = null;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(requestTimeout(prompt))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestPayload.body(), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                sink.error(toHttpException(response.statusCode(), responseBody, response.headers(), resolvedModel));
                return;
            }
            lastUsage = consumeSse(response.body(), sink);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sink.error(interruptedException(e));
        } catch (IOException e) {
            sink.error(new ModelGatewayException(ModelErrorCode.PROVIDER_UNAVAILABLE, "模型服务网络异常", true, null, e));
        } catch (Exception e) {
            sink.error(new ModelGatewayException(ModelErrorCode.MODEL_ERROR, ModelErrorCode.MODEL_ERROR.defaultMessage(), false, null, e));
        } finally {
            // 3) 诊断后置：不论成功 / 4xx / IOException / 中断，都写出诊断日志
            // 失败时 lastUsage 为 null，符合「usage 缺失 = provider 未声明」的语义
            diagnosticHook.afterSend(diagnosticContext, lastUsage);
        }
    }

    private ModelChatResult executeComplete(ChatPrompt prompt) throws IOException {
        String apiKey = apiKey();
        if (StringUtils.isBlank(apiKey)) {
            throw new ModelGatewayException(ModelErrorCode.CONFIG_ERROR, "DEEPSEEK_API_KEY 不能为空", false, null, null);
        }
        // 1) 序列化 body 一次
        RequestPayload requestPayload = buildRequestPayload(prompt, false);
        // 2) 诊断前置：与 BodyPublisher 共享同一字符串引用
        String resolvedModel = resolvedModel(prompt);
        PromptCacheDiagnosticContext diagnosticContext = diagnosticHook.beforeSend(
                resolvedModel,
                prompt.getCapability(),
                prompt.getPurpose() == null ? null : prompt.getPurpose().name(),
                prompt.getConversationId(),
                requestPayload.body(),
                requestPayload.messages());
        TokenUsage usage = null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint()))
                    .timeout(requestTimeout(prompt))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestPayload.body(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw interruptedException(e);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw toHttpException(response.statusCode(), response.body(), response.headers(), resolvedModel);
            }
            ModelChatResult result = parseChatResult(response.body());
            usage = result == null ? null : result.getUsage();
            return result;
        } finally {
            // 3) 诊断后置：成功路径下带 usage，失败路径下 usage 为 null
            diagnosticHook.afterSend(diagnosticContext, usage);
        }
    }

    private ModelGatewayException interruptedException(InterruptedException cause) {
        return new ModelGatewayException(
                ModelErrorCode.MODEL_ERROR, "模型调用线程被中断", false, null, cause);
    }

    private TokenUsage consumeSse(InputStream inputStream, FluxSink<ModelStreamChunk> sink) throws IOException {
        // DeepSeek 在 stream_options.include_usage=true 时把 usage 放在最后一个 SSE 事件上。
        // 收集「最后一次出现的 usage」以供诊断日志关联：最后一次非 null 用法代表整次流式调用的
        // 最终 cache usage（hit / miss 反映 provider 侧对本次 prompt 的实际判定）。
        TokenUsage latestUsage = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.isCancelled()) {
                    return latestUsage;
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
                    return latestUsage;
                }
                ModelStreamChunk chunk = parseChunk(data);
                if (chunk != null) {
                    if (chunk.getUsage() != null) {
                        latestUsage = chunk.getUsage();
                    }
                    sink.next(chunk);
                }
            }
            sink.complete();
        }
        return latestUsage;
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
                .promptCacheHitTokens(integerOrNull(usageNode.path("prompt_cache_hit_tokens")))
                .promptCacheMissTokens(integerOrNull(usageNode.path("prompt_cache_miss_tokens")))
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
        // 仅作为「对测试可见、只关心 body 字符串」的便捷方法存在；生产代码走 buildRequestPayload
        // 以同时拿到 messages（诊断需要）。
        return buildRequestPayload(prompt, stream).body();
    }

    /**
     * 构造一次 DeepSeek 请求：单次序列化得到 body JSON，并同时返回实际进入 body 的 messages。
     *
     * <p>关键不变量：{@code body} 字符串会被原样传给 {@code HttpRequest.BodyPublishers.ofString(...)}
     * 与 {@link PromptCacheDiagnosticHook#beforeSend}，绝不被二次序列化；后者在接收端做
     * SHA-256 哈希即可与线上发送内容严格对齐。
     */
    private RequestPayload buildRequestPayload(ChatPrompt prompt, boolean stream) throws IOException {
        List<Map<String, String>> messageList = messages(prompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", resolvedModel(prompt));
        body.put("messages", messageList);
        body.put("stream", stream);
        if (stream) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        body.put("temperature", prompt.getTemperature() == null ? defaultTemperature() : prompt.getTemperature());
        body.put("max_tokens", prompt.getMaxTokens() == null ? defaultMaxTokens() : prompt.getMaxTokens());
        if (OutputFormat.JSON_OBJECT == prompt.getOutputFormat()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        String json = objectMapper.writeValueAsString(body);
        return new RequestPayload(json, messageList);
    }

    /**
     * 一次请求的「最终 payload」：body 字符串 + 实际进入 body 的 messages 列表。
     * 两者是同一序列化的产物，body 字符串可被安全地直接传给 BodyPublisher。
     */
    private record RequestPayload(String body, List<Map<String, String>> messages) {
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
        String message = StringUtils.defaultIfBlank(providerMessage, errorCode.defaultMessage());
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
