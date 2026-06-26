package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelCapability;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ReplanNodeTest {

    private static ModelGateway failingGateway() {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.error(new RuntimeException("bad replan config"));
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.error(new RuntimeException("bad replan config"));
            }

            @Override
            public ModelCapability capability(String model) {
                return null;
            }
        };
    }

    private ReplanNode newNode(ModelGateway modelGateway, InMemoryTraceRecorder traceRecorder) {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        return new ReplanNode(modelGateway, properties, new ObjectMapper(), traceRecorder, null);
    }

    private AgentContext basicContext() {
        AgentContext context = new AgentContext();
        context.setRunId("test-run");
        context.setRootRunId("test-run");
        context.setTraceId("test-trace");
        context.setRequestId("req-1");
        context.setConversationId("conv-1");
        context.setQuestion("test question");
        context.setMaxSteps(5);
        context.setStep(1);
        context.setStartedAt(Instant.now());
        context.setCurrentSpanId("test-span");
        context.setReplanReason(ReplanReason.TOOL_FAILURE);
        context.setPlan(AgentPlan.forQuestion("test question"));
        return context;
    }

    @Test
    public void modelExceptionShouldFallbackAndRecordTrace() {
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        ModelGateway failingGateway = failingGateway();
        ReplanNode node = newNode(failingGateway, traceRecorder);
        AgentContext context = basicContext();

        NodeResult result = node.apply(context);

        // 不抛异常
        assertNotNull(result);
        // 下一节点仍是 render_prompt
        assertEquals("next node should be render_prompt after fallback",
                AgentNodeNames.RENDER_PROMPT, result.getNextNode());
        // fallback replan item 已追加
        assertTrue("plan lastUpdateReason should contain replan:",
                context.getPlan().getLastUpdateReason() != null
                        && context.getPlan().getLastUpdateReason().contains("replan:"));

        // trace 中应包含 model_replan_call_failed
        boolean foundTrace = traceRecorder.timeline("test-run").stream()
                .anyMatch(e -> "model_replan_call_failed".equals(e.getEventType())
                        && "error".equals(e.getStatus())
                        && "replan".equals(e.getNode())
                        && e.getErrorMessage() != null
                        && e.getErrorMessage().contains("bad replan config"));
        assertTrue("timeline should contain model_replan_call_failed with error info", foundTrace);
    }

    @Test
    public void modelExceptionShouldNotThrow() {
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        ModelGateway failingGateway = failingGateway();
        ReplanNode node = newNode(failingGateway, traceRecorder);
        AgentContext context = basicContext();

        // 不应抛异常
        NodeResult result = node.apply(context);
        assertNotNull(result);
    }

    @Test
    public void traceRecorderNullShouldNotThrow() {
        ModelGateway failingGateway = failingGateway();
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        ReplanNode node = new ReplanNode(failingGateway, properties, new ObjectMapper(), null, null);
        AgentContext context = basicContext();

        // traceRecorder 为 null 时也不应抛异常
        NodeResult result = node.apply(context);
        assertNotNull(result);
        assertEquals(AgentNodeNames.RENDER_PROMPT, result.getNextNode());
    }

    @Test
    public void traceEventShouldHaveMetadata() {
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        ModelGateway failingGateway = failingGateway();
        ReplanNode node = newNode(failingGateway, traceRecorder);
        AgentContext context = basicContext();
        context.setReplanReason(ReplanReason.APPROVAL_REJECTED);

        node.apply(context);

        AgentTraceEvent traceEvent = traceRecorder.timeline("test-run").stream()
                .filter(e -> "model_replan_call_failed".equals(e.getEventType()))
                .findFirst()
                .orElse(null);
        assertNotNull("should have model_replan_call_failed event", traceEvent);
        assertEquals("error", traceEvent.getStatus());
        assertEquals("replan", traceEvent.getNode());
        assertTrue("duration should be >= 0", traceEvent.getDurationMs() >= 0);
        assertEquals("Replan model call failed, fallback to generic item", traceEvent.getSummary());
        assertNotNull("errorMessage should not be null", traceEvent.getErrorMessage());
        assertTrue("errorMessage should contain exception message",
                traceEvent.getErrorMessage().contains("bad replan config"));
        assertNotNull("metadata should not be null", traceEvent.getMetadata());
        assertEquals("CONTROL_JSON", traceEvent.getMetadata().get("purpose"));
        assertEquals("complete.replan", traceEvent.getMetadata().get("capability"));
        assertEquals("generic_item", traceEvent.getMetadata().get("fallback"));
        assertEquals("APPROVAL_REJECTED", traceEvent.getMetadata().get("replanReason"));
    }
}
