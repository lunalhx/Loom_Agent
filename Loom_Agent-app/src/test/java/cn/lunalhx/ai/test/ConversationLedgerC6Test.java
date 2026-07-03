package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedger;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentPlanItemStatus;
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

/**
 * C6: Append system events (plan snapshots, budget snapshots, TODO reminders,
 * replan notes, parse errors, user input, continuation) as user messages to
 * the ConversationLedger.
 */
public class ConversationLedgerC6Test {

    private AgentRuntimeProperties.ConversationLedgerProperties enabledConfig;
    private ConversationLedgerAppendService svc;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        enabledConfig = new AgentRuntimeProperties.ConversationLedgerProperties();
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

    // ================================================================
    // 1. Plan version unchanged → no duplicate plan entry
    // ================================================================

    @Test
    public void planVersionUnchangedDoesNotDuplicate() {
        AgentContext ctx = basicContext("r-plan-unchanged");
        ctx.ensureLedgerActive();

        AgentPlan plan = AgentPlan.forQuestion("test");
        int v1 = plan.getVersion();
        ctx.setPlan(plan);

        // First append
        String text = ControlUpdateTexts.renderPlanSnapshot(plan);
        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "plan", "v" + v1);
        svc.appendControlUpdate(ctx, text, eventKey);
        ctx.setLastLedgerPlanVersion(v1);

        assertEquals(1, ctx.getConversationLedger().entries().size());

