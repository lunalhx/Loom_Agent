package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.domain.agent.service.observability.ModelCallTraceContext;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelCapability;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.gateway.ResilientModelGateway;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Meter;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ResilientModelGatewayTest {

    @Test
    public void preflightContextLimitShouldReportContextOverflow() {
        AtomicInteger calls = new AtomicInteger();
        ModelRuntimeProperties properties = properties(3, 4);
        properties.getModelCapabilities().put("deepseek-v4-flash",
                new ModelCapability("deepseek-v4-flash", 100L, 4096, true, true));
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Mono.just(ModelChatResult.builder().content("{}").finishReason("stop").build());
            }
        }, new InMemoryTraceRecorder(), properties);

        ModelGatewayException error = assertThrows(ModelGatewayException.class,
                () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION))
                        .block(Duration.ofSeconds(2)));

        assertEquals(ModelErrorCode.CONTEXT_OVERFLOW, error.getErrorCode());
        assertEquals(0, calls.get());
    }

    @Test
    public void shouldNotRetryNonRetryableCompleteError() {
        AtomicInteger calls = new AtomicInteger();
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
        }, new InMemoryTraceRecorder());

        assertThrows(ModelGatewayException.class, () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION))
                .block(Duration.ofSeconds(2)));
        assertEquals(1, calls.get());
    }

    @Test
    public void shouldRetryRetryableCompleteErrorAndRecordTrace() {
        AtomicInteger calls = new AtomicInteger();
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.incrementAndGet() == 1) {
                    return Mono.error(new ModelGatewayException(ModelErrorCode.PROVIDER_UNAVAILABLE, "503", true, 503, null));
                }
                return Mono.just(ModelChatResult.builder().content("{}").finishReason("stop").build());
            }
        }, traceRecorder);

        AgentContext context = context("retry-run");
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }

        assertEquals(2, calls.get());
        assertTrue(traceRecorder.timeline("retry-run").stream()
                .anyMatch(event -> "model_retry_attempt".equals(event.getEventType())
                        && "error".equals(event.getStatus())));
        assertTrue(traceRecorder.timeline("retry-run").stream()
                .anyMatch(event -> "model_retry_attempt".equals(event.getEventType())
                        && "success".equals(event.getStatus())));
    }

    @Test
    public void shouldOpenCircuitOnlyForSameCapability() {
        AtomicInteger agentDecisionCalls = new AtomicInteger();
        AtomicInteger replanCalls = new AtomicInteger();
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (ModelCapabilities.COMPLETE_REPLAN.equals(prompt.getCapability())) {
                    replanCalls.incrementAndGet();
                    return Mono.just(ModelChatResult.builder().content("{}").finishReason("stop").build());
                }
                agentDecisionCalls.incrementAndGet();
                return Mono.error(new ModelGatewayException(ModelErrorCode.PROVIDER_UNAVAILABLE, "503", true, 503, null));
            }
        }, new InMemoryTraceRecorder(), 1, 2);

        assertThrows(ModelGatewayException.class, () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION))
                .block(Duration.ofSeconds(2)));
        assertThrows(ModelGatewayException.class, () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION))
                .block(Duration.ofSeconds(2)));
        assertThrows(CallNotPermittedException.class, () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION))
                .block(Duration.ofSeconds(2)));

        ModelChatResult result = gateway.complete(prompt(ModelCapabilities.COMPLETE_REPLAN)).block(Duration.ofSeconds(2));
        assertEquals("{}", result.getContent());
        assertEquals(2, agentDecisionCalls.get());
        assertEquals(1, replanCalls.get());
    }

    @Test
    public void shouldNotRetryStreamAfterTokenEmitted() {
        AtomicInteger calls = new AtomicInteger();
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Flux.concat(
                        Flux.just(ModelStreamChunk.builder().content("a").build()),
                        Flux.error(new ModelGatewayException(ModelErrorCode.PROVIDER_UNAVAILABLE, "503", true, 503, null)));
            }
        }, new InMemoryTraceRecorder());

        assertThrows(ModelGatewayException.class, () -> gateway.stream(prompt(ModelCapabilities.STREAM_CHAT))
                .collectList()
                .block(Duration.ofSeconds(2)));
        assertEquals(1, calls.get());
    }

    @Test
    public void shouldUseOnlyLowCardinalityMetricTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder().content("{}").finishReason("stop").build());
            }
        }, new InMemoryTraceRecorder(), meterRegistry, 3, 4);

        gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));

        Set<String> forbidden = Set.of("runId", "traceId", "requestId", "conversationId", "userId",
                "workspace", "question", "run_id", "trace_id", "request_id", "conversation_id", "user_id");
        for (Meter meter : meterRegistry.getMeters()) {
            for (Meter.Id id : java.util.List.of(meter.getId())) {
                assertTrue(id.getTags().stream().noneMatch(tag -> forbidden.contains(tag.getKey())));
            }
        }
    }

    @Test
    public void shouldStopBeforeRetryAfterExceedsDeadline() {
        AtomicInteger calls = new AtomicInteger();
        ModelRuntimeProperties properties = properties(4, 4);
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Mono.error(new ModelGatewayException(ModelErrorCode.RATE_LIMITED,
                        "429", true, 429, 5000L, prompt.getModel(), null));
            }
        }, new InMemoryTraceRecorder(), properties);
        ChatPrompt prompt = prompt(ModelCapabilities.COMPLETE_AGENT_DECISION);
        prompt.setDeadlineEpochMs(System.currentTimeMillis() + 30L);

        ModelGatewayException error = assertThrows(ModelGatewayException.class,
                () -> gateway.complete(prompt).block(Duration.ofSeconds(1)));

        assertEquals(ModelErrorCode.MODEL_CALL_TIMEOUT, error.getErrorCode());
        assertEquals(1, calls.get());
    }

    @Test
    public void shouldSwitchFallbackAfterConsecutiveOverload() {
        List<String> models = new ArrayList<>();
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        ModelRuntimeProperties properties = properties(5, 10);
        properties.getResilience().setOverloadFallbackThreshold(3);
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
                return Mono.error(new ModelGatewayException(ModelErrorCode.PROVIDER_OVERLOADED,
                        "503", true, 503, null));
            }
        }, traceRecorder, properties);

        AgentContext context = context("fallback-run");
        ModelChatResult result;
        try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
            result = gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2));
        }

        assertEquals(List.of("deepseek-v4-flash", "deepseek-v4-flash", "deepseek-v4-flash", "deepseek-v4-pro"), models);
        assertEquals("deepseek-v4-pro", result.getActualModel());
        assertTrue(traceRecorder.timeline("fallback-run").stream()
                .anyMatch(event -> "model_fallback_switched".equals(event.getEventType())
                        && "deepseek-v4-flash".equals(event.getMetadata().get("fromModel"))
                        && "deepseek-v4-pro".equals(event.getMetadata().get("toModel"))));
    }

    @Test
    public void shouldNotSwitchWhenFallbackEqualsCurrentModel() {
        List<String> models = new ArrayList<>();
        ModelRuntimeProperties properties = properties(4, 10);
        properties.getResilience().setOverloadFallbackThreshold(1);
        properties.getResilience().setFallbackModel("deepseek-v4-flash");
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                models.add(prompt.getModel());
                return Mono.error(new ModelGatewayException(ModelErrorCode.PROVIDER_OVERLOADED,
                        "503", true, 503, null));
            }
        }, new InMemoryTraceRecorder(), properties);

        assertThrows(ModelGatewayException.class,
                () -> gateway.complete(prompt(ModelCapabilities.COMPLETE_AGENT_DECISION)).block(Duration.ofSeconds(2)));

        assertEquals(List.of("deepseek-v4-flash", "deepseek-v4-flash",
                "deepseek-v4-flash", "deepseek-v4-flash"), models);
    }

    @Test
    public void shouldRetryStreamingInsufficientSystemResourceBeforeToken() {
        AtomicInteger calls = new AtomicInteger();
        ResilientModelGateway gateway = gateway(new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                if (calls.incrementAndGet() == 1) {
                    return Flux.just(ModelStreamChunk.builder()
                            .finishReason("insufficient_system_resource").build());
                }
                return Flux.just(ModelStreamChunk.builder().content("ok").finishReason("stop").build());
            }
        }, new InMemoryTraceRecorder());

        List<ModelStreamChunk> chunks = gateway.stream(prompt(ModelCapabilities.STREAM_CHAT))
                .collectList().block(Duration.ofSeconds(2));

        assertEquals(2, calls.get());
        assertEquals("ok", chunks.getFirst().getContent());
    }

    private ResilientModelGateway gateway(ModelGateway delegate, InMemoryTraceRecorder traceRecorder) {
        return gateway(delegate, traceRecorder, 3, 4);
    }

    private ResilientModelGateway gateway(ModelGateway delegate,
                                         InMemoryTraceRecorder traceRecorder,
                                         int maxAttempts,
                                         int slidingWindowSize) {
        ModelRuntimeProperties properties = new ModelRuntimeProperties();
        properties.setFirstTokenTimeoutMs(500L);
        properties.getResilience().setRetryMaxAttempts(maxAttempts);
        properties.getResilience().setRetryBackoffInitialMs(1L);
        properties.getResilience().setRetryBackoffMaxMs(2L);
        properties.getResilience().setCircuitSlidingWindowSize(slidingWindowSize);
        properties.getResilience().setCircuitOpenStateWaitMs(5000L);
        properties.getResilience().setCircuitFailureRateThreshold(50.0F);
        return new ResilientModelGateway(
                delegate,
                properties,
                traceRecorder,
                new SimpleMeterRegistry(),
                new MockEnvironment().withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"));
    }

    private ResilientModelGateway gateway(ModelGateway delegate,
                                          InMemoryTraceRecorder traceRecorder,
                                          ModelRuntimeProperties properties) {
        return new ResilientModelGateway(
                delegate,
                properties,
                traceRecorder,
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

    private ResilientModelGateway gateway(ModelGateway delegate,
                                         InMemoryTraceRecorder traceRecorder,
                                         SimpleMeterRegistry meterRegistry,
                                         int maxAttempts,
                                         int slidingWindowSize) {
        ModelRuntimeProperties properties = new ModelRuntimeProperties();
        properties.setFirstTokenTimeoutMs(500L);
        properties.getResilience().setRetryMaxAttempts(maxAttempts);
        properties.getResilience().setRetryBackoffInitialMs(1L);
        properties.getResilience().setRetryBackoffMaxMs(2L);
        properties.getResilience().setCircuitSlidingWindowSize(slidingWindowSize);
        properties.getResilience().setCircuitOpenStateWaitMs(5000L);
        properties.getResilience().setCircuitFailureRateThreshold(50.0F);
        return new ResilientModelGateway(
                delegate,
                properties,
                traceRecorder,
                meterRegistry,
                new MockEnvironment().withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash"));
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
