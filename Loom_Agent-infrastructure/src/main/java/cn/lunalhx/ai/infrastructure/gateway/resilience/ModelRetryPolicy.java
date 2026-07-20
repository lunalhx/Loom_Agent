package cn.lunalhx.ai.infrastructure.gateway.resilience;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.apache.commons.lang3.StringUtils;

import java.util.function.LongUnaryOperator;

public final class ModelRetryPolicy {

    private final ModelRuntimeProperties properties;
    private final ModelRequestNormalizer requestNormalizer;
    private final ModelCallPreflight preflight;
    private final ModelFailureClassifier classifier;
    private final LongUnaryOperator jitterSource;

    ModelRetryPolicy(ModelRuntimeProperties properties,
                     ModelRequestNormalizer requestNormalizer,
                     ModelCallPreflight preflight,
                     ModelFailureClassifier classifier,
                     LongUnaryOperator jitterSource) {
        this.properties = properties;
        this.requestNormalizer = requestNormalizer;
        this.preflight = preflight;
        this.classifier = classifier;
        this.jitterSource = jitterSource;
    }

    ModelRetryDecision decide(ModelAttemptState state, Throwable error, AgentContext context, boolean retryAllowed) {
        ModelRuntimeProperties effectiveProperties = requestNormalizer.effectiveProperties(state.prompt());
        Throwable normalized = classifier.normalize(error, state.key().model());
        boolean retryable = retryAllowed && classifier.retryable(normalized);

        if (!retryable) {
            return new ModelRetryDecision(ModelRetryDecision.Action.STOP, null, 0L, normalized, null, false);
        }

        if (state.attemptNo() >= maxAttempts(effectiveProperties)) {
            return new ModelRetryDecision(ModelRetryDecision.Action.STOP, null, 0L, normalized, null, true);
        }

        int nextOverload = classifier.overload(normalized) ? state.consecutiveOverload() + 1 : 0;

        ModelFallbackSwitch fallbackSwitch = null;
        ChatPrompt nextPrompt = state.prompt();
        ModelCallKey nextKey = state.key();
        String fallbackReason = state.fallbackReason();

        if (classifier.isEmptyResponse(normalized)) {
            nextPrompt = requestNormalizer.withEmptyResponseHint(nextPrompt);
            nextKey = requestNormalizer.key(nextPrompt);
        }

        if (shouldFallback(effectiveProperties, state.key().model(), nextOverload)) {
            String fallbackModel = resilience(effectiveProperties).getFallbackModel();
            try {
                preflight.validateFallbackBudget(context, state.prompt(), fallbackModel);
            } catch (ModelGatewayException e) {
                return new ModelRetryDecision(ModelRetryDecision.Action.STOP, null, 0L, e, null, false);
            }
            fallbackReason = classifier.errorCode(normalized);
            fallbackSwitch = new ModelFallbackSwitch(state.key().model(), fallbackModel, fallbackReason, state.attemptNo());
            nextPrompt = requestNormalizer.withModel(state.prompt(), fallbackModel);
            nextKey = requestNormalizer.key(nextPrompt);
            nextOverload = 0;
        }

        long delayMs = retryDelayMs(effectiveProperties, state.attemptNo(), normalized);
        if (!preflight.canWait(state.prompt(), delayMs)) {
            ModelGatewayException timeoutError = classifier.deadlineExceeded(state.key().model());
            return new ModelRetryDecision(ModelRetryDecision.Action.STOP, null, 0L, timeoutError, null, false);
        }

        ModelAttemptState nextState = new ModelAttemptState(nextPrompt, nextKey, state.attemptNo() + 1,
                nextOverload, fallbackReason);
        return new ModelRetryDecision(ModelRetryDecision.Action.RETRY, nextState, delayMs, null, fallbackSwitch, false);
    }

    private long retryDelayMs(ModelRuntimeProperties effectiveProperties,
                              int attemptNo, Throwable error) {
        if (error instanceof ModelGatewayException exception && exception.getRetryAfterMs() != null) {
            return Math.max(0L, exception.getRetryAfterMs());
        }
        long base = Math.min(backoffMaxMs(effectiveProperties),
                backoffInitialMs(effectiveProperties) * (1L << Math.min(20, Math.max(0, attemptNo - 1))));
        long jitter = Math.round(base * 0.25D);
        long delta = jitter <= 0 ? 0 : jitterSource.applyAsLong(jitter);
        return Math.max(0L, base + delta);
    }

    private boolean shouldFallback(ModelRuntimeProperties effectiveProperties,
                                   String currentModel, int consecutiveOverload) {
        String fallbackModel = resilience(effectiveProperties).getFallbackModel();
        return consecutiveOverload >= Math.max(1,
                resilience(effectiveProperties).getOverloadFallbackThreshold())
                && StringUtils.isNotBlank(fallbackModel)
                && !StringUtils.equals(currentModel, fallbackModel)
                && effectiveProperties.getAllowedModels().contains(fallbackModel);
    }

    private int maxAttempts(ModelRuntimeProperties effectiveProperties) {
        return Math.max(1, resilience(effectiveProperties).getRetryMaxAttempts());
    }

    private long backoffInitialMs(ModelRuntimeProperties effectiveProperties) {
        return Math.max(1L, resilience(effectiveProperties).getRetryBackoffInitialMs());
    }

    private long backoffMaxMs(ModelRuntimeProperties effectiveProperties) {
        return Math.max(backoffInitialMs(effectiveProperties),
                resilience(effectiveProperties).getRetryBackoffMaxMs());
    }

    private ModelRuntimeProperties.ResilienceProperties resilience(
            ModelRuntimeProperties effectiveProperties) {
        return effectiveProperties.getResilience() == null
                ? new ModelRuntimeProperties.ResilienceProperties()
                : effectiveProperties.getResilience();
    }

}
