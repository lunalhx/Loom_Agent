package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.service.ModelCallTraceContext;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
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
    private static final double JITTER = 0.2D;

    private final ModelGateway delegate;
    private final ModelRuntimeProperties properties;
    private final TraceRecorder traceRecorder;
    private final MeterRegistry meterRegistry;
    private final Environment environment;
    private final CircuitBreakerRegistry circuitBreakers;
    private final Map<String, AtomicInteger> circuitStateGauges = new ConcurrentHashMap<>();

    public ResilientModelGateway(@Qualifier("deepSeekModelGateway") ModelGateway delegate,
                                 ModelRuntimeProperties properties,
                                 TraceRecorder traceRecorder,
                                 MeterRegistry meterRegistry,
                                 Environment environment) {
        this.delegate = delegate;
        this.properties = properties;
        this.traceRecorder = traceRecorder;
        this.meterRegistry = meterRegistry;
        this.environment = environment;
        this.circuitBreakers = CircuitBreakerRegistry.of(circuitBreakerConfig());
    }

    @Override
    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
        ChatPrompt normalized = withCapability(prompt, ModelCapabilities.COMPLETE_AGENT_DECISION);
        ResilienceKey key = key(normalized);
        AgentContext context = ModelCallTraceContext.current();
        if (!enabled()) {
            return delegate.complete(normalized);
        }
        return completeAttempt(normalized, key, context, 1);
    }

    @Override
    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
        ChatPrompt normalized = withCapability(prompt, ModelCapabilities.STREAM_CHAT);
        ResilienceKey key = key(normalized);
        AgentContext context = ModelCallTraceContext.current();
        if (!enabled()) {
            return delegate.stream(normalized);
        }
        AtomicBoolean tokenEmitted = new AtomicBoolean(false);
        return streamAttempt(normalized, key, context, tokenEmitted, 1);
    }

    private Mono<ModelChatResult> completeAttempt(ChatPrompt prompt, ResilienceKey key, AgentContext context, int attemptNo) {
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
                .doOnSuccess(result -> {
                    long durationMs = elapsed(startedAt);
                    circuitBreaker.onSuccess(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                    recordAttempt(context, key, "success", durationMs, attemptNo, null);
                    recordModelCall(key, "success", "none", durationMs);
                    recordStateChange(context, key, beforeState, circuitBreaker.getState(), attemptNo, null);
                })
                .onErrorResume(error -> {
                    long durationMs = elapsed(startedAt);
                    Throwable unwrapped = unwrap(error);
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
                    recordRetry(key, "scheduled", errorCode(unwrapped));
                    return Mono.delay(backoff(attemptNo)).then(completeAttempt(prompt, key, context, attemptNo + 1));
                });
    }

    private Flux<ModelStreamChunk> streamAttempt(ChatPrompt prompt,
                                                ResilienceKey key,
                                                AgentContext context,
                                                AtomicBoolean tokenEmitted,
                                                int attemptNo) {
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
                .timeout(Duration.ofMillis(firstTokenTimeoutMs()))
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
                    recordRetry(key, "scheduled", errorCode(unwrapped));
                    return Mono.delay(backoff(attemptNo)).thenMany(streamAttempt(prompt, key, context, tokenEmitted, attemptNo + 1));
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

    private ChatPrompt withCapability(ChatPrompt prompt, String fallback) {
        prompt.setCapability(StringUtils.defaultIfBlank(prompt.getCapability(), fallback));
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
                && (exception.getHttpStatus() == 400
                || exception.getHttpStatus() == 401
                || exception.getHttpStatus() == 402
                || exception.getHttpStatus() == 422)) {
            return true;
        }
        ModelErrorCode code = exception.getErrorCode();
        return code == ModelErrorCode.CONFIG_ERROR
                || code == ModelErrorCode.INVALID_REQUEST
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

    private String errorCode(Throwable error) {
        if (error instanceof ModelGatewayException exception && exception.getErrorCode() != null) {
            return exception.getErrorCode().code();
        }
        if (error instanceof CallNotPermittedException) {
            return "circuit_open";
        }
        return error == null ? "none" : error.getClass().getSimpleName();
    }

    private Duration backoff(int attemptNo) {
        long base = Math.min(backoffMaxMs(), backoffInitialMs() * (1L << Math.min(20, Math.max(0, attemptNo - 1))));
        long jitter = Math.round(base * JITTER);
        long delta = jitter <= 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        return Duration.ofMillis(Math.max(0L, base + delta));
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
