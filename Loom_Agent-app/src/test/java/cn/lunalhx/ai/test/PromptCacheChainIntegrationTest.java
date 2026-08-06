package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.service.execution.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.PromptCacheCapability;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 执行链集成：PromptBuildNode → ModelPromptFactory → ModelCallExecutor。
 * 验证工具写入后仍复用同一个 stable cache key；trace/metadata 连通。
 */
public class PromptCacheChainIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    public void toolRoundAndFinalRoundReuseSameCacheKey() {
        CopyOnWriteArrayList<ChatPrompt> captured = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) { return Flux.empty(); }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                captured.add(prompt);
                int call = calls.getAndIncrement();
                String content = call == 0
                        ? "<tool>{\"name\":\"read_file\",\"args\":{\"path\":\"A.java\"}}</tool>"
                        : "<final>done</final>";
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .usage(cn.lunalhx.ai.domain.model.valobj.TokenUsage.builder()
                                .promptTokens(100)
                                .completionTokens(50)
                                .totalTokens(150)
                                .promptCacheHitTokens(80)
                                .promptCacheMissTokens(20)
                                .cacheHit(true)
                                .cacheCapability("supported")
                                .cacheStatus(cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.HIT)
                                .build())
                        .build());
            }
        };

        AgentTool readTool = new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name("read_file").description("Read")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("class A {}", false, 1L);
            }
        };

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(gateway)
                .addTool(readTool)
                .properties(AgentRuntimeTestFixture.standardProperties())
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("read A.java and summarize")
                        .maxSteps(3)
                        .build())
                .collectList().block(TIMEOUT);

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.ANSWER));
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.DONE));

        // At least one tool round + one final round
        assertEquals(2, captured.size());
        String firstKey = captured.get(0).getStablePrefixSignature();
        assertNotNull(firstKey);
        for (ChatPrompt p : captured) {
            assertEquals(firstKey, p.getStablePrefixSignature());
        }
        // 工具写入后签名不变 → 由签名派生的 cache key 也保持不变（由 gateway 派生）
        assertEquals(captured.get(0).getStablePrefixSignature(), captured.get(1).getStablePrefixSignature());
    }

    @Test
    public void disabledFlagProducesNonePolicyAndNoCacheKey() {
        CopyOnWriteArrayList<ChatPrompt> captured = new CopyOnWriteArrayList<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) { return Flux.empty(); }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                captured.add(prompt);
                return Mono.just(ModelChatResult.builder()
                        .content("<final>ok</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };

        cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties props =
                AgentRuntimeTestFixture.standardProperties();
        props.getFeatureFlags().setPromptCache(false);

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(gateway)
                .properties(props)
                .buildAgentLoop();

        service.ask(AgentQuestion.builder().question("hi").maxSteps(2).build())
                .collectList().block(TIMEOUT);

        assertFalse(captured.isEmpty());
        for (ChatPrompt p : captured) {
            assertEquals(ChatPrompt.CachePolicy.NONE, p.getCachePolicy());
            assertNull(p.getPromptCacheKey());
        }
    }

    @Test
    public void declaredCapabilityReachesPrompt() {
        // ModelPromptFactory resolves the capability from the run config model
        // properties; a fixture without prompt-cache config stays UNSUPPORTED.
        CopyOnWriteArrayList<ChatPrompt> captured = new CopyOnWriteArrayList<>();
        ModelGateway gateway = recordingGateway(captured, "<final>ok</final>");

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(gateway)
                .properties(AgentRuntimeTestFixture.standardProperties())
                .buildAgentLoop();

        service.ask(AgentQuestion.builder().question("hi").maxSteps(2).build())
                .collectList().block(TIMEOUT);

        assertFalse(captured.isEmpty());
        assertEquals(PromptCacheCapability.UNSUPPORTED, captured.get(0).getPromptCacheCapability());
        // capability 不支持时缓存 key 为空，但签名仍携带
        assertNotNull(captured.get(0).getStablePrefixSignature());
    }

    @Test
    public void traceMetadataCarriesCacheDimensions() {
        CopyOnWriteArrayList<ChatPrompt> captured = new CopyOnWriteArrayList<>();
        ModelGateway gateway = recordingGateway(captured, "<final>ok</final>");
        cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder traces =
                new cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder();

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(gateway)
                .traceRecorder(traces)
                .properties(AgentRuntimeTestFixture.standardProperties())
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("hi").maxSteps(2).build())
                .collectList().block(TIMEOUT);
        assertNotNull(events);

        String runId = events.get(0).getRunId();
        List<cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent> usageEvents = traces.timeline(runId)
                .stream()
                .filter(e -> "model_usage".equals(e.getEventType()))
                .toList();
        assertFalse(usageEvents.isEmpty());
        cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent last = usageEvents.get(usageEvents.size() - 1);
        assertNotNull(last.getTokenUsage());
        // 三态 hit status 与 capability 进入 trace metadata
        assertEquals("HIT", last.getMetadata().get("promptCacheStatus"));
        assertEquals("supported", last.getMetadata().get("promptCacheCapability"));
        assertTrue(last.getMetadata().containsKey("stablePrefixFingerprint"));
        // 高基数标签（runId/conversationId/prompt 文本/cache key）不得出现
        assertFalse(last.getMetadata().containsKey("runId"));
        assertFalse(last.getMetadata().containsKey("promptText"));
        assertFalse(last.getMetadata().containsKey("cacheKey"));
    }

    private static ModelGateway recordingGateway(List<ChatPrompt> captured, String output) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) { return Flux.empty(); }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                captured.add(prompt);
                return Mono.just(ModelChatResult.builder()
                        .content(output)
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .usage(cn.lunalhx.ai.domain.model.valobj.TokenUsage.builder()
                                .promptTokens(100)
                                .completionTokens(50)
                                .totalTokens(150)
                                .promptCacheHitTokens(80)
                                .promptCacheMissTokens(20)
                                .cacheHit(true)
                                .cacheCapability("supported")
                                .cacheStatus(cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.HIT)
                                .build())
                        .build());
            }
        };
    }
}
