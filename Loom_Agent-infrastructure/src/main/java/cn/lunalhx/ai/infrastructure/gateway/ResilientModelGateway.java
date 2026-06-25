package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.service.ModelCallTraceContext;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelCapability;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Primary
@Component
public class ResilientModelGateway implements ModelGateway {

    private static final String PROVIDER = "deepseek";
    private final ModelGateway delegate;
    private final ModelRuntimeProperties properties;
    private final TraceRecorder traceRecorder;
    private final MeterRegistry meterRegistry;
    private final Environment environment;
    private final BudgetGuard budgetGuard;
    private final CircuitBreakerRegistry circuitBreakers;
    private final Map<String, AtomicInteger> circuitStateGauges = new ConcurrentHashMap<>();

    @Autowired
    public ResilientModelGateway(@Qualifier("deepSeekModelGateway") ModelGateway delegate,
                                 ModelRuntimeProperties properties,
                                 TraceRecorder traceRecorder,
                                 MeterRegistry meterRegistry,
                                 Environment environment,
                                 BudgetGuard budgetGuard) {
        this.delegate = delegate;
        this.properties = properties;
        this.traceRecorder = traceRecorder;
        this.meterRegistry = meterRegistry;
        this.environment = environment;
        this.budgetGuard = budgetGuard;
        this.circuitBreakers = CircuitBreakerRegistry.of(circuitBreakerConfig());
    }

    public ResilientModelGateway(ModelGateway delegate,
                                 ModelRuntimeProperties properties,
                                 TraceRecorder traceRecorder,
                                 MeterRegistry meterRegistry,
                                 Environment environment) {
        this(delegate, properties, traceRecorder, meterRegistry, environment, null);
    }

