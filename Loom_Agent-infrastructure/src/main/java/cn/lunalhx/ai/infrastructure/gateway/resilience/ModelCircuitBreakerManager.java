package cn.lunalhx.ai.infrastructure.gateway.resilience;

import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;

public final class ModelCircuitBreakerManager {

    private final CircuitBreakerRegistry registry;
    private final CircuitBreakerConfig config;
    private final ModelFailureClassifier classifier;

    ModelCircuitBreakerManager(ModelRuntimeProperties properties, ModelFailureClassifier classifier) {
        this.classifier = classifier;
        this.config = circuitBreakerConfig(properties);
        this.registry = CircuitBreakerRegistry.of(config);
    }

    CircuitPermit acquire(ModelCallKey key) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker(key.circuitName(), config);
        CircuitBreaker.State beforeState = circuitBreaker.getState();
        if (!circuitBreaker.tryAcquirePermission()) {
            throw CallNotPermittedException.createCallNotPermittedException(circuitBreaker);
        }
        return new CircuitPermit(key, circuitBreaker, beforeState);
    }

    CircuitTransition success(CircuitPermit permit, long durationMs) {
        CircuitBreaker.State before = permit.circuitBreaker().getState();
        permit.circuitBreaker().onSuccess(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        CircuitBreaker.State after = permit.circuitBreaker().getState();
        return new CircuitTransition(before, after);
    }

    CircuitTransition failure(CircuitPermit permit, long durationMs, Throwable error, boolean retryable) {
        CircuitBreaker.State before = permit.circuitBreaker().getState();
        if (retryable) {
            permit.circuitBreaker().onError(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS, error);
        } else {
            permit.circuitBreaker().releasePermission();
        }
        CircuitBreaker.State after = permit.circuitBreaker().getState();
        return new CircuitTransition(before, after);
    }

    private CircuitBreakerConfig circuitBreakerConfig(ModelRuntimeProperties properties) {
        ModelRuntimeProperties.ResilienceProperties resilience = resilience(properties);
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(resilience.getCircuitFailureRateThreshold())
                .slowCallRateThreshold(resilience.getCircuitSlowCallRateThreshold())
                .slowCallDurationThreshold(Duration.ofMillis(resilience.getCircuitSlowCallDurationMs()))
                .slidingWindowSize(resilience.getCircuitSlidingWindowSize())
                .minimumNumberOfCalls(resilience.getCircuitSlidingWindowSize())
                .waitDurationInOpenState(Duration.ofMillis(resilience.getCircuitOpenStateWaitMs()))
                .permittedNumberOfCallsInHalfOpenState(resilience.getCircuitHalfOpenPermittedCalls())
                .recordException(classifier::retryable)
                .build();
    }

    private ModelRuntimeProperties.ResilienceProperties resilience(ModelRuntimeProperties properties) {
        if (properties.getResilience() == null) {
            properties.setResilience(new ModelRuntimeProperties.ResilienceProperties());
        }
        return properties.getResilience();
    }

    record CircuitPermit(ModelCallKey key, CircuitBreaker circuitBreaker, CircuitBreaker.State beforeState) {}

    record CircuitTransition(CircuitBreaker.State before, CircuitBreaker.State after) {
        boolean changed() {
            return !before.equals(after);
        }
    }

}
