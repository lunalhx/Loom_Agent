package cn.lunalhx.ai.infrastructure.gateway.resilience;

import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModelGatewayObserver {

    private final TraceRecorder traceRecorder;
    private final MeterRegistry meterRegistry;
    private final ModelFailureClassifier classifier;
    private final int maxAttempts;
    private final Map<String, AtomicInteger> circuitStateGauges = new ConcurrentHashMap<>();

    ModelGatewayObserver(TraceRecorder traceRecorder,
                         MeterRegistry meterRegistry,
                         ModelFailureClassifier classifier,
                         int maxAttempts) {
        this.traceRecorder = traceRecorder;
        this.meterRegistry = meterRegistry;
        this.classifier = classifier;
        this.maxAttempts = maxAttempts;
    }

    void attemptSucceeded(AgentContext context, ModelCallKey key, long durationMs, int attemptNo) {
        recordAttempt(context, key, "success", durationMs, attemptNo, null);
        recordModelCall(key, "success", "none", durationMs);
    }

    void attemptFailed(AgentContext context, ModelCallKey key, long durationMs, int attemptNo, Throwable error) {
        recordAttempt(context, key, "error", durationMs, attemptNo, error);
        recordModelCall(key, "error", classifier.errorCode(error), durationMs);
    }

    void circuitBlocked(AgentContext context, ModelCallKey key, int attemptNo, Throwable error) {
        recordGatewayEvent(context, "circuit_open", key, "blocked", 0L, attemptNo, error, "circuit is open");
        recordCircuit(key, "OPEN");
        recordModelCall(key, "error", "circuit_open", 0L);
    }

    void circuitTransition(AgentContext context, ModelCallKey key,
                           ModelCircuitBreakerManager.CircuitTransition transition,
                           int attemptNo, Throwable error) {
        if (!transition.changed()) {
            return;
        }
        recordCircuit(key, transition.after().name());
        recordGatewayEvent(context, "circuit_state_changed", key,
                transition.after().name().toLowerCase(), 0L, attemptNo, error,
                "circuit state changed from " + transition.before() + " to " + transition.after());
    }

    void retryScheduled(ModelCallKey key, String errorCode) {
        recordRetry(key, "scheduled", errorCode);
    }

    void retryExhausted(AgentContext context, ModelCallKey key, long durationMs, int attemptNo, Throwable error) {
        recordGatewayEvent(context, "model_retry_exhausted", key, "error", durationMs, attemptNo,
                error, "retry exhausted");
    }

    void fallbackSwitched(AgentContext context, ModelFallbackSwitch fallbackSwitch) {
        if (context == null) {
            return;
        }
        traceRecorder.recordModelGatewayEvent(context, "model_fallback_switched",
                ModelCapabilities.COMPLETE_AGENT_DECISION, "success", 0L,
                "model fallback switched", null,
                Map.of("fromModel", fallbackSwitch.fromModel(),
                        "toModel", fallbackSwitch.toModel(),
                        "reason", safe(fallbackSwitch.reason()),
                        "attempt", fallbackSwitch.attemptNo()));
    }

    private void recordAttempt(AgentContext context, ModelCallKey key,
                               String status, long durationMs, int attemptNo, Throwable error) {
        Map<String, Object> metadata = Map.of(
                "provider", key.provider(),
                "model", key.model(),
                "capability", key.capability(),
                "attemptNo", attemptNo,
                "maxAttempts", maxAttempts,
                "retryable", error == null || classifier.retryable(error));
        traceRecorder.recordModelGatewayEvent(context, "model_retry_attempt", key.capability(), status,
                durationMs, "model call attempt " + status, error, metadata);
        recordRetry(key, status, classifier.errorCode(error));
    }

    private void recordGatewayEvent(AgentContext context,
                                    String eventType,
                                    ModelCallKey key,
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
                        "maxAttempts", maxAttempts,
                        "retryable", error == null || classifier.retryable(error)));
    }

    private void recordModelCall(ModelCallKey key, String status, String errorCode, long durationMs) {
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

    private void recordRetry(ModelCallKey key, String status, String errorCode) {
        meterRegistry.counter("loom_agent_model_retry_total",
                        "model", key.model(),
                        "capability", key.capability(),
                        "status", safe(status),
                        "error_code", safe(errorCode))
                .increment();
    }

    private void recordCircuit(ModelCallKey key, String state) {
        meterRegistry.counter("loom_agent_circuit_event_total",
                        "model", key.model(),
                        "capability", key.capability(),
                        "state", safe(state))
                .increment();
        for (io.github.resilience4j.circuitbreaker.CircuitBreaker.State candidate :
                io.github.resilience4j.circuitbreaker.CircuitBreaker.State.values()) {
            AtomicInteger value = circuitGauge(key, candidate.name());
            value.set(Objects.equals(candidate.name(), state) ? 1 : 0);
        }
    }

    private AtomicInteger circuitGauge(ModelCallKey key, String state) {
        String gaugeKey = key.model() + "|" + key.capability() + "|" + state;
        return circuitStateGauges.computeIfAbsent(gaugeKey, ignored -> {
            AtomicInteger value = new AtomicInteger(0);
            meterRegistry.gauge("loom_agent_circuit_state",
                    Tags.of("model", key.model(), "capability", key.capability(), "state", state),
                    value);
            return value;
        });
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

}