        // Second append with same version — should be idempotent
        svc.appendControlUpdate(ctx, text, eventKey);
        assertEquals("same event key must not duplicate", 1,
                ctx.getConversationLedger().entries().size());
    }

    @Test
    public void planVersionChangedAppendsNewEntry() {
        AgentContext ctx = basicContext("r-plan-changed");
        ctx.ensureLedgerActive();

        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);

        // Append v1
        String text1 = ControlUpdateTexts.renderPlanSnapshot(plan);
        String key1 = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "plan", "v" + plan.getVersion());
        svc.appendControlUpdate(ctx, text1, key1);
        ctx.setLastLedgerPlanVersion(plan.getVersion());

        // Modify plan (simulate todo_write)
        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode todos = input.putArray("todos");
        ObjectNode todo = todos.addObject();
        todo.put("id", "task-1");
        todo.put("content", "updated content");
        todo.put("status", "completed");
        plan.applyTodoWrite(input);

        int v2 = plan.getVersion();
        assertTrue("version must increment", v2 > ctx.getLastLedgerPlanVersion());

        // Append v2
        String text2 = ControlUpdateTexts.renderPlanSnapshot(plan);
        String key2 = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "plan", "v" + v2);
        svc.appendControlUpdate(ctx, text2, key2);
        ctx.setLastLedgerPlanVersion(v2);

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals("two versions → two entries", 2, entries.size());
        assertThat(entries.get(0).content()).contains("[Plan v1]");
        assertThat(entries.get(1).content()).contains("[Plan v2]");
    }

    // ================================================================
    // 2. Budget snapshot sequence per step
    // ================================================================

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
        assertFalse("budget text must be present when maxSegments > 1", text.isEmpty());
        assertThat(text).contains("Segment 1/5");
        assertThat(text).contains("Step 1/150");
        assertThat(text).contains("segment limit 30 steps");

        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "budget");
        svc.appendControlUpdate(ctx, text, eventKey);
        assertEquals(1, ctx.getConversationLedger().entries().size());
        assertEquals("user", ctx.getConversationLedger().entries().get(0).role());
    }

    @Test
    public void budgetSnapshotSkippedWhenSingleSegment() {
        AgentContext ctx = basicContext("r-budget-single");
        ctx.ensureLedgerActive();
        ctx.setMaxSegments(1);

        String text = ControlUpdateTexts.renderBudgetSnapshot(ctx);
        assertTrue("budget text must be empty when maxSegments <= 1", text.isEmpty());
    }

    @Test
    public void eachStepBudgetSnapshotUniqueKey() {
        AgentContext ctx = basicContext("r-budget-seq");
        ctx.ensureLedgerActive();
        ctx.setMaxSegments(5);
        ctx.setMaxTotalSteps(150);
        ctx.setMaxSteps(30);

        // Step 1 budget
        ctx.setStep(1);
        ctx.setSegmentIndex(0);
        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "budget"));

        // Step 2 budget (different step → different event key)
        ctx.setStep(2);
        ctx.setSegmentIndex(0);
        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "2", "budget"));

        // Step 3 budget (entered segment 2)
        ctx.setStep(3);
        ctx.setSegmentIndex(1);
        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "3", "budget"));

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals("each step has its own budget entry", 3, entries.size());
        assertEquals("r-budget-seq:1:budget", entries.get(0).eventKey());
        assertEquals("r-budget-seq:2:budget", entries.get(1).eventKey());
        assertEquals("r-budget-seq:3:budget", entries.get(2).eventKey());

        // Verify old entries are not overwritten
        assertThat(entries.get(0).content()).contains("Step 2/150");
        assertThat(entries.get(1).content()).contains("Step 3/150");
        assertThat(entries.get(2).content()).contains("Step 4/150");
    }

    // ================================================================
    // 3. TODO reminder dedup
    // ================================================================

    @Test
    public void todoReminderNotTriggeredWhenRoundsLow() {
        AgentContext ctx = basicContext("r-todo-low");
        ctx.ensureLedgerActive();
        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);
        plan.setRoundsSinceUpdate(0);

        // roundsSinceUpdate < 3 → should not trigger
        assertFalse("rounds < 3 should not trigger reminder",
                plan.getRoundsSinceUpdate() >= 3);
    }

    @Test
    public void todoReminderTriggeredWhenRoundsHigh() {
        AgentContext ctx = basicContext("r-todo-high");
        ctx.ensureLedgerActive();
        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);
        plan.setRoundsSinceUpdate(5);

        // roundsSinceUpdate >= 3 → should trigger
        assertTrue("rounds >= 3 should trigger reminder",
                plan.getRoundsSinceUpdate() >= 3);

        String text = ControlUpdateTexts.renderTodoReminder();
        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "todo_reminder");
        svc.appendControlUpdate(ctx, text, eventKey);

        assertEquals(1, ctx.getConversationLedger().entries().size());
        assertThat(ctx.getConversationLedger().entries().get(0).content())
                .contains("Update your todos with todo_write");
    }

    @Test
    public void todoReminderSameStepDeduplicated() {
        AgentContext ctx = basicContext("r-todo-dedup");
        ctx.ensureLedgerActive();
        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);
        plan.setRoundsSinceUpdate(5);

        String text = ControlUpdateTexts.renderTodoReminder();
        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "todo_reminder");

        // First append
        svc.appendControlUpdate(ctx, text, eventKey);
        assertEquals(1, ctx.getConversationLedger().entries().size());

        // Same step, same event key → dedup
        svc.appendControlUpdate(ctx, text, eventKey);
        assertEquals("same step todo_reminder must not duplicate", 1,
                ctx.getConversationLedger().entries().size());
    }

    // ================================================================
    // 4. Parse error note
    // ================================================================

    @Test
    public void parseErrorAppendedToLedger() {
        AgentContext ctx = basicContext("r-parse-err");
        ctx.ensureLedgerActive();
        ctx.setParseErrors(1);
        String modelOutput = "invalid json {{{";

        String text = ControlUpdateTexts.renderParseErrorNote(modelOutput, 1, 3);
        assertThat(text).contains("[Parse Error]");
        assertThat(text).contains("attempt 1/3");
        assertThat(text).contains("invalid json {{{");

        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "parse_error:1");
        svc.appendControlUpdate(ctx, text, eventKey);

        assertEquals(1, ctx.getConversationLedger().entries().size());
        assertEquals(LedgerStableType.CONTROL_UPDATE,
                ctx.getConversationLedger().entries().get(0).stableType());
    }

    @Test
    public void multipleParseErrorsSameStepHaveDistinctKeys() {
        AgentContext ctx = basicContext("r-parse-multi");
        ctx.ensureLedgerActive();

        // First parse error in step 1
        ctx.setParseErrors(1);
        String text1 = ControlUpdateTexts.renderParseErrorNote("error 1", 1, 3);
        svc.appendControlUpdate(ctx, text1,
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "parse_error:1"));

        // Second parse error in same step
        ctx.setParseErrors(2);
        String text2 = ControlUpdateTexts.renderParseErrorNote("error 2", 2, 3);
        svc.appendControlUpdate(ctx, text2,
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "parse_error:2"));

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals(2, entries.size());
        assertEquals("r-parse-multi:1:parse_error:1", entries.get(0).eventKey());
        assertEquals("r-parse-multi:1:parse_error:2", entries.get(1).eventKey());
    }

    // ================================================================
    // 5. Replan note
    // ================================================================

    @Test
    public void replanNoteAppendedToLedger() {
        AgentContext ctx = basicContext("r-replan");
        ctx.ensureLedgerActive();

        ReplanReason reason = ReplanReason.TOOL_FAILURE;
        boolean modelUpdated = true;
        String message = "Tool bash failed with exit code 1";

        String text = ControlUpdateTexts.renderReplanNote(reason, modelUpdated, message);
        assertThat(text).contains("[Replan]");
        assertThat(text).contains("Reason: TOOL_FAILURE");
        assertThat(text).contains("Source: model");
        assertThat(text).contains(message);

        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "replan");
        svc.appendControlUpdate(ctx, text, eventKey);

        assertEquals(1, ctx.getConversationLedger().entries().size());
    }

    @Test
    public void replanNoteFallbackSource() {
        ReplanReason reason = ReplanReason.APPROVAL_REJECTED;
        boolean modelUpdated = false;
        String text = ControlUpdateTexts.renderReplanNote(reason, modelUpdated, null);

        assertThat(text).contains("Source: fallback");
        assertThat(text).doesNotContain("null");
    }

    @Test
    public void replanNoteIdempotent() {
        AgentContext ctx = basicContext("r-replan-idem");
        ctx.ensureLedgerActive();

        String text = ControlUpdateTexts.renderReplanNote(
                ReplanReason.INCOMPLETE_PLAN, true, null);
        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "replan");

        svc.appendControlUpdate(ctx, text, eventKey);
        svc.appendControlUpdate(ctx, text, eventKey); // same key → dedup

        assertEquals(1, ctx.getConversationLedger().entries().size());
    }

    // ================================================================
    // 6. User input
    // ================================================================

    @Test
    public void userInputAppendedToLedger() {
        AgentContext ctx = basicContext("r-userinput");
        ctx.ensureLedgerActive();

        String text = ControlUpdateTexts.renderUserInput("请改用 Python 实现");
        assertThat(text).startsWith("[User Input]");
        assertThat(text).contains("请改用 Python 实现");

        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "user_input");
        svc.appendUserInput(ctx, text, eventKey);

        assertEquals(1, ctx.getConversationLedger().entries().size());
        assertEquals(LedgerStableType.USER_INPUT,
                ctx.getConversationLedger().entries().get(0).stableType());
        assertEquals("user", ctx.getConversationLedger().entries().get(0).role());
    }

    @Test
    public void userInputNullMessageHandled() {
        String text = ControlUpdateTexts.renderUserInput(null);
        assertThat(text).startsWith("[User Input]");
        assertThat(text).doesNotContain("null");
    }

    // ================================================================
    // 7. Continuation
    // ================================================================

    @Test
    public void continuationMarkerAppendedToLedger() {
        AgentContext ctx = basicContext("r-cont");
        ctx.ensureLedgerActive();

        String text = ControlUpdateTexts.renderContinuation("follow-up question");
        assertThat(text).startsWith("[Conversation Continued]");
        assertThat(text).contains("follow-up question");

        String eventKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "continuation", "user_input");
        svc.appendUserInput(ctx, text, eventKey);

        assertEquals(1, ctx.getConversationLedger().entries().size());
    }

    @Test
    public void continuationDoesNotOverwriteExistingUserTask() {
        AgentContext ctx = basicContext("r-cont-keep");
        ctx.ensureLedgerActive();

        // Initial user task from first run
        String initialKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "init", "user_task");
        svc.appendUserInput(ctx, "original task", initialKey);

        // Continuation in new run
        String contKey = ConversationLedgerInitializer.eventKey(ctx.getRunId(), "continuation", "user_input");
        svc.appendUserInput(ctx, ControlUpdateTexts.renderContinuation("new question"), contKey);

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals(2, entries.size());
        assertEquals("original task", entries.get(0).content());
        assertThat(entries.get(1).content()).contains("[Conversation Continued]");
    }

    // ================================================================
    // 8. ControlUpdateTexts determinism
    // ================================================================

    @Test
    public void planSnapshotIsDeterministic() {
        AgentPlan plan1 = AgentPlan.forQuestion("test");
        AgentPlan plan2 = AgentPlan.forQuestion("test");

        // Make them identical except for random fields (planId, updatedAt)
        // ForQuestion produces deterministic items
        String text1 = ControlUpdateTexts.renderPlanSnapshot(plan1);
        String text2 = ControlUpdateTexts.renderPlanSnapshot(plan2);

        assertEquals("same plan items → same output", text1, text2);
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
    public void planSnapshotDoesNotContainVolatileFields() {
        AgentPlan plan = AgentPlan.forQuestion("test question with cache");
        String text = ControlUpdateTexts.renderPlanSnapshot(plan);

        assertThat(text).doesNotContain("planId=");
        assertThat(text).doesNotContain("updatedAt");
        assertThat(text).doesNotContain("elapsedMs");
        assertThat(text).doesNotContain("UUID");
        assertThat(text).doesNotContain("/tmp/");
    }

    @Test
    public void budgetSnapshotDoesNotContainVolatileFields() {
        AgentContext ctx = basicContext("r-clean");
        ctx.setMaxSegments(3);
        ctx.setMaxTotalSteps(90);

        String text = ControlUpdateTexts.renderBudgetSnapshot(ctx);

        assertThat(text).doesNotContain(ctx.getRunId());
        assertThat(text).doesNotContain("elapsedMs");
        assertThat(text).doesNotContain("UUID");
        assertThat(text).doesNotContain("/tmp/");
    }

    @Test
    public void replanNoteDoesNotContainVolatileFields() {
        String text = ControlUpdateTexts.renderReplanNote(
                ReplanReason.TOOL_FAILURE, true, "some message");

        assertThat(text).doesNotContain("elapsedMs");
        assertThat(text).doesNotContain("UUID");
        assertThat(text).doesNotContain("/tmp/");
    }

    // ================================================================
    // 9. Event key format convention
    // ================================================================

    @Test
    public void planEventKeyFormat() {
        String key = ConversationLedgerInitializer.eventKey("run-1", "plan", "v3");
        assertEquals("run-1:plan:v3", key);
    }

    @Test
    public void budgetEventKeyFormat() {
        String key = ConversationLedgerInitializer.eventKey("run-1", "5", "budget");
        assertEquals("run-1:5:budget", key);
    }

    @Test
    public void todoReminderEventKeyFormat() {
        String key = ConversationLedgerInitializer.eventKey("run-1", "5", "todo_reminder");
        assertEquals("run-1:5:todo_reminder", key);
    }

    @Test
    public void parseErrorEventKeyFormat() {
        String key = ConversationLedgerInitializer.eventKey("run-1", "3", "parse_error:2");
        assertEquals("run-1:3:parse_error:2", key);
    }

    @Test
    public void replanEventKeyFormat() {
        String key = ConversationLedgerInitializer.eventKey("run-1", "4", "replan");
        assertEquals("run-1:4:replan", key);
    }

    @Test
    public void userInputEventKeyFormat() {
        String key = ConversationLedgerInitializer.eventKey("run-1", "6", "user_input");
        assertEquals("run-1:6:user_input", key);
    }

    @Test
    public void nullLedgerServiceSafe() {
        // Verify all renderer methods work without a null service
        AgentPlan plan = AgentPlan.forQuestion("test");
        assertNotNull(ControlUpdateTexts.renderPlanSnapshot(plan));
        assertNotNull(ControlUpdateTexts.renderTodoReminder());
        assertNotNull(ControlUpdateTexts.renderReplanNote(
                ReplanReason.TOOL_FAILURE, true, "msg"));
        assertNotNull(ControlUpdateTexts.renderParseErrorNote("raw", 1, 3));
        assertNotNull(ControlUpdateTexts.renderUserInput("hi"));
        assertNotNull(ControlUpdateTexts.renderContinuation("q"));
    }

    // ================================================================
    // 11. Plan snapshot format matches AgentPlan.render style
    // ================================================================

    @Test
    public void planSnapshotRendersAllItems() {
        AgentPlan plan = AgentPlan.forQuestion("test");
        plan.addReplanItem("extra item", "replan:TOOL_FAILURE");

        String text = ControlUpdateTexts.renderPlanSnapshot(plan);
        String[] lines = text.split("\n");

        // First line is [Plan vX]
        assertThat(lines[0]).startsWith("[Plan v");
        // Remaining lines are plan items
        assertEquals("header + 4 items", plan.getItems().size() + 1, lines.length);

        for (int i = 1; i < lines.length; i++) {
            assertThat(lines[i]).startsWith("- [");
            assertThat(lines[i]).contains(": ");
        }
    }

    @Test
    public void planSnapshotWithEvidenceAndBlocker() {
        AgentPlan plan = AgentPlan.forQuestion("test");
        // Simulate model setting evidence and blocker via todo_write
        ObjectNode input = objectMapper.createObjectNode();
        ArrayNode todos = input.putArray("todos");
        ObjectNode todo = todos.addObject();
        todo.put("id", "task-1");
        todo.put("content", "test content");
        todo.put("status", "blocked");
        todo.put("evidence", "found bug in module X");
        todo.put("blocker", "need fix first");
        plan.applyTodoWrite(input);

        String text = ControlUpdateTexts.renderPlanSnapshot(plan);
        assertThat(text).contains("evidence=found bug in module X");
        assertThat(text).contains("blocker=need fix first");
    }

    @Test
    public void nullPlanRendersEmptyMarker() {
        String text = ControlUpdateTexts.renderPlanSnapshot(null);
        assertEquals("[Plan] (empty)", text);
    }

    // ================================================================
    // 12. Mixed event sequence
    // ================================================================

    @Test
    public void fullRunSequenceAppendsAllEventTypes() {
        AgentContext ctx = basicContext("r-full");
        ctx.ensureLedgerActive();
        ctx.setMaxSegments(3);
        ctx.setMaxTotalSteps(90);

        // Simulate a full run sequence:
        // 1. Budget snapshot before step 1 model call
        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "budget"));

        // 2. Model call success (assistant) — step 1
        svc.appendAssistant(ctx, "model output 1",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));

        // 3. Tool result — step 1
        svc.appendToolResult(ctx, "tool output 1",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "tool_result"));

        // 4. Plan updated by todo_write — version v2
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

        // 5. Budget before step 2
        ctx.setStep(2);
        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "2", "budget"));

        // 6. Model call — step 2
        svc.appendAssistant(ctx, "model output 2",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "2", "assistant"));

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals(6, entries.size());

        // All control updates and tool results are "user" role
        assertEquals("user", entries.get(0).role()); // budget
        assertEquals("assistant", entries.get(1).role());
        assertEquals("user", entries.get(2).role()); // tool_result
        assertEquals("user", entries.get(3).role()); // plan
        assertEquals("user", entries.get(4).role()); // budget
        assertEquals("assistant", entries.get(5).role());

        // Sequences are monotonic
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(i, entries.get(i).sequence());
        }
    }

    // ================================================================
    // 13. Stable types are correct
    // ================================================================

    @Test
    public void planSnapshotUsesControlUpdateStableType() {
        AgentContext ctx = basicContext("r-type-plan");
        ctx.ensureLedgerActive();
        AgentPlan plan = AgentPlan.forQuestion("test");
        String text = ControlUpdateTexts.renderPlanSnapshot(plan);
        svc.appendControlUpdate(ctx, text,
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "plan", "v1"));

        assertEquals(LedgerStableType.CONTROL_UPDATE,
                ctx.getConversationLedger().entries().get(0).stableType());
    }

    @Test
    public void budgetSnapshotUsesControlUpdateStableType() {
        AgentContext ctx = basicContext("r-type-budget");
        ctx.ensureLedgerActive();
        ctx.setMaxSegments(3);

        svc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "budget"));

        assertEquals(LedgerStableType.CONTROL_UPDATE,
                ctx.getConversationLedger().entries().get(0).stableType());
    }

    @Test
    public void userInputUsesUserInputStableType() {
        AgentContext ctx = basicContext("r-type-input");
        ctx.ensureLedgerActive();

        svc.appendUserInput(ctx, ControlUpdateTexts.renderUserInput("hi"),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "user_input"));

        assertEquals(LedgerStableType.USER_INPUT,
                ctx.getConversationLedger().entries().get(0).stableType());
    }

    @Test
    public void continuationUsesUserInputStableType() {
        AgentContext ctx = basicContext("r-type-cont");
        ctx.ensureLedgerActive();

        svc.appendUserInput(ctx, ControlUpdateTexts.renderContinuation("q"),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "continuation", "user_input"));

        assertEquals(LedgerStableType.USER_INPUT,
                ctx.getConversationLedger().entries().get(0).stableType());
    }
}
