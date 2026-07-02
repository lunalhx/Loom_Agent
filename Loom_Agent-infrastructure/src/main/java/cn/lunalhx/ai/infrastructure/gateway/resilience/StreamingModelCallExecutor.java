package cn.lunalhx.ai.infrastructure.gateway.resilience;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class StreamingModelCallExecutor {

    private final ModelGateway delegate;
    private final ModelCallPreflight preflight;
    private final ModelFailureClassifier classifier;
    private final ModelRetryPolicy retryPolicy;
    private final ModelCircuitBreakerManager circuitBreakerManager;
    private final ModelGatewayObserver observer;
    private final long firstTokenTimeoutMs;

    public StreamingModelCallExecutor(ModelGateway delegate,
                               ModelCallPreflight preflight,
                               ModelFailureClassifier classifier,
                               ModelRetryPolicy retryPolicy,
                               ModelCircuitBreakerManager circuitBreakerManager,
                               ModelGatewayObserver observer,
                               long firstTokenTimeoutMs) {
        this.delegate = delegate;
        this.preflight = preflight;
        this.classifier = classifier;
        this.retryPolicy = retryPolicy;
        this.circuitBreakerManager = circuitBreakerManager;
        this.observer = observer;
        this.firstTokenTimeoutMs = firstTokenTimeoutMs;
    }

    public Flux<ModelStreamChunk> execute(ChatPrompt prompt, ModelCallKey key, AgentContext context) {
        AtomicBoolean tokenEmitted = new AtomicBoolean(false);
        // 用于在 doOnComplete 之前收集最后一次的 usage（DeepSeek SSE 在 stream_options.include_usage=true 时
        // 把 usage 放在最后一个 chunk 上；前置错误不会到这里，所以每个逻辑调用最多只记录一次）。
        AtomicReference<TokenUsage> latestUsage = new AtomicReference<>();
        ModelAttemptState state = new ModelAttemptState(prompt, key, 1, 0, null);
        return attempt(state, context, tokenEmitted, latestUsage);
    }

    private Flux<ModelStreamChunk> attempt(ModelAttemptState state, AgentContext context,
                                            AtomicBoolean tokenEmitted, AtomicReference<TokenUsage> latestUsage) {
        preflight.validate(state.prompt(), state.key(), context);

        ModelCircuitBreakerManager.CircuitPermit permit;
        try {
            permit = circuitBreakerManager.acquire(state.key());
        } catch (Exception e) {
            observer.circuitBlocked(context, state.key(), state.attemptNo(), e);
            return Flux.error(e);
        }

        long startedAt = System.currentTimeMillis();
        long timeoutMs = Math.min(firstTokenTimeoutMs, preflight.remainingMs(state.prompt()));

        return delegate.stream(state.prompt())
                .timeout(Duration.ofMillis(timeoutMs))
                .concatMap(chunk -> classifier.insufficientSystemResource(chunk.getFinishReason())
                        ? Mono.error(classifier.overloaded(state.key().model(), "insufficient_system_resource"))
                        : Mono.just(chunk))
                .doOnNext(chunk -> {
                    if (StringUtils.isNotEmpty(chunk.getContent())) {
                        tokenEmitted.set(true);
                    }
                    if (chunk.getUsage() != null) {
                        latestUsage.set(chunk.getUsage());
                    }
                })
                .doOnComplete(() -> {
                    long durationMs = elapsed(startedAt);
                    ModelCircuitBreakerManager.CircuitTransition transition =
                            circuitBreakerManager.success(permit, durationMs);
                    observer.attemptSucceeded(context, state.key(), durationMs, state.attemptNo());
                    // 成功尝试只触发一次：失败的重试不会进入 doOnComplete（流异常会走 onErrorResume），
                    // 因此 cache usage 不会重复累计。
                    observer.recordCacheUsage(context, state.key(), state.prompt().getPurpose(), latestUsage.get());
                    observer.circuitTransition(context, state.key(), transition, state.attemptNo(), null);
                })
                .onErrorResume(error -> {
                    long durationMs = elapsed(startedAt);
                    Throwable normalized = classifier.normalize(error, state.key().model());
                    boolean retryable = !tokenEmitted.get() && classifier.retryable(normalized);

                    ModelCircuitBreakerManager.CircuitTransition transition =
                            circuitBreakerManager.failure(permit, durationMs, normalized, retryable);
                    observer.attemptFailed(context, state.key(), durationMs, state.attemptNo(), normalized);
                    observer.circuitTransition(context, state.key(), transition, state.attemptNo(), normalized);

                    ModelRetryDecision decision = retryPolicy.decide(state, normalized, context, retryable);
                    return handleDecision(decision, state, context, tokenEmitted, latestUsage, durationMs);
                });
    }

    private Flux<ModelStreamChunk> handleDecision(ModelRetryDecision decision,
                                                   ModelAttemptState originalState,
                                                   AgentContext context,
                                                   AtomicBoolean tokenEmitted,
                                                   AtomicReference<TokenUsage> latestUsage,
                                                   long durationMs) {
        if (decision.action() == ModelRetryDecision.Action.STOP) {
            if (decision.retryExhausted()) {
                observer.retryExhausted(context, originalState.key(), durationMs,
                        originalState.attemptNo(), decision.terminalError());
            }
            return Flux.error(decision.terminalError());
        }

        if (decision.fallbackSwitch() != null) {
            observer.fallbackSwitched(context, decision.fallbackSwitch());
        }
        observer.retryScheduled(decision.nextAttempt().key(),
                classifier.errorCode(decision.terminalError()));

        return Mono.delay(Duration.ofMillis(decision.delayMs()))
                .thenMany(Flux.defer(() -> attempt(decision.nextAttempt(), context, tokenEmitted, latestUsage)));
    }

    private long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

}
