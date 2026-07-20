package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.service.observability.ModelCallTraceContext;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.infrastructure.gateway.ResilientModelGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * cache usage 在 ResilientModelGateway 路径上的端到端指标行为。
 *
 * <p>覆盖：
 * <ul>
 *   <li>指标名 / 标签值正确；</li>
 *   <li>complete 与 stream 各自只记录一次；</li>
 *   <li>retry 不会重复累计；</li>
 *   <li>fallback 切换的模型进入标签；</li>
 *   <li>usage 缺失只计 missing 计数，绝不伪造 0；</li>
 *   <li>hit + miss 与 prompt_tokens 不一致时只诊断，不修改原值；</li>
 *   <li>所有指标标签都是低基数（与既有 metricsTagsShouldStayLowCardinality 互不冲突）。</li>
 * </ul>
 */
public class ModelGatewayCacheMetricsTest {

    private static final Set<String> FORBIDDEN_TAGS = Set.of("runId", "traceId", "requestId",
            "conversationId", "userId", "workspace", "question",
            "run_id", "trace_id", "request_id", "conversation_id", "user_id");

    @Test
    public void completeShouldRecordCacheUsageOnceWithFullFields() {
        MeterRegistry registry = new SimpleMeterRegistry();
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        ResilientModelGateway gateway = gateway(registry, traceRecorder, new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content("{}")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .usage(TokenUsage.builder()
                                .promptTokens(100)
                                .completionTokens(50)
                                .totalTokens(150)
                                .promptCacheHitTokens(80)
                                .promptCacheMissTokens(20)
                                .build())
                        .build());
            }
        });

        AgentContext context = contextWithRole("cache-run", AgentRole.EXPLORER);
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }

        // 三个相关计数器都被递增
        assertEquals(1.0, count(registry, "loom_agent_model_cache_requests_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
        assertEquals(80.0, count(registry, "loom_agent_model_cache_hit_tokens_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
        assertEquals(20.0, count(registry, "loom_agent_model_cache_miss_tokens_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
        // hit + miss == prompt_tokens，没有 inconsistency
        assertEquals(0.0, count(registry, "loom_agent_model_cache_inconsistency_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
    }

    @Test
    public void missingUsageShouldOnlyIncrementMissingCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ResilientModelGateway gateway = gateway(registry, new InMemoryTraceRecorder(), new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                // provider 未返回 usage
                return Mono.just(ModelChatResult.builder().content("{}").finishReason("stop").build());
            }
        });

        AgentContext context = contextWithRole("missing-usage-run", AgentRole.EDITOR);
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }

        assertEquals(1.0, count(registry, "loom_agent_model_cache_requests_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EDITOR")), 0.0001);
        assertEquals(1.0, count(registry, "loom_agent_model_cache_missing_usage_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EDITOR")), 0.0001);
        // 没有伪造 hit/miss
        assertNoCounter(registry, "loom_agent_model_cache_hit_tokens_total");
        assertNoCounter(registry, "loom_agent_model_cache_miss_tokens_total");
    }

    @Test
    public void cacheFieldsAbsentShouldCountAsMissingNotZero() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ResilientModelGateway gateway = gateway(registry, new InMemoryTraceRecorder(), new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                // provider 返回了 usage 但没有 cache 字段
                return Mono.just(ModelChatResult.builder()
                        .content("{}")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .usage(TokenUsage.builder()
                                .promptTokens(100)
                                .completionTokens(50)
                                .totalTokens(150)
                                .build())
                        .build());
            }
        });

        AgentContext context = contextWithRole("no-cache-fields", null);
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }

        assertEquals(1.0, count(registry, "loom_agent_model_cache_missing_usage_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "none")), 0.0001);
        // hit/miss 都没有命中（缺数据 ≠ 0）
        assertNoCounter(registry, "loom_agent_model_cache_hit_tokens_total");
        assertNoCounter(registry, "loom_agent_model_cache_miss_tokens_total");
    }

    @Test
    public void hitMissInconsistentWithPromptTokensShouldOnlyDiagnose() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ResilientModelGateway gateway = gateway(registry, new InMemoryTraceRecorder(), new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content("{}")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .usage(TokenUsage.builder()
                                .promptTokens(100)
                                .completionTokens(50)
                                .totalTokens(150)
                                .promptCacheHitTokens(80)
                                .promptCacheMissTokens(15) // 80 + 15 = 95 != 100
                                .build())
                        .build());
            }
        });

        AgentContext context = contextWithRole("inconsistent-run", AgentRole.REVIEWER);
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }

        // hit/miss 原值原样累加，不做归一化
        assertEquals(80.0, count(registry, "loom_agent_model_cache_hit_tokens_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "REVIEWER")), 0.0001);
        assertEquals(15.0, count(registry, "loom_agent_model_cache_miss_tokens_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "REVIEWER")), 0.0001);
        // inconsistency 计数 +1
        assertEquals(1.0, count(registry, "loom_agent_model_cache_inconsistency_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "REVIEWER")), 0.0001);
    }

    @Test
    public void retryShouldNotDoubleCountCacheUsage() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger calls = new AtomicInteger();
        ResilientModelGateway gateway = gateway(registry, new InMemoryTraceRecorder(), new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    return Mono.error(new cn.lunalhx.ai.domain.model.valobj.ModelGatewayException(
                            cn.lunalhx.ai.domain.model.valobj.ModelErrorCode.RATE_LIMITED,
                            "429", true, 429, 5L, "deepseek-v4-flash", null));
                }
                return Mono.just(ModelChatResult.builder()
                        .content("{}")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .usage(TokenUsage.builder()
                                .promptTokens(100)
                                .completionTokens(50)
                                .totalTokens(150)
                                .promptCacheHitTokens(70)
                                .promptCacheMissTokens(30)
                                .build())
                        .build());
            }
        }, /*maxAttempts*/ 4, /*slidingWindow*/ 4);

        AgentContext context = contextWithRole("retry-cache-run", AgentRole.EXPLORER);
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }

        // 重试了一次，最终成功 1 次 => requests = 1, hit = 70, miss = 30
        assertEquals(2, calls.get());
        assertEquals(1.0, count(registry, "loom_agent_model_cache_requests_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
        assertEquals(70.0, count(registry, "loom_agent_model_cache_hit_tokens_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
        assertEquals(30.0, count(registry, "loom_agent_model_cache_miss_tokens_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
    }

    @Test
    public void fallbackShouldTagCacheUsageWithActualModel() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ResilientModelGateway gateway = gateway(registry, new InMemoryTraceRecorder(), new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if ("deepseek-v4-flash".equals(prompt.getModel())) {
                    return Mono.error(new cn.lunalhx.ai.domain.model.valobj.ModelGatewayException(
                            cn.lunalhx.ai.domain.model.valobj.ModelErrorCode.PROVIDER_OVERLOADED,
                            "503", true, 503, null));
                }
                return Mono.just(ModelChatResult.builder()
                        .content("{}")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-pro") // 实际跑在 fallback 模型
                        .usage(TokenUsage.builder()
                                .promptTokens(50)
                                .completionTokens(20)
                                .totalTokens(70)
                                .promptCacheHitTokens(40)
                                .promptCacheMissTokens(10)
                                .build())
                        .build());
            }
        }, /*maxAttempts*/ 5, /*slidingWindow*/ 10);

        // 启用 fallback 切换
        ModelRuntimeProperties properties = properties(5, 10);
        properties.getResilience().setOverloadFallbackThreshold(1);
        properties.getResilience().setFallbackModel("deepseek-v4-pro");
        gateway = gatewayWithProperties(registry, new InMemoryTraceRecorder(), new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if ("deepseek-v4-flash".equals(prompt.getModel())) {
                    return Mono.error(new cn.lunalhx.ai.domain.model.valobj.ModelGatewayException(
                            cn.lunalhx.ai.domain.model.valobj.ModelErrorCode.PROVIDER_OVERLOADED,
                            "503", true, 503, null));
                }
                return Mono.just(ModelChatResult.builder()
                        .content("{}")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-pro")
                        .usage(TokenUsage.builder()
                                .promptTokens(50)
                                .completionTokens(20)
                                .totalTokens(70)
                                .promptCacheHitTokens(40)
                                .promptCacheMissTokens(10)
                                .build())
                        .build());
            }
        }, properties);

        AgentContext context = contextWithRole("fallback-cache-run", AgentRole.EDITOR);
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }

        // fallback 模型的 hit/miss 计数器 +1，hit=40, miss=10；主模型没有 cache usage 计数
        assertEquals(40.0, count(registry, "loom_agent_model_cache_hit_tokens_total",
                tags("model", "deepseek-v4-pro",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EDITOR")), 0.0001);
        assertEquals(10.0, count(registry, "loom_agent_model_cache_miss_tokens_total",
                tags("model", "deepseek-v4-pro",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EDITOR")), 0.0001);
        assertNoCounter(registry, "loom_agent_model_cache_hit_tokens_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "EDITOR"));
    }

    @Test
    public void subAgentShouldBeDistinguishedInAgentRoleTag() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ResilientModelGateway gateway = gateway(registry, new InMemoryTraceRecorder(), new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content("{}")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .usage(TokenUsage.builder()
                                .promptTokens(10).completionTokens(2).totalTokens(12)
                                .promptCacheHitTokens(5).promptCacheMissTokens(5)
                                .build())
                        .build());
            }
        });

        AgentContext subContext = contextWithRole("sub-call", AgentRole.REVIEWER);
        subContext.setParentRunId("parent-run"); // 标记为子 agent
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(subContext)) {
            gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }

        // 子 agent：agent_role = "REVIEWER.sub"
        assertEquals(5.0, count(registry, "loom_agent_model_cache_hit_tokens_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "complete.agent_decision",
                        "purpose", "CONTROL_JSON",
                        "call_site", "model_call",
                        "agent_role", "REVIEWER.sub")), 0.0001);
    }

    @Test
    public void streamShouldRecordCacheUsageOnceWithFinalChunkUsage() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ResilientModelGateway gateway = gateway(registry, new InMemoryTraceRecorder(), new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.concat(
                        Flux.just(ModelStreamChunk.builder().content("hello").build()),
                        Flux.just(ModelStreamChunk.builder().content(" world").build()),
                        // DeepSeek SSE 风格：最后一块是 usage，没有 content
                        Flux.just(ModelStreamChunk.builder()
                                .finishReason("stop")
                                .usage(TokenUsage.builder()
                                        .promptTokens(200).completionTokens(10).totalTokens(210)
                                        .promptCacheHitTokens(150).promptCacheMissTokens(50)
                                        .build())
                                .build()));
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.empty();
            }
        });

        AgentContext context = contextWithRole("stream-cache-run", AgentRole.EXPLORER);
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.stream(prompt(ModelCapabilities.STREAM_CHAT)).collectList().block(Duration.ofSeconds(2));
        }

        assertEquals(1.0, count(registry, "loom_agent_model_cache_requests_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "stream.chat",
                        "purpose", "FINAL_TEXT",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
        assertEquals(150.0, count(registry, "loom_agent_model_cache_hit_tokens_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "stream.chat",
                        "purpose", "FINAL_TEXT",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
        assertEquals(50.0, count(registry, "loom_agent_model_cache_miss_tokens_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "stream.chat",
                        "purpose", "FINAL_TEXT",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
    }

    @Test
    public void streamWithoutUsageChunkShouldOnlyCountMissing() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ResilientModelGateway gateway = gateway(registry, new InMemoryTraceRecorder(), new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.just(
                        ModelStreamChunk.builder().content("hi").build(),
                        ModelStreamChunk.builder().content(" there").finishReason("stop").build());
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.empty();
            }
        });

        AgentContext context = contextWithRole("stream-no-usage", AgentRole.EXPLORER);
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.stream(prompt(ModelCapabilities.STREAM_CHAT)).collectList().block(Duration.ofSeconds(2));
        }

        assertEquals(1.0, count(registry, "loom_agent_model_cache_requests_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "stream.chat",
                        "purpose", "FINAL_TEXT",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
        assertEquals(1.0, count(registry, "loom_agent_model_cache_missing_usage_total",
                tags("model", "deepseek-v4-flash",
                        "capability", "stream.chat",
                        "purpose", "FINAL_TEXT",
                        "call_site", "model_call",
                        "agent_role", "EXPLORER")), 0.0001);
        assertNoCounter(registry, "loom_agent_model_cache_hit_tokens_total");
        assertNoCounter(registry, "loom_agent_model_cache_miss_tokens_total");
    }

    @Test
    public void cacheUsageMetricsTagsShouldStayLowCardinality() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ResilientModelGateway gateway = gateway(registry, new InMemoryTraceRecorder(), new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content("{}")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .usage(TokenUsage.builder()
                                .promptTokens(10).completionTokens(1).totalTokens(11)
                                .promptCacheHitTokens(7).promptCacheMissTokens(3)
                                .build())
                        .build());
            }
        });

        AgentContext context = contextWithRole("low-cardinality-run", AgentRole.EXPLORER);
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }

        for (Meter meter : registry.getMeters()) {
            if (meter.getId().getName().startsWith("loom_agent_model_cache_")) {
                for (Tag tag : meter.getId().getTags()) {
                    assertTrue("cache 指标禁止高基数标签: " + meter.getId() + " -> " + tag,
                            !FORBIDDEN_TAGS.contains(tag.getKey()));
                }
            }
        }
    }

    // ============== helpers ==============

    private static double count(MeterRegistry registry, String name, List<Tag> tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        if (counter == null) {
            return 0.0;
        }
        return counter.count();
    }

    private static void assertNoCounter(MeterRegistry registry, String name) {
        Counter counter = registry.find(name).counter();
        if (counter != null) {
            throw new AssertionError("counter " + name + " should not exist but found with value " + counter.count());
        }
    }

    private static void assertNoCounter(MeterRegistry registry, String name, List<Tag> tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        if (counter != null) {
            throw new AssertionError("counter " + name + " with tags " + tags + " should not exist "
                    + "but found with value " + counter.count());
        }
    }

    private static List<Tag> tags(String... kv) {
        assertEquals(0, kv.length % 2);
        List<Tag> result = new java.util.ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            result.add(Tag.of(kv[i], kv[i + 1]));
        }
        return result;
    }

    private ResilientModelGateway gateway(MeterRegistry registry,
                                          InMemoryTraceRecorder traceRecorder,
                                          ModelGateway delegate) {
        return gateway(registry, traceRecorder, delegate, 3, 4);
    }

    private ResilientModelGateway gateway(MeterRegistry registry,
                                          InMemoryTraceRecorder traceRecorder,
                                          ModelGateway delegate,
                                          int maxAttempts,
                                          int slidingWindow) {
        return new ResilientModelGateway(delegate, properties(maxAttempts, slidingWindow),
                traceRecorder, registry,
                new MockEnvironment().withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"),
                noopBudgetGuard());
    }

    private ResilientModelGateway gatewayWithProperties(MeterRegistry registry,
                                                        InMemoryTraceRecorder traceRecorder,
                                                        ModelGateway delegate,
                                                        ModelRuntimeProperties properties) {
        return new ResilientModelGateway(delegate, properties, traceRecorder, registry,
                new MockEnvironment().withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"),
                noopBudgetGuard());
    }

    private static ModelRuntimeProperties properties(int maxAttempts, int slidingWindowSize) {
        ModelRuntimeProperties properties = new ModelRuntimeProperties();
        properties.setFirstTokenTimeoutMs(500L);
        properties.getResilience().setRetryMaxAttempts(maxAttempts);
        properties.getResilience().setRetryBackoffInitialMs(1L);
        properties.getResilience().setRetryBackoffMaxMs(2L);
        properties.getResilience().setCircuitSlidingWindowSize(slidingWindowSize);
        properties.getResilience().setCircuitOpenStateWaitMs(5000L);
        properties.getResilience().setCircuitFailureRateThreshold(50.0F);
        ModelRuntimeProperties.ProviderConfig cfg = new ModelRuntimeProperties.ProviderConfig();
        cfg.setDefaultModel("deepseek-v4-flash");
        cfg.setMaxTokens(2048);
        properties.getProviders().put("deepseek", cfg);
        return properties;
    }

    private static ChatPrompt prompt(String capability) {
        ChatPrompt.ChatPromptBuilder builder = ChatPrompt.builder()
                .message("hello")
                .model("deepseek-v4-flash")
                .capability(capability);
        // complete 链路在生产里走 ModelPromptFactory，会显式设 purpose=CONTROL_JSON / outputFormat=JSON_OBJECT；
        // stream 链路目前没有生产调用方，保留 normalize 后的默认行为（无 purpose 时按 outputFormat 推导，
        // 都没设就退化为 FINAL_TEXT）。这里按调用类型分别构造。
        if (ModelCapabilities.COMPLETE_AGENT_DECISION.equals(capability)
                || "complete.replan".equals(capability)
                || "complete.context_summary".equals(capability)) {
            builder.purpose(cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose.CONTROL_JSON)
                    .outputFormat(cn.lunalhx.ai.domain.model.valobj.OutputFormat.JSON_OBJECT);
        }
        return builder.build();
    }

    private static AgentContext contextWithRole(String runId, AgentRole role) {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setRootRunId(runId);
        context.setTraceId(runId);
        // 与生产路径一致：recordUsage 写入时，context 已位于某个 node（model_call / replan / ...）。
        // 这里统一固定为 "model_call"，让指标 call_site 标签可断言。
        context.setCurrentNode("model_call");
        if (role != null) {
            context.setAgentRole(role);
        }
        return context;
    }

    private static BudgetGuard noopBudgetGuard() {
        return new BudgetGuard() {
            @Override
            public BudgetCheckResult checkBeforeModelCall(AgentContext ctx, String node, String input) {
                return BudgetCheckResult.allowed(0L, 0L, 0L, Long.MAX_VALUE);
            }

            @Override
            public BudgetCheckResult checkBeforeModelCall(AgentContext ctx, String node, String model,
                                                          cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose purpose,
                                                          String input, int requestedMaxTokens) {
                return BudgetCheckResult.allowed(0L, 0L, 0L, Long.MAX_VALUE);
            }

            @Override
            public cn.lunalhx.ai.domain.agent.model.valobj.TraceCost recordModelUsage(AgentContext ctx, TokenUsage usage) {
                return null;
            }

            @Override
            public cn.lunalhx.ai.domain.agent.model.valobj.TraceCost recordModelUsage(AgentContext ctx, String model,
                                                                                       TokenUsage usage) {
                return null;
            }
        };
    }
}
