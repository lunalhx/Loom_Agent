package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.service.InMemoryTraceRecorder;
import cn.lunalhx.ai.domain.agent.service.ModelCallTraceContext;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.gateway.ResilientModelGateway;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * ResilientModelGateway 补充契约（Phase 1 §7）。
 *
 * <p>保留 {@code ResilientModelGatewayTest} 现有 10 个测试，本类补充目前缺失的高风险场景：
 * 预算 preflight 拒绝、fallback 预算不足停止切换、降级后 actualModel/fallbackReason、
 * stream 已输出 token 不降级、non-retryable 不计入熔断、complete/stream 错误分类与 retry delay 一致、
 * deadline 不足跳过下一次重试、metrics 标签低基数。
 *
 * <p>只要求 complete 与 stream 的策略语义一致，不要求内部实现一致。
 */
public class ResilientModelGatewayContractTest {

    // ===== 1. Budget preflight 拒绝时 delegate 不被调用 =====

    @Test
    public void budgetPreflightRejectionShouldNotCallDelegate() {
        AtomicInteger calls = new AtomicInteger();
        ModelGateway delegate = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Mono.just(ModelChatResult.builder().content("{}").finishReason("stop").build());
            }
        };
        // budgetGuard 恒拦截
        BudgetGuard blockingBudget = new BudgetGuard() {
            @Override
            public BudgetCheckResult checkBeforeModelCall(cn.lunalhx.ai.domain.agent.model.entity.AgentContext ctx, String node, String input) {
                return BudgetCheckResult.blocked(0L, 0L, 0L, 1L);
            }

            @Override
            public BudgetCheckResult checkBeforeModelCall(cn.lunalhx.ai.domain.agent.model.entity.AgentContext ctx, String node, String model, cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose purpose, String input, int requestedMaxTokens) {
                return BudgetCheckResult.blocked(0L, 0L, 0L, 1L);
            }

            @Override
            public cn.lunalhx.ai.domain.agent.model.valobj.TraceCost recordModelUsage(cn.lunalhx.ai.domain.agent.model.entity.AgentContext ctx, cn.lunalhx.ai.domain.model.valobj.TokenUsage usage) {
                return null;
            }

            @Override
            public cn.lunalhx.ai.domain.agent.model.valobj.TraceCost recordModelUsage(cn.lunalhx.ai.domain.agent.model.entity.AgentContext ctx, String model, cn.lunalhx.ai.domain.model.valobj.TokenUsage usage) {
                return null;
            }
        };
        ResilientModelGateway gateway = new ResilientModelGateway(delegate, properties(3, 4),
                new InMemoryTraceRecorder(), new SimpleMeterRegistry(),
                new MockEnvironment().withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"),
                blockingBudget);

        AgentContext context = context("budget-run");
        ModelGatewayException error;
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            error = assertThrows(ModelGatewayException.class,
                    () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2)));
        }
        // budget preflight 拒绝 -> BUDGET_EXCEEDED，delegate 未被调用
        assertEquals(ModelErrorCode.BUDGET_EXCEEDED, error.getErrorCode());
        assertEquals(0, calls.get());
    }

    // ===== 2. fallback 模型预算不足时停止切换 =====

    @Test
    public void fallbackShouldStopSwitchingWhenBudgetInsufficient() {
        List<String> models = new ArrayList<>();
        AtomicInteger budgetChecks = new AtomicInteger();
        ModelRuntimeProperties properties = properties(5, 10);
        properties.getResilience().setOverloadFallbackThreshold(1);
        properties.getResilience().setFallbackModel("deepseek-v4-pro");
        // budgetGuard：第一次（主模型 preflight）放行，第二次（fallback preflight）拦截
        BudgetGuard budgetGuard = new BudgetGuard() {
            @Override
            public BudgetCheckResult checkBeforeModelCall(cn.lunalhx.ai.domain.agent.model.entity.AgentContext ctx, String node, String input) {
                return BudgetCheckResult.allowed(0L, 0L, 0L, Long.MAX_VALUE);
            }

            @Override
            public BudgetCheckResult checkBeforeModelCall(cn.lunalhx.ai.domain.agent.model.entity.AgentContext ctx, String node, String model, cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose purpose, String input, int requestedMaxTokens) {
                int n = budgetChecks.incrementAndGet();
                return n <= 1 ? BudgetCheckResult.allowed(0L, 0L, 0L, Long.MAX_VALUE)
                        : BudgetCheckResult.blocked(0L, 0L, 0L, 1L);
            }

            @Override
            public cn.lunalhx.ai.domain.agent.model.valobj.TraceCost recordModelUsage(cn.lunalhx.ai.domain.agent.model.entity.AgentContext ctx, cn.lunalhx.ai.domain.model.valobj.TokenUsage usage) {
                return null;
            }

            @Override
            public cn.lunalhx.ai.domain.agent.model.valobj.TraceCost recordModelUsage(cn.lunalhx.ai.domain.agent.model.entity.AgentContext ctx, String model, cn.lunalhx.ai.domain.model.valobj.TokenUsage usage) {
                return null;
            }
        };
        ResilientModelGateway gateway = new ResilientModelGateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                models.add(prompt.getModel());
                return Mono.error(new ModelGatewayException(ModelErrorCode.PROVIDER_OVERLOADED, "503", true, 503, null));
            }
        }, properties, new InMemoryTraceRecorder(), new SimpleMeterRegistry(),
                new MockEnvironment().withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"),
                budgetGuard);

        AgentContext context = context("fallback-budget-run");
        ModelGatewayException error;
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            error = assertThrows(ModelGatewayException.class,
                    () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2)));
        }
        // 主模型 over loaded -> 触发 fallback 切换；fallback preflight 预算不足 -> 停止切换，返回 BUDGET_EXCEEDED
        assertEquals(ModelErrorCode.BUDGET_EXCEEDED, error.getErrorCode());
        // 只调用了主模型，未实际调用 fallback delegate
        assertEquals(List.of("deepseek-v4-flash"), models);
    }

    // ===== 3. complete 降级后设置 actualModel 和 fallbackReason =====

    @Test
    public void completeAfterFallbackShouldSetActualModelAndFallbackReason() {
        List<String> models = new ArrayList<>();
        ModelRuntimeProperties properties = properties(5, 10);
        properties.getResilience().setOverloadFallbackThreshold(1);
        properties.getResilience().setFallbackModel("deepseek-v4-pro");
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                models.add(prompt.getModel());
                if ("deepseek-v4-pro".equals(prompt.getModel())) {
                    return Mono.just(ModelChatResult.builder().content("{}").finishReason("stop").build());
                }
                return Mono.error(new ModelGatewayException(ModelErrorCode.PROVIDER_OVERLOADED, "503", true, 503, null));
            }
        }, properties);

        ModelChatResult result = gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        // 降级后 actualModel=fallback，fallbackReason=原错误码
        assertEquals("deepseek-v4-pro", result.getActualModel());
        assertEquals(ModelErrorCode.PROVIDER_OVERLOADED.code(), result.getFallbackReason());
        assertEquals(List.of("deepseek-v4-flash", "deepseek-v4-pro"), models);
    }

    // ===== 4. stream 已输出 token 后既不重试也不降级 =====

    @Test
    public void streamAfterTokenEmittedShouldNeitherRetryNorDowngrade() {
        AtomicInteger calls = new AtomicInteger();
        ModelRuntimeProperties properties = properties(5, 10);
        properties.getResilience().setOverloadFallbackThreshold(1);
        properties.getResilience().setFallbackModel("deepseek-v4-pro");
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                calls.incrementAndGet();
                // 已输出 token 后再抛 overload（可重试 + 可降级）
                return Flux.concat(
                        Flux.just(ModelStreamChunk.builder().content("token-emitted").build()),
                        Flux.error(new ModelGatewayException(ModelErrorCode.PROVIDER_OVERLOADED, "503", true, 503, null)));
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder().content("{}").build());
            }
        }, properties);
        ModelGatewayException error = assertThrows(ModelGatewayException.class, () -> gateway
                .stream(prompt(ModelCapabilities.STREAM_CHAT)).collectList().block(Duration.ofSeconds(2)));
        // 已输出 token：不重试（只调用 1 次）、不降级（错误就是原 overload）
        assertEquals(1, calls.get());
        assertEquals(ModelErrorCode.PROVIDER_OVERLOADED, error.getErrorCode());
    }

    // ===== 5. non-retryable 异常不计入熔断失败 =====

    @Test
    public void nonRetryableExceptionShouldNotCountTowardCircuitFailures() {
        AtomicInteger calls = new AtomicInteger();
        // 滑动窗口=2，失败率阈值 50%。若 non-retryable 计入失败，2 次后应开路；
        // 实际 non-retryable 走 releasePermission，不计入 -> 不开路，第 3 次仍能调用。
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Mono.error(new ModelGatewayException(ModelErrorCode.INVALID_REQUEST, "400", false, 400, null));
            }
        }, properties(10, 2));
        assertThrows(ModelGatewayException.class, () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2)));
        assertThrows(ModelGatewayException.class, () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2)));
        // 第 3 次仍应被允许调用（未开路）
        assertThrows(ModelGatewayException.class, () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2)));
        assertEquals(3, calls.get());
    }

    // ===== 6. complete 与 stream 使用相同的错误分类（non-retryable 一致） =====

    @Test
    public void completeAndStreamShouldShareErrorClassificationForNonRetryable() {
        ModelErrorCode code = ModelErrorCode.CONTEXT_OVERFLOW;
        // complete：non-retryable -> 立即抛出，只调用 1 次
        AtomicInteger completeCalls = new AtomicInteger();
        ResilientModelGateway completeGateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                completeCalls.incrementAndGet();
                return Mono.error(new ModelGatewayException(code, "422", false, 422, null));
            }
        }, properties(5, 10));
        ModelGatewayException completeError = assertThrows(ModelGatewayException.class,
                () -> completeGateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2)));
        assertEquals(code, completeError.getErrorCode());
        assertEquals(1, completeCalls.get());

        // stream：同样的 non-retryable -> 立即抛出，只调用 1 次（不重试）
        AtomicInteger streamCalls = new AtomicInteger();
        ResilientModelGateway streamGateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                streamCalls.incrementAndGet();
                return Flux.error(new ModelGatewayException(code, "422", false, 422, null));
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder().content("{}").build());
            }
        }, properties(5, 10));
        ModelGatewayException streamError = assertThrows(ModelGatewayException.class,
                () -> streamGateway.stream(prompt(ModelCapabilities.STREAM_CHAT)).collectList().block(Duration.ofSeconds(2)));
        assertEquals(code, streamError.getErrorCode());
        assertEquals(1, streamCalls.get());
        // 两者错误分类一致：non-retryable 都不重试，调用次数均为 1
    }

    // ===== 7. deadline 不足时不等待下一次重试（stream） =====

    @Test
    public void streamShouldNotWaitForNextRetryWhenDeadlineInsufficient() {
        AtomicInteger calls = new AtomicInteger();
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Flux.error(new ModelGatewayException(ModelErrorCode.RATE_LIMITED, "429", true, 429, 5000L, prompt.getModel(), null));
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder().content("{}").build());
            }
        }, properties(4, 4));
        ChatPrompt p = prompt(ModelCapabilities.STREAM_CHAT);
        p.setDeadlineEpochMs(System.currentTimeMillis() + 30L);
        ModelGatewayException error = assertThrows(ModelGatewayException.class,
                () -> gateway.stream(p).collectList().block(Duration.ofSeconds(2)));
        // deadline 不足 -> MODEL_CALL_TIMEOUT，不等待下一次重试
        assertEquals(ModelErrorCode.MODEL_CALL_TIMEOUT, error.getErrorCode());
        assertEquals(1, calls.get());
    }

    // ===== 8. Metrics 标签继续保持低基数 =====

    @Test
    public void metricsTagsShouldStayLowCardinalityAcrossCompleteAndStream() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ResilientModelGateway gateway = new ResilientModelGateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.just(ModelStreamChunk.builder().content("ok").finishReason("stop").build());
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder().content("{}").finishReason("stop").build());
            }
        }, properties(3, 4), new InMemoryTraceRecorder(), meterRegistry,
                new MockEnvironment().withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"),
                null);

        AgentContext context = context("metrics-run");
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }
        gateway.stream(prompt(ModelCapabilities.STREAM_CHAT)).collectList().block(Duration.ofSeconds(2));

        Set<String> forbidden = Set.of("runId", "traceId", "requestId", "conversationId", "userId",
                "workspace", "question", "run_id", "trace_id", "request_id", "conversation_id", "user_id");
        for (Meter meter : meterRegistry.getMeters()) {
            assertTrue("禁止高基数标签：" + meter.getId().getTags(),
                    meter.getId().getTags().stream().noneMatch(tag -> forbidden.contains(tag.getKey())));
        }
    }

    // ===== 辅助 =====

    private ResilientModelGateway gateway(ModelGateway delegate, ModelRuntimeProperties properties) {
        return new ResilientModelGateway(delegate, properties, new InMemoryTraceRecorder(),
                new SimpleMeterRegistry(),
                new MockEnvironment().withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"));
    }

    private ModelRuntimeProperties properties(int maxAttempts, int slidingWindowSize) {
        ModelRuntimeProperties properties = new ModelRuntimeProperties();
        properties.setFirstTokenTimeoutMs(500L);
        properties.getResilience().setRetryMaxAttempts(maxAttempts);
        properties.getResilience().setRetryBackoffInitialMs(1L);
        properties.getResilience().setRetryBackoffMaxMs(2L);
        properties.getResilience().setCircuitSlidingWindowSize(slidingWindowSize);
        properties.getResilience().setCircuitOpenStateWaitMs(5000L);
        properties.getResilience().setCircuitFailureRateThreshold(50.0F);
        return properties;
    }

    private ChatPrompt prompt(String capability) {
        return ChatPrompt.builder()
                .message("hello")
                .model("deepseek-v4-flash")
                .capability(capability)
                .build();
    }

    private AgentContext context(String runId) {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setRootRunId(runId);
        context.setTraceId(runId);
        return context;
    }
}
