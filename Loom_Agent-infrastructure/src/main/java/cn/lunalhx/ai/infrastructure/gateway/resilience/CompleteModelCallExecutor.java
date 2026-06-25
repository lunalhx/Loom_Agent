package cn.lunalhx.ai.infrastructure.gateway.resilience;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;

public final class CompleteModelCallExecutor {

    private final ModelGateway delegate;
    private final ModelCallPreflight preflight;
    private final ModelFailureClassifier classifier;
    private final ModelRetryPolicy retryPolicy;
    private final ModelCircuitBreakerManager circuitBreakerManager;
    private final ModelGatewayObserver observer;

    public CompleteModelCallExecutor(ModelGateway delegate,
                              ModelCallPreflight preflight,
                              ModelFailureClassifier classifier,
                              ModelRetryPolicy retryPolicy,
                              ModelCircuitBreakerManager circuitBreakerManager,
                              ModelGatewayObserver observer) {
        this.delegate = delegate;
        this.preflight = preflight;
        this.classifier = classifier;
        this.retryPolicy = retryPolicy;
        this.circuitBreakerManager = circuitBreakerManager;
        this.observer = observer;
    }

    public Mono<ModelChatResult> execute(ChatPrompt prompt, ModelCallKey key, AgentContext context) {
        ModelAttemptState state = new ModelAttemptState(prompt, key, 1, 0, null);
        return attempt(state, context);
    }

    private Mono<ModelChatResult> attempt(ModelAttemptState state, AgentContext context) {
        preflight.validate(state.prompt(), state.key(), context);

        ModelCircuitBreakerManager.CircuitPermit permit;
        try {
            permit = circuitBreakerManager.acquire(state.key());
        } catch (Exception e) {
            observer.circuitBlocked(context, state.key(), state.attemptNo(), e);
            return Mono.error(e);
        }

        long startedAt = System.currentTimeMillis();
        return delegate.complete(state.prompt())
                .flatMap(result -> classifier.insufficientSystemResource(
                        result == null ? null : result.getFinishReason())
                        ? Mono.error(classifier.overloaded(state.key().model(), "insufficient_system_resource"))
                        : Mono.just(result))
                .doOnSuccess(result -> {
                    long durationMs = elapsed(startedAt);
                    if (StringUtils.isBlank(result.getActualModel())) {
                        result.setActualModel(state.key().model());
                    }
                    result.setFallbackReason(state.fallbackReason());
                    ModelCircuitBreakerManager.CircuitTransition transition =
                            circuitBreakerManager.success(permit, durationMs);
                    observer.attemptSucceeded(context, state.key(), durationMs, state.attemptNo());
                    observer.circuitTransition(context, state.key(), transition, state.attemptNo(), null);
                })
                .onErrorResume(error -> {
                    long durationMs = elapsed(startedAt);
                    Throwable normalized = classifier.normalize(error, state.key().model());
                    boolean retryable = classifier.retryable(normalized);

                    ModelCircuitBreakerManager.CircuitTransition transition =
                            circuitBreakerManager.failure(permit, durationMs, normalized, retryable);
                    observer.attemptFailed(context, state.key(), durationMs, state.attemptNo(), normalized);
                    observer.circuitTransition(context, state.key(), transition, state.attemptNo(), normalized);

                    ModelRetryDecision decision = retryPolicy.decide(state, normalized, context, retryable);
                    return handleDecision(decision, state, context, durationMs);
                });
    }

    private Mono<ModelChatResult> handleDecision(ModelRetryDecision decision,
                                                  ModelAttemptState originalState,
                                                  AgentContext context,
                                                  long durationMs) {
        if (decision.action() == ModelRetryDecision.Action.STOP) {
            if (decision.retryExhausted()) {
                observer.retryExhausted(context, originalState.key(), durationMs,
                        originalState.attemptNo(), decision.terminalError());
            }
            return Mono.error(decision.terminalError());
        }

        if (decision.fallbackSwitch() != null) {
            observer.fallbackSwitched(context, decision.fallbackSwitch());
        }
        observer.retryScheduled(decision.nextAttempt().key(),
                classifier.errorCode(decision.terminalError()));

        return Mono.delay(Duration.ofMillis(decision.delayMs()))
                .then(Mono.defer(() -> attempt(decision.nextAttempt(), context)));
    }

    private long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

}
