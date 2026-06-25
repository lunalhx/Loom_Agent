package cn.lunalhx.ai.infrastructure.gateway.resilience;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.env.Environment;

import java.util.concurrent.ThreadLocalRandom;

public record ModelResilienceAssembly(
        ModelRequestNormalizer requestNormalizer,
        CompleteModelCallExecutor completeExecutor,
        StreamingModelCallExecutor streamingExecutor
) {

    public static ModelResilienceAssembly create(ModelGateway delegate,
                                          ModelRuntimeProperties properties,
                                          TraceRecorder traceRecorder,
                                          MeterRegistry meterRegistry,
                                          Environment environment,
                                          BudgetGuard budgetGuard) {
        ModelRequestNormalizer normalizer = new ModelRequestNormalizer(properties, environment);
        ModelFailureClassifier classifier = new ModelFailureClassifier();
        ModelCallPreflight preflight = new ModelCallPreflight(properties, normalizer, budgetGuard,
                System::currentTimeMillis);
        ModelRetryPolicy retryPolicy = new ModelRetryPolicy(properties, normalizer, preflight, classifier,
                bound -> ThreadLocalRandom.current().nextLong(0, bound + 1));

        int maxAttempts = Math.max(1,
                properties.getResilience() != null ? properties.getResilience().getRetryMaxAttempts() : 10);

        ModelCircuitBreakerManager circuitBreakerManager = new ModelCircuitBreakerManager(properties, classifier);
        ModelGatewayObserver observer = new ModelGatewayObserver(traceRecorder, meterRegistry, classifier, maxAttempts);

        CompleteModelCallExecutor completeExecutor = new CompleteModelCallExecutor(
                delegate, preflight, classifier, retryPolicy, circuitBreakerManager, observer);

        long firstTokenTimeoutMs = properties.getFirstTokenTimeoutMs() != null
                ? Math.max(1L, properties.getFirstTokenTimeoutMs()) : 30000L;
        StreamingModelCallExecutor streamingExecutor = new StreamingModelCallExecutor(
                delegate, preflight, classifier, retryPolicy, circuitBreakerManager, observer, firstTokenTimeoutMs);

        return new ModelResilienceAssembly(normalizer, completeExecutor, streamingExecutor);
    }

}
