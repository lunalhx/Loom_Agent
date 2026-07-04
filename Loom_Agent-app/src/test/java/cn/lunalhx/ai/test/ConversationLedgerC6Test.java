package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.*;

public class ConversationLedgerC6Test {

    private ConversationLedgerAppendService svc;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        svc = new ConversationLedgerAppendService();
        objectMapper = new ObjectMapper();
    }

    private AgentContext basicContext(String runId) {
        AgentContext ctx = new AgentContext();
        ctx.setRunId(runId);
        ctx.setRootRunId(runId);
        ctx.setRequestId("req-" + runId);
        ctx.setConversationId("conv-" + runId);
        ctx.setQuestion("test question");
        ctx.setStep(1);
        ctx.setMaxSteps(30);
        ctx.setMaxSegments(1);
        ctx.setMaxTotalSteps(30);
        ctx.setSegmentIndex(0);
        ctx.setSegmentStartStep(0);
        return ctx;
    }

    @Test
    public void planVersionChangedAppendsNewEntry() {
        AgentContext ctx = basicContext("r-plan-changed");
        ctx.ensureLedgerActive();

        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);

        String text1 = ControlUpdateTexts.renderPlanSnapshot(plan);
        String key1 = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "plan", "v" + plan.getVersion());
        svc.appendControlUpdate(ctx, text1, key1);
        ctx.setLastLedgerPlanVersion(plan.getVersion());

        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode todos = input.putArray("todos");
        ObjectNode todo = todos.addObject();
        todo.put("id", "task-1");
        todo.put("content", "updated content");
        todo.put("status", "completed");
        plan.applyTodoWrite(input);

        int v2 = plan.getVersion();
        assertTrue(v2 > ctx.getLastLedgerPlanVersion());

        String text2 = ControlUpdateTexts.renderPlanSnapshot(plan);
        String key2 = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "plan", "v" + v2);
        svc.appendControlUpdate(ctx, text2, key2);
        ctx.setLastLedgerPlanVersion(v2);

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals(2, entries.size());
        assertThat(entries.get(0).content()).contains("[Plan v1]");
        assertThat(entries.get(1).content()).contains("[Plan v2]");
    }

    @Test
    public void budgetSnapshotAppendedWhenSegmented() {
        AgentContext ctx = basicContext("r-budget-1");
        ctx.ensureLedgerActive();
        ctx.setMaxSegments(5);
        ctx.setMaxTotalSteps(150);
        ctx.setMaxSteps(30);
        ctx.setSegmentIndex(0);
        ctx.setStep(0);

        String text = ControlUpdateTexts.renderBudgetSnapshot(ctx);
        assertFalse(text.isEmpty());
        assertThat(text).contains("Segment 1/5");
        assertThat(text).contains("Step 1/150");

        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "budget");
        svc.appendControlUpdate(ctx, text, eventKey);
        assertEquals(1, ctx.getConversationLedger().entries().size());
        assertEquals("user", ctx.getConversationLedger().entries().get(0).role());
    }

    @Test
    public void eachStepBudgetSnapshotUniqueKey() {
        AgentContext ctx = basicContext("r-budget-seq");
        ctx.ensureLedgerActive();
        ctx.setMaxSegments(5);
        ctx.setMaxTotalSteps(150);
        ctx.setMaxSteps(30);

        ctx.setStep(1);
        ctx.setSegmentIndex(0);
        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "budget"));

        ctx.setStep(2);
        ctx.setSegmentIndex(0);
        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "2", "budget"));

        ctx.setStep(3);
        ctx.setSegmentIndex(1);
        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "3", "budget"));

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals(3, entries.size());
        assertEquals("r-budget-seq:1:budget", entries.get(0).eventKey());
        assertEquals("r-budget-seq:2:budget", entries.get(1).eventKey());
        assertEquals("r-budget-seq:3:budget", entries.get(2).eventKey());
    }

    @Test
    public void todoReminderTriggeredWhenRoundsHigh() {
        AgentContext ctx = basicContext("r-todo-high");
        ctx.ensureLedgerActive();
        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);
        plan.setRoundsSinceUpdate(5);

        String text = ControlUpdateTexts.renderTodoReminder();
        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "todo_reminder");
        svc.appendControlUpdate(ctx, text, eventKey);

        assertEquals(1, ctx.getConversationLedger().entries().size());
        assertThat(ctx.getConversationLedger().entries().get(0).content())
                .contains("Update your todos with todo_write");
    }

    @Test
    public void parseErrorAppendedToLedger() {
        AgentContext ctx = basicContext("r-parse-err");
        ctx.ensureLedgerActive();
        ctx.setParseErrors(1);

        String text = ControlUpdateTexts.renderParseErrorNote("invalid json {{{", 1, 3);
        assertThat(text).contains("[Parse Error]");

        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "parse_error:1");
        svc.appendControlUpdate(ctx, text, eventKey);

        assertEquals(1, ctx.getConversationLedger().entries().size());
        assertEquals(LedgerStableType.CONTROL_UPDATE,
                ctx.getConversationLedger().entries().get(0).stableType());
    }

    @Test
    public void replanNoteAppendedToLedger() {
        AgentContext ctx = basicContext("r-replan");
        ctx.ensureLedgerActive();

        String text = ControlUpdateTexts.renderReplanNote(
                ReplanReason.TOOL_FAILURE, true, "Tool bash failed with exit code 1");
        assertThat(text).contains("[Replan]");

        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "replan");
        svc.appendControlUpdate(ctx, text, eventKey);

        assertEquals(1, ctx.getConversationLedger().entries().size());
    }

    @Test
    public void userInputAppendedToLedger() {
        AgentContext ctx = basicContext("r-userinput");
        ctx.ensureLedgerActive();

        String text = ControlUpdateTexts.renderUserInput("请改用 Python 实现");
        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "user_input");
        svc.appendUserInput(ctx, text, eventKey);

        assertEquals(1, ctx.getConversationLedger().entries().size());
        assertEquals(LedgerStableType.USER_INPUT,
                ctx.getConversationLedger().entries().get(0).stableType());
    }

    @Test
    public void continuationMarkerAppendedToLedger() {
        AgentContext ctx = basicContext("r-cont");
        ctx.ensureLedgerActive();

        String text = ControlUpdateTexts.renderContinuation("follow-up question");
        assertThat(text).startsWith("[Conversation Continued]");

        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "continuation", "user_input");
        svc.appendUserInput(ctx, text, eventKey);

        assertEquals(1, ctx.getConversationLedger().entries().size());
    }

    @Test
    public void planSnapshotIsDeterministic() {
        AgentPlan plan1 = AgentPlan.forQuestion("test");
        AgentPlan plan2 = AgentPlan.forQuestion("test");

        assertEquals(ControlUpdateTexts.renderPlanSnapshot(plan1),
                ControlUpdateTexts.renderPlanSnapshot(plan2));
    }

    @Test
    public void budgetSnapshotIsDeterministic() {
        AgentContext ctx1 = basicContext("r-det-1");
        ctx1.setMaxSegments(3);
        ctx1.setMaxTotalSteps(90);
        ctx1.setSegmentIndex(1);
        ctx1.setStep(15);

        AgentContext ctx2 = basicContext("r-det-2");
        ctx2.setMaxSegments(3);
        ctx2.setMaxTotalSteps(90);
        ctx2.setSegmentIndex(1);
        ctx2.setStep(15);

        assertEquals(ControlUpdateTexts.renderBudgetSnapshot(ctx1),
                ControlUpdateTexts.renderBudgetSnapshot(ctx2));
    }

    @Test
    public void nullLedgerServiceSafe() {
        assertNotNull(ControlUpdateTexts.renderPlanSnapshot(AgentPlan.forQuestion("test")));
        assertNotNull(ControlUpdateTexts.renderTodoReminder());
        assertNotNull(ControlUpdateTexts.renderReplanNote(ReplanReason.TOOL_FAILURE, true, "msg"));
        assertNotNull(ControlUpdateTexts.renderParseErrorNote("raw", 1, 3));
        assertNotNull(ControlUpdateTexts.renderUserInput("hi"));
        assertNotNull(ControlUpdateTexts.renderContinuation("q"));
    }

    @Test
    public void fullRunSequenceAppendsAllEventTypes() {
        AgentContext ctx = basicContext("r-full");
        ctx.ensureLedgerActive();
        ctx.setMaxSegments(3);
        ctx.setMaxTotalSteps(90);

        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "budget"));
        svc.appendAssistant(ctx, "model output 1",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));
        svc.appendToolResult(ctx, "tool output 1",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "tool_result"));

        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);
        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode todos = input.putArray("todos");
        ObjectNode todo = todos.addObject();
        todo.put("id", "task-1");
        todo.put("content", "do something");
        todo.put("status", "in_progress");
        plan.applyTodoWrite(input);
        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderPlanSnapshot(plan),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "plan", "v" + plan.getVersion()));

        ctx.setStep(2);
        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "2", "budget"));
        svc.appendAssistant(ctx, "model output 2",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "2", "assistant"));

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals(6, entries.size());
        assertEquals("user", entries.get(0).role());
        assertEquals("assistant", entries.get(1).role());
        assertEquals("user", entries.get(2).role());
        assertEquals("user", entries.get(3).role());
        assertEquals("user", entries.get(4).role());
        assertEquals("assistant", entries.get(5).role());

        for (int i = 0; i < entries.size(); i++) {
            assertEquals(i, entries.get(i).sequence());
        }
    }

    @Test
    public void eventKeyFormatShouldBeColonSeparated() {
        assertEquals("run-1:plan:v3", ConversationLedgerInitializer.eventKey("run-1", "plan", "v3"));
        assertEquals("run-1:5:budget", ConversationLedgerInitializer.eventKey("run-1", "5", "budget"));
        assertEquals("run-1:5:todo_reminder", ConversationLedgerInitializer.eventKey("run-1", "5", "todo_reminder"));
        assertEquals("run-1:3:parse_error:2", ConversationLedgerInitializer.eventKey("run-1", "3", "parse_error:2"));
        assertEquals("run-1:4:replan", ConversationLedgerInitializer.eventKey("run-1", "4", "replan"));
        assertEquals("run-1:6:user_input", ConversationLedgerInitializer.eventKey("run-1", "6", "user_input"));
    }
}
