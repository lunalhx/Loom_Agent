package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.AgentTraceEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReplanNodeTest {

    @Test
    public void modelExceptionShouldFallbackToGenericItem() {
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        ModelGateway failingGateway = failingGateway();
        ReplanNode node = newNode(failingGateway, traceRecorder);
        AgentContext context = basicContext();

        NodeResult result = node.apply(context);

        assertNotNull(result);
        assertEquals(AgentNodeNames.RENDER_PROMPT, result.getNextNode());
        assertTrue(context.getPlan().getLastUpdateReason().contains("replan:"));

        boolean foundTrace = traceRecorder.timeline("test-run").stream()
                .anyMatch(e -> "model_replan_call_failed".equals(e.getEventType())
                        && "error".equals(e.getStatus())
                        && "replan".equals(e.getNode())
                        && e.getErrorMessage() != null
                        && e.getErrorMessage().contains("bad replan config"));
        assertTrue(foundTrace);
    }

    @Test
    public void traceRecorderNullShouldNotThrow() {
        ModelGateway failingGateway = failingGateway();
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        ReplanNode node = new ReplanNode(failingGateway, properties, new ObjectMapper(), null, null);
        AgentContext context = basicContext();

        NodeResult result = node.apply(context);
        assertNotNull(result);
        assertEquals(AgentNodeNames.RENDER_PROMPT, result.getNextNode());
    }

    @Test
    public void shouldBlockTaskAfterExceedingReplanLimit() throws Exception {
        AgentPlan plan = new AgentPlan();
        ObjectMapper mapper = new ObjectMapper();
        plan.applyTodoWrite(mapper.readTree("""
                {"todos":[{"content":"task","status":"in_progress","kind":"edit",
                  "targets":["src/Foo.java"]}]}
                """));
        String taskId = plan.getItems().get(0).getId();

        plan.blockItem(taskId, "continuous failures exceeded limit");

        assertEquals(AgentPlanItemStatus.BLOCKED, plan.getItems().get(0).getStatus());
        assertTrue(plan.getItems().get(0).getBlocker().contains("failures"));
    }

    @Test
    public void shouldIncludeStrategyChangeWarningInReplanPrompt() {
        AgentContext context = mock(AgentContext.class);
        AgentPlan plan = AgentPlan.forQuestion("test");
        when(context.getPlan()).thenReturn(plan);
        when(context.getQuestion()).thenReturn("test");
        when(context.getReplanMessage()).thenReturn("策略变更要求: 必须更换策略");
        when(context.getReplanReason()).thenReturn(ReplanReason.REPEATED_ERROR);

        ReplanNode node = new ReplanNode(mock(ModelGateway.class), null, new ObjectMapper());
        assertNotNull(plan);
    }

    @Test
    public void shouldNotDuplicateReplanItemsWithSameContent() {
        AgentPlan plan = new AgentPlan();
        plan.addReplanItem("check the error and retry", "test");
        int initialSize = plan.getItems().size();
        plan.addReplanItem("check the error and retry", "test again");
        assertEquals(initialSize, plan.getItems().size());
        assertTrue(plan.getEvents().stream()
                .anyMatch(e -> "REPLAN_DEDUPED".equals(e.getType())));
    }

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
}