    @Override
    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
        ChatPrompt normalized = normalize(prompt, ModelCapabilities.COMPLETE_AGENT_DECISION);
        ResilienceKey key = key(normalized);
        AgentContext context = ModelCallTraceContext.current();
        if (!enabled()) {
            return delegate.complete(normalized);
        }
        return completeAttempt(normalized, key, context, 1, 0, null);
    }

    @Override
    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
        ChatPrompt normalized = normalize(prompt, ModelCapabilities.STREAM_CHAT);
        ResilienceKey key = key(normalized);
        AgentContext context = ModelCallTraceContext.current();
        if (!enabled()) {
            return delegate.stream(normalized);
        }
        AtomicBoolean tokenEmitted = new AtomicBoolean(false);
        return streamAttempt(normalized, key, context, tokenEmitted, 1, 0);
    }

    @Override
    public ModelCapability capability(String model) {
        String resolved = StringUtils.defaultIfBlank(model,
                environment.getProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"));
        return properties.capability(resolved);
    }

    private Mono<ModelChatResult> completeAttempt(ChatPrompt prompt,
                                                  ResilienceKey key,
                                                  AgentContext context,
                                                  int attemptNo,
                                                  int consecutiveOverload,
                                                  String fallbackReason) {
        ModelGatewayException preflight = preflight(prompt, key.model(), context);
        if (preflight != null) {
            return Mono.error(preflight);
        }
        CircuitBreaker circuitBreaker = circuitBreaker(key);
        CircuitBreaker.State beforeState = circuitBreaker.getState();
        if (!circuitBreaker.tryAcquirePermission()) {
            CallNotPermittedException error = CallNotPermittedException.createCallNotPermittedException(circuitBreaker);
            recordGatewayEvent(context, "circuit_open", key, "blocked", 0L, attemptNo, error, "circuit is open");
            recordCircuit(key, "OPEN");
            recordModelCall(key, "error", "circuit_open", 0L);
            return Mono.error(error);
        }

        long startedAt = System.currentTimeMillis();
        return delegate.complete(prompt)
                .flatMap(result -> isInsufficientSystemResource(result == null ? null : result.getFinishReason())
                        ? Mono.error(overloaded(key.model(), "insufficient_system_resource"))
                        : Mono.just(result))
                .doOnSuccess(result -> {
                    long durationMs = elapsed(startedAt);
                    if (StringUtils.isBlank(result.getActualModel())) {
                        result.setActualModel(key.model());
                    }
                    result.setFallbackReason(fallbackReason);
                    circuitBreaker.onSuccess(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                    recordAttempt(context, key, "success", durationMs, attemptNo, null);
                    recordModelCall(key, "success", "none", durationMs);
                    recordStateChange(context, key, beforeState, circuitBreaker.getState(), attemptNo, null);
                })
                .onErrorResume(error -> {
                    long durationMs = elapsed(startedAt);
                    Throwable unwrapped = unwrap(error);
                    attachModel(unwrapped, key.model());
                    boolean retryable = isRetryable(unwrapped);
                    if (retryable) {
                        circuitBreaker.onError(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS, unwrapped);
                    } else {
                        circuitBreaker.releasePermission();
                    }
                    recordAttempt(context, key, "error", durationMs, attemptNo, unwrapped);
                    recordModelCall(key, "error", errorCode(unwrapped), durationMs);
                    recordStateChange(context, key, beforeState, circuitBreaker.getState(), attemptNo, unwrapped);
                    if (!retryable || attemptNo >= maxAttempts()) {
                        if (retryable) {
                            recordGatewayEvent(context, "model_retry_exhausted", key, "error", durationMs, attemptNo,
                                    unwrapped, "retry exhausted");
                        }
                        return Mono.error(unwrapped);
                    }
                    int nextOverload = isOverload(unwrapped) ? consecutiveOverload + 1 : 0;
                    String nextFallbackReason = fallbackReason;
                    ChatPrompt nextPrompt = prompt;
                    ResilienceKey nextKey = key;
                    if (shouldFallback(key.model(), nextOverload)) {
                        String fallbackModel = resilience().getFallbackModel();
                        ModelGatewayException budgetError = fallbackBudgetError(context, prompt, fallbackModel);
                        if (budgetError != null) {
                            return Mono.error(budgetError);
                        }
                        nextFallbackReason = errorCode(unwrapped);
                        nextPrompt = withModel(prompt, fallbackModel);
                        nextKey = key(nextPrompt);
                        traceFallback(context, key.model(), fallbackModel, nextFallbackReason, attemptNo);
                        nextOverload = 0;
                    }
                    long delayMs = retryDelayMs(attemptNo, unwrapped);
                    if (!canWait(prompt, delayMs)) {
                        return Mono.error(deadlineExceeded(key.model()));
                    }
                    recordRetry(key, "scheduled", errorCode(unwrapped));
                    ChatPrompt retryPrompt = nextPrompt;
                    ResilienceKey retryKey = nextKey;
                    int retryOverload = nextOverload;
                    String retryFallbackReason = nextFallbackReason;
                    return Mono.delay(Duration.ofMillis(delayMs))
                            .then(Mono.defer(() -> completeAttempt(retryPrompt, retryKey, context, attemptNo + 1,
                                    retryOverload, retryFallbackReason)));
                });
    }

    private Flux<ModelStreamChunk> streamAttempt(ChatPrompt prompt,
                                                ResilienceKey key,
                                                AgentContext context,
                                                AtomicBoolean tokenEmitted,
                                                int attemptNo,
                                                int consecutiveOverload) {
        ModelGatewayException preflight = preflight(prompt, key.model(), context);
        if (preflight != null) {
            return Flux.error(preflight);
        }
        CircuitBreaker circuitBreaker = circuitBreaker(key);
        CircuitBreaker.State beforeState = circuitBreaker.getState();
        if (!circuitBreaker.tryAcquirePermission()) {
            CallNotPermittedException error = CallNotPermittedException.createCallNotPermittedException(circuitBreaker);
            recordGatewayEvent(context, "circuit_open", key, "blocked", 0L, attemptNo, error, "circuit is open");
            recordCircuit(key, "OPEN");
            recordModelCall(key, "error", "circuit_open", 0L);
            return Flux.error(error);
        }

        long startedAt = System.currentTimeMillis();
        return delegate.stream(prompt)
                .timeout(Duration.ofMillis(Math.min(firstTokenTimeoutMs(), remainingMs(prompt))))
                .concatMap(chunk -> isInsufficientSystemResource(chunk.getFinishReason())
                        ? Mono.error(overloaded(key.model(), "insufficient_system_resource"))
                        : Mono.just(chunk))
                .doOnNext(chunk -> {
                    if (StringUtils.isNotEmpty(chunk.getContent())) {
                        tokenEmitted.set(true);
                    }
                })
                .doOnComplete(() -> {
                    long durationMs = elapsed(startedAt);
                    circuitBreaker.onSuccess(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                    recordAttempt(context, key, "success", durationMs, attemptNo, null);
                    recordModelCall(key, "success", "none", durationMs);
                    recordStateChange(context, key, beforeState, circuitBreaker.getState(), attemptNo, null);
                })
                .onErrorResume(error -> {
                    long durationMs = elapsed(startedAt);
                    Throwable unwrapped = unwrap(error);
                    attachModel(unwrapped, key.model());
                    boolean retryable = !tokenEmitted.get() && isRetryable(unwrapped);
                    if (retryable) {
                        circuitBreaker.onError(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS, unwrapped);
                    } else {
                        circuitBreaker.releasePermission();
                    }
                    recordAttempt(context, key, "error", durationMs, attemptNo, unwrapped);
                    recordModelCall(key, "error", errorCode(unwrapped), durationMs);
                    recordStateChange(context, key, beforeState, circuitBreaker.getState(), attemptNo, unwrapped);
                    if (!retryable || attemptNo >= maxAttempts()) {
                        if (retryable) {
                            recordGatewayEvent(context, "model_retry_exhausted", key, "error", durationMs, attemptNo,
                                    unwrapped, "retry exhausted");
                        }
                        return Flux.error(unwrapped);
                    }
                    int nextOverload = isOverload(unwrapped) ? consecutiveOverload + 1 : 0;
                    ChatPrompt nextPrompt = prompt;
                    ResilienceKey nextKey = key;
                    if (shouldFallback(key.model(), nextOverload)) {
                        String fallbackModel = resilience().getFallbackModel();
                        ModelGatewayException budgetError = fallbackBudgetError(context, prompt, fallbackModel);
                        if (budgetError != null) {
                            return Flux.error(budgetError);
                        }
                        String fallbackReason = errorCode(unwrapped);
                        nextPrompt = withModel(prompt, fallbackModel);
                        nextKey = key(nextPrompt);
                        traceFallback(context, key.model(), fallbackModel, fallbackReason, attemptNo);
                        nextOverload = 0;
                    }
                    long delayMs = retryDelayMs(attemptNo, unwrapped);
                    if (!canWait(prompt, delayMs)) {
                        return Flux.error(deadlineExceeded(key.model()));
                    }
                    recordRetry(key, "scheduled", errorCode(unwrapped));
                    ChatPrompt retryPrompt = nextPrompt;
                    ResilienceKey retryKey = nextKey;
                    int retryOverload = nextOverload;
                    return Mono.delay(Duration.ofMillis(delayMs))
                            .thenMany(Flux.defer(() -> streamAttempt(retryPrompt, retryKey, context, tokenEmitted,
                                    attemptNo + 1, retryOverload)));
                });
    }

    private CircuitBreaker circuitBreaker(ResilienceKey key) {
        return circuitBreakers.circuitBreaker(key.circuitName(), circuitBreakerConfig());
    }

    private CircuitBreakerConfig circuitBreakerConfig() {
        ModelRuntimeProperties.ResilienceProperties resilience = resilience();
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(resilience.getCircuitFailureRateThreshold())
                .slowCallRateThreshold(resilience.getCircuitSlowCallRateThreshold())
                .slowCallDurationThreshold(Duration.ofMillis(resilience.getCircuitSlowCallDurationMs()))
                .slidingWindowSize(resilience.getCircuitSlidingWindowSize())
                .minimumNumberOfCalls(resilience.getCircuitSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofMillis(resilience.getCircuitOpenStateWaitMs()))
                .permittedNumberOfCallsInHalfOpenState(resilience.getCircuitHalfOpenPermittedCalls())
                .recordException(this::isRetryable)
                .build();
    }

    private ChatPrompt normalize(ChatPrompt prompt, String fallback) {
        prompt.setCapability(StringUtils.defaultIfBlank(prompt.getCapability(), fallback));
        prompt.setModel(StringUtils.defaultIfBlank(prompt.getModel(),
                environment.getProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash")));
        if (prompt.getPurpose() == null) {
            prompt.setPurpose(prompt.getOutputFormat() == cn.lunalhx.ai.domain.model.valobj.OutputFormat.JSON_OBJECT
                    ? ModelCallPurpose.CONTROL_JSON
                    : ModelCallPurpose.FINAL_TEXT);
        }
        return prompt;
    }

    private ResilienceKey key(ChatPrompt prompt) {
        String model = StringUtils.defaultIfBlank(prompt.getModel(), environment.getProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"));
        String capability = StringUtils.defaultIfBlank(prompt.getCapability(), ModelCapabilities.STREAM_CHAT);
        return new ResilienceKey(PROVIDER, model, capability);
    }

    private void recordAttempt(AgentContext context,
                               ResilienceKey key,
                               String status,
                               long durationMs,
                               int attemptNo,
                               Throwable error) {
        Map<String, Object> metadata = Map.of(
                "provider", key.provider(),
                "model", key.model(),
                "capability", key.capability(),
                "attemptNo", attemptNo,
                "maxAttempts", maxAttempts(),
                "retryable", error == null || isRetryable(error));
        traceRecorder.recordModelGatewayEvent(context, "model_retry_attempt", key.capability(), status,
                durationMs, "model call attempt " + status, error, metadata);
        recordRetry(key, status, errorCode(error));
    }

    private void recordGatewayEvent(AgentContext context,
                                    String eventType,
                                    ResilienceKey key,
                                    String status,
                                    long durationMs,
                                    int attemptNo,
                                    Throwable error,
                                    String summary) {
        if (context == null) {
            return;
        }
        traceRecorder.recordModelGatewayEvent(context, eventType, key.capability(), status, durationMs, summary, error,
                Map.of("provider", key.provider(),
                        "model", key.model(),
                        "capability", key.capability(),
                        "attemptNo", attemptNo,
                        "maxAttempts", maxAttempts(),
                        "retryable", error == null || isRetryable(error)));
    }

    private void recordStateChange(AgentContext context,
                                   ResilienceKey key,
                                   CircuitBreaker.State before,
                                   CircuitBreaker.State after,
                                   int attemptNo,
                                   Throwable error) {
        if (Objects.equals(before, after)) {
            return;
        }
        recordCircuit(key, after.name());
        recordGatewayEvent(context, "circuit_state_changed", key, after.name().toLowerCase(), 0L, attemptNo,
                error, "circuit state changed from " + before + " to " + after);
    }

    private void recordModelCall(ResilienceKey key, String status, String errorCode, long durationMs) {
        meterRegistry.counter("loom_agent_model_call_total",
                        "model", key.model(),
                        "capability", key.capability(),
                        "status", status,
                        "error_code", safe(errorCode))
                .increment();
        meterRegistry.timer("loom_agent_model_latency_seconds",
                        "model", key.model(),
                        "capability", key.capability(),
                        "status", status)
                .record(Duration.ofMillis(Math.max(0L, durationMs)));
    }

    private void recordRetry(ResilienceKey key, String status, String errorCode) {
        meterRegistry.counter("loom_agent_model_retry_total",
                        "model", key.model(),
                        "capability", key.capability(),
                        "status", safe(status),
                        "error_code", safe(errorCode))
                .increment();
    }

    private void recordCircuit(ResilienceKey key, String state) {
        meterRegistry.counter("loom_agent_circuit_event_total",
                        "model", key.model(),
                        "capability", key.capability(),
                        "state", safe(state))
                .increment();
        for (CircuitBreaker.State candidate : CircuitBreaker.State.values()) {
            AtomicInteger value = circuitGauge(key, candidate.name());
            value.set(candidate.name().equals(state) ? 1 : 0);
        }
    }

    private AtomicInteger circuitGauge(ResilienceKey key, String state) {
        String gaugeKey = key.model() + "|" + key.capability() + "|" + state;
        return circuitStateGauges.computeIfAbsent(gaugeKey, ignored -> {
            AtomicInteger value = new AtomicInteger(0);
            meterRegistry.gauge("loom_agent_circuit_state",
                    Tags.of("model", key.model(), "capability", key.capability(), "state", state),
                    value);
            return value;
        });
    }

    private boolean isRetryable(Throwable throwable) {
        Throwable error = unwrap(throwable);
        if (error instanceof ModelGatewayException exception) {
            if (isNonRetryable(exception)) {
                return false;
            }
            return exception.isRetryable();
        }
        return error instanceof TimeoutException
                || error instanceof HttpTimeoutException
                || error instanceof ConnectException
                || error instanceof IOException;
    }

    private boolean isNonRetryable(ModelGatewayException exception) {
        if (exception.getHttpStatus() != null
                && (exception.getHttpStatus() == 401
                || exception.getHttpStatus() == 402
                || exception.getHttpStatus() == 422)) {
            return true;
        }
        ModelErrorCode code = exception.getErrorCode();
        return code == ModelErrorCode.CONFIG_ERROR
                || code == ModelErrorCode.INVALID_REQUEST
                || code == ModelErrorCode.BAD_REQUEST
                || code == ModelErrorCode.INVALID_PARAMETER
                || code == ModelErrorCode.CONTEXT_OVERFLOW
                || code == ModelErrorCode.MODEL_CAPABILITY_MISMATCH
                || code == ModelErrorCode.MODEL_CALL_TIMEOUT
                || code == ModelErrorCode.AUTHENTICATION_FAILED
                || code == ModelErrorCode.INSUFFICIENT_BALANCE;
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof RuntimeException && error.getCause() != null
                && "reactor.core.Exceptions$ReactiveException".equals(error.getClass().getName())) {
            return error.getCause();
        }
        return error;
    }

    private void attachModel(Throwable error, String model) {
        if (error instanceof ModelGatewayException exception && StringUtils.isBlank(exception.getModel())) {
            exception.setModel(model);
        }
    }

    private String errorCode(Throwable error) {
        if (error instanceof ModelGatewayException exception && exception.getErrorCode() != null) {
            return exception.getErrorCode().code();
        }
        if (error instanceof CallNotPermittedException) {
            return "circuit_open";
        }
        return error == null ? "none" : error.getClass().getSimpleName();
    }

    private long retryDelayMs(int attemptNo, Throwable error) {
        if (error instanceof ModelGatewayException exception && exception.getRetryAfterMs() != null) {
            return Math.max(0L, exception.getRetryAfterMs());
        }
        long base = Math.min(backoffMaxMs(), backoffInitialMs() * (1L << Math.min(20, Math.max(0, attemptNo - 1))));
        long jitter = Math.round(base * 0.25D);
        long delta = jitter <= 0 ? 0 : ThreadLocalRandom.current().nextLong(0, jitter + 1);
        return Math.max(0L, base + delta);
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(resilience().getEnabled());
    }

    private ModelRuntimeProperties.ResilienceProperties resilience() {
        if (properties.getResilience() == null) {
            properties.setResilience(new ModelRuntimeProperties.ResilienceProperties());
        }
        return properties.getResilience();
    }

    private int maxAttempts() {
        return Math.max(1, resilience().getRetryMaxAttempts());
    }

    private long backoffInitialMs() {
        return Math.max(1L, resilience().getRetryBackoffInitialMs());
    }

    private long backoffMaxMs() {
        return Math.max(backoffInitialMs(), resilience().getRetryBackoffMaxMs());
    }

    private long firstTokenTimeoutMs() {
        return Math.max(1L, properties.getFirstTokenTimeoutMs());
    }

    private ModelGatewayException preflight(ChatPrompt prompt, String model, AgentContext context) {
        if (remainingMs(prompt) <= 0) {
            return deadlineExceeded(model);
        }
        ModelCapability capability;
        try {
            capability = properties.capability(model);
        } catch (ModelGatewayException e) {
            return e;
        }
        int requestedMaxTokens = prompt.getMaxTokens() == null
                ? environment.getProperty("spring.ai.deepseek.chat.max-tokens", Integer.class, 2048)
                : prompt.getMaxTokens();
        long estimatedPromptTokens = Math.max(1L,
                StringUtils.length(prompt.getMessage()) / 4L
                        + StringUtils.length(prompt.getSystemPrompt()) / 4L
                        + (prompt.getMessages() == null ? 0L : prompt.getMessages().stream()
                        .mapToLong(message -> StringUtils.length(message.getContent()) / 4L).sum()));
        if (requestedMaxTokens > capability.getMaxOutputTokens()) {
            return new ModelGatewayException(ModelErrorCode.MODEL_CAPABILITY_MISMATCH,
                    "模型输出能力不足：model=" + model + ", requestedMaxTokens=" + requestedMaxTokens
                            + ", maxOutputTokens=" + capability.getMaxOutputTokens(),
                    false, null, null);
        }
        if (estimatedPromptTokens + requestedMaxTokens > capability.getContextLength()) {
            return new ModelGatewayException(ModelErrorCode.CONTEXT_OVERFLOW,
                    "模型上下文长度超限：model=" + model + ", promptTokens=" + estimatedPromptTokens
                            + ", requestedMaxTokens=" + requestedMaxTokens
                            + ", contextLength=" + capability.getContextLength(),
                    false, null, null, model, null);
        }
        if (prompt.getPurpose() == ModelCallPurpose.CONTROL_JSON
                && !Boolean.TRUE.equals(capability.getSupportsJsonOutput())) {
            return new ModelGatewayException(ModelErrorCode.MODEL_CAPABILITY_MISMATCH,
                    "模型不支持 JSON 输出：" + model, false, null, null);
        }
        if (prompt.getPurpose() == ModelCallPurpose.CONTROL_JSON
                && prompt.getOutputFormat() != cn.lunalhx.ai.domain.model.valobj.OutputFormat.JSON_OBJECT) {
            return new ModelGatewayException(ModelErrorCode.MODEL_CAPABILITY_MISMATCH,
                    "CONTROL_JSON 必须使用 JSON_OBJECT 输出格式", false, null, null);
        }
        if (ModelCapabilities.COMPLETE_AGENT_DECISION.equals(prompt.getCapability())
                && !Boolean.TRUE.equals(capability.getSupportsToolCalls())) {
            return new ModelGatewayException(ModelErrorCode.MODEL_CAPABILITY_MISMATCH,
                    "模型不支持 Agent 工具决策：" + model, false, null, null);
        }
        ModelGatewayException budgetError = budgetError(context, prompt, model, requestedMaxTokens);
        if (budgetError != null) {
            return budgetError;
        }
        return null;
    }

    private boolean shouldFallback(String currentModel, int consecutiveOverload) {
        String fallbackModel = resilience().getFallbackModel();
        return consecutiveOverload >= Math.max(1, resilience().getOverloadFallbackThreshold())
                && StringUtils.isNotBlank(fallbackModel)
                && !StringUtils.equals(currentModel, fallbackModel)
                && properties.getAllowedModels().contains(fallbackModel);
    }

    private boolean isOverload(Throwable error) {
        return error instanceof ModelGatewayException exception
                && exception.getErrorCode() == ModelErrorCode.PROVIDER_OVERLOADED;
    }

    private boolean isInsufficientSystemResource(String finishReason) {
        return "insufficient_system_resource".equalsIgnoreCase(StringUtils.trimToEmpty(finishReason));
    }

    private ModelGatewayException overloaded(String model, String message) {
        return new ModelGatewayException(ModelErrorCode.PROVIDER_OVERLOADED, message, true, 503,
                null, model, null);
    }

    private ModelGatewayException deadlineExceeded(String model) {
        return new ModelGatewayException(ModelErrorCode.MODEL_CALL_TIMEOUT,
                ModelErrorCode.MODEL_CALL_TIMEOUT.message(), false, null, null, model, null);
    }

    private long remainingMs(ChatPrompt prompt) {
        if (prompt.getDeadlineEpochMs() == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, prompt.getDeadlineEpochMs() - System.currentTimeMillis());
    }

    private boolean canWait(ChatPrompt prompt, long delayMs) {
        long remaining = remainingMs(prompt);
        return remaining == Long.MAX_VALUE || delayMs < remaining;
    }

    private ChatPrompt withModel(ChatPrompt source, String model) {
        return ChatPrompt.builder()
                .requestId(source.getRequestId())
                .conversationId(source.getConversationId())
                .message(source.getMessage())
                .systemPrompt(source.getSystemPrompt())
                .model(model)
                .temperature(source.getTemperature())
                .maxTokens(source.getMaxTokens())
                .outputFormat(source.getOutputFormat())
                .capability(source.getCapability())
                .purpose(source.getPurpose())
                .deadlineEpochMs(source.getDeadlineEpochMs())
                .messages(source.getMessages())
                .build();
    }

    private void traceFallback(AgentContext context,
                               String fromModel,
                               String toModel,
                               String reason,
                               int attempt) {
        if (context == null) {
            return;
        }
        traceRecorder.recordModelGatewayEvent(context, "model_fallback_switched",
                ModelCapabilities.COMPLETE_AGENT_DECISION, "success", 0L,
                "model fallback switched", null,
                Map.of("fromModel", fromModel, "toModel", toModel, "reason", safe(reason), "attempt", attempt));
    }

    private ModelGatewayException fallbackBudgetError(AgentContext context, ChatPrompt prompt, String fallbackModel) {
        int maxTokens = prompt.getMaxTokens() == null
                ? environment.getProperty("spring.ai.deepseek.chat.max-tokens", Integer.class, 2048)
                : prompt.getMaxTokens();
        return budgetError(context, prompt, fallbackModel, maxTokens);
    }

    private ModelGatewayException budgetError(AgentContext context,
                                              ChatPrompt prompt,
                                              String model,
                                              int maxTokens) {
        if (budgetGuard == null || context == null) {
            return null;
        }
        BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context,
                "model_gateway_preflight", model, prompt.getPurpose(), budgetInput(prompt), maxTokens);
        if (check.isAllowed()) {
            return null;
        }
        return new ModelGatewayException(ModelErrorCode.BUDGET_EXCEEDED,
                "model call exceeds remaining budget: " + model, false, null, null);
    }

    private String budgetInput(ChatPrompt prompt) {
        StringBuilder input = new StringBuilder(StringUtils.defaultString(prompt.getSystemPrompt()))
                .append(StringUtils.defaultString(prompt.getMessage()));
        if (prompt.getMessages() != null) {
            prompt.getMessages().forEach(message -> input.append(StringUtils.defaultString(message.getContent())));
        }
        return input.toString();
    }

    private long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private String safe(String value) {
        return StringUtils.defaultIfBlank(value, "none");
    }

    private record ResilienceKey(String provider, String model, String capability) {

        private String circuitName() {
            return provider + ":" + model + ":" + capability;
        }

    }

}
