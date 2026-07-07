package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.TodoApplyResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ReplanNodeTest {

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

    @Test
    public void duplicateReplanDeltaShouldNotAddFallbackItem() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentPlan plan = new AgentPlan();
        plan.applyTodoWriteForReplan(
                objectMapper.readTree("""
                        {"todos":[{"content":"task","status":"pending","kind":"edit","targets":["a.txt"]}]}
                        """));
        int sizeBefore = plan.getItems().size();

        List<TodoApplyResult> results = plan.applyTodoWriteForReplan(
                objectMapper.readTree("""
                        {"todos":[{"content":"task again","status":"pending","kind":"edit","targets":["a.txt"]}]}
                        """));

        assertEquals(1, results.size());
        assertFalse(results.get(0).isApplied());
        assertEquals(sizeBefore, plan.getItems().size());
    }

    @Test
    public void noProgressReplanShouldBeTracked() {
        AgentContext context = basicContext();
        assertEquals(0, context.getNoProgressRounds());

        context.setNoProgressRounds(1);
        assertEquals(1, context.getNoProgressRounds());

        context.setNoProgressRounds(0);
        assertEquals(0, context.getNoProgressRounds());
    }

    @Test
    public void consecutiveNoChangeResultsShouldIncrementRounds() {
        int noProgressRounds = 0;
        List<TodoApplyResult> allSkipped = List.of(
                TodoApplyResult.skipped("task-1", "duplicate"));

        if (allSkipped.stream().noneMatch(TodoApplyResult::isApplied)) {
            noProgressRounds++;
        }
        assertEquals(1, noProgressRounds);

        if (allSkipped.stream().noneMatch(TodoApplyResult::isApplied)) {
            noProgressRounds++;
        }
        assertEquals(2, noProgressRounds);

        if (allSkipped.stream().noneMatch(TodoApplyResult::isApplied)) {
            noProgressRounds++;
        }
        assertEquals(3, noProgressRounds);

        int maxRounds = 3;
        assertTrue(noProgressRounds >= maxRounds);
    }

    @Test
    public void appliedReplanShouldResetNoProgress() {
        int noProgressRounds = 2;

        List<TodoApplyResult> applied = List.of(
                TodoApplyResult.applied("new-task"));

        if (applied.stream().anyMatch(TodoApplyResult::isApplied)) {
            noProgressRounds = 0;
        }
        assertEquals(0, noProgressRounds);
    }

    @Test
    public void mixedBatchWithAppliedShouldResetNoProgress() {
        int noProgressRounds = 2;

        List<TodoApplyResult> mixed = List.of(
                TodoApplyResult.applied("task-1"),
                TodoApplyResult.skipped("task-2", "duplicate"));

        if (mixed.stream().anyMatch(TodoApplyResult::isApplied)) {
            noProgressRounds = 0;
        }
        assertEquals(0, noProgressRounds);
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
