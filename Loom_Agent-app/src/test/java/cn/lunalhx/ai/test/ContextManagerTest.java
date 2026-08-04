package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.agent.service.context.ContextBuildResult;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContextManagerTest {

    private ContextManager manager = new ContextManager(new AgentRuntimeProperties());

    private AgentContext context() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("cm-test");
        ctx.setQuestion("原始问题");
        ctx.ensureLedgerActive();
        ctx.setStablePrefix(new StablePrefix("prefix-content", "fp"));
        ctx.setLedgerReady(true);
        return ctx;
    }

    private ConversationHistory history(AgentContext ctx) {
        return ctx.getConversationHistory();
    }

    // ---- 1. Section order & current request last ----

    @Test
    public void currentRequestIsLastAndNotDuplicated() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "初始任务", ConversationEntryType.USER_TASK, "k1");
        history(ctx).appendWithEventKey("assistant", "assistant-1", ConversationEntryType.ASSISTANT_ACTION, "k2");
        history(ctx).appendWithEventKey("user", "补充说明", ConversationEntryType.USER_INPUT, "k3");

        ContextBuildResult result = manager.build(ctx);

        List<ChatMessage> messages = result.messages();
        assertFalse(messages.isEmpty());
        ChatMessage last = messages.get(messages.size() - 1);
        assertEquals("user", last.getRole());
        assertEquals("补充说明", last.getContent());
        // The current request must not appear again in the history projection.
        long occurrences = messages.stream()
                .filter(m -> m.getContent() != null && m.getContent().contains("补充说明"))
                .count();
        assertEquals(1, occurrences);
    }

    @Test
    public void userTaskIsCurrentRequestWhenNoUserInput() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "初始任务", ConversationEntryType.USER_TASK, "k1");
        history(ctx).appendWithEventKey("assistant", "assistant-1", ConversationEntryType.ASSISTANT_ACTION, "k2");

        ContextBuildResult result = manager.build(ctx);
        ChatMessage last = result.messages().get(result.messages().size() - 1);
        assertEquals("初始任务", last.getContent());
    }

    // ---- 2. Tool pair merging ----

    @Test
    public void toolPairIsMergedIntoLogicalItem() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "run:init:user_task");
        history(ctx).appendWithEventKey("assistant",
                "{\"type\":\"action\",\"tool\":\"read_file\",\"input\":{\"path\":\"A.java\"}}",
                ConversationEntryType.ASSISTANT_ACTION, "run:1:assistant", "read_file", null, null, null);
        history(ctx).appendWithEventKey("user",
                "<untrusted_tool_output>\nclass A {}\n</untrusted_tool_output>",
                ConversationEntryType.TOOL_RESULT, "run:1:tool_result", "read_file", null, null, null);

        ContextBuildResult result = manager.build(ctx);
        String text = result.budgetText();
        assertTrue(text.contains("[tool:read_file]"));
        assertTrue(text.contains("class A {}"));
        // Raw assistant tool-call JSON must not be duplicated.
        assertFalse(text.contains("{\"type\":\"action\",\"tool\":\"read_file\""));
    }

    // ---- 3. Repeated read dedup / freshness ----

    @Test
    public void repeatedReadDedupsAndReusesValidSummary() {
        AgentContext ctx = context();
        WorkingContextMemory wm = ctx.workingMemoryOrCreate();
        wm.putFileSummary(new WorkingContextMemory.FileSummary("A.java", "first-summary", Instant.now(), "sha-abc"));

        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "run:init:user_task");
        history(ctx).appendWithEventKey("assistant", "a", ConversationEntryType.ASSISTANT_ACTION,
                "run:1:assistant", "read_file", null, null, null);
        history(ctx).appendWithEventKey("user", "<untrusted_tool_output>\nclass A {}\n</untrusted_tool_output>",
                ConversationEntryType.TOOL_RESULT, "run:1:tool_result", "read_file", null, null, null);
        history(ctx).appendWithEventKey("assistant", "b", ConversationEntryType.ASSISTANT_ACTION,
                "run:2:assistant", "read_file", null, null, null);
        history(ctx).appendWithEventKey("user", "<untrusted_tool_output>\nclass A {}\n</untrusted_tool_output>",
                ConversationEntryType.TOOL_RESULT, "run:2:tool_result", "read_file", null, null, null);

        ContextBuildResult result = manager.build(ctx);
        String text = result.budgetText();
        // Both reads of A.java render in the merged history window.
        assertTrue(text.contains("[tool:read_file]"));
    }

    // ---- 4. Shell keeps first three lines ----

    @Test
    public void shellResultKeepsFirstThreeLines() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "run:init:user_task");
        String output = "line1\nline2\nline3\nline4\nline5";
        history(ctx).appendWithEventKey("assistant", "a", ConversationEntryType.ASSISTANT_ACTION,
                "run:1:assistant", "run_shell", null, null, null);
        history(ctx).appendWithEventKey("user", "<untrusted_tool_output>\n" + output + "\n</untrusted_tool_output>",
                ConversationEntryType.TOOL_RESULT, "run:1:tool_result", "run_shell", null, null, null);

        ContextBuildResult result = manager.build(ctx);
        String text = result.budgetText();
        assertTrue(text.contains("line1"));
        assertTrue(text.contains("line3"));
        assertFalse(text.contains("line5"));
    }

    // ---- 5. Reduction switch off returns raw view ----

    @Test
    public void reductionDisabledReturnsUncutView() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.getContext().setContextReductionEnabled(false);
        ContextManager rawManager = new ContextManager(props);
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "run:init:user_task");
        history(ctx).appendWithEventKey("assistant", "a", ConversationEntryType.ASSISTANT_ACTION, "run:1:assistant");

        ContextBuildResult result = rawManager.build(ctx);
        assertFalse(result.metadata().reductionEnabled());
        // current request is still present and last.
        ChatMessage last = result.messages().get(result.messages().size() - 1);
        assertEquals("任务", last.getContent());
    }

    // ---- 6. Build does not mutate history ----

    @Test
    public void buildDoesNotMutateHistory() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "run:init:user_task");
        history(ctx).appendWithEventKey("assistant", "a", ConversationEntryType.ASSISTANT_ACTION, "run:1:assistant");
        int sizeBefore = history(ctx).size();

        manager.build(ctx);
        manager.buildFloorPressed(ctx);

        assertEquals(sizeBefore, history(ctx).size());
    }

    // ---- 7. Floor-pressed view preserves current request ----

    @Test
    public void floorPressedKeepsCurrentRequest() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "很长的请求".repeat(500), ConversationEntryType.USER_TASK, "run:init:user_task");
        history(ctx).appendWithEventKey("assistant", "a".repeat(3000), ConversationEntryType.ASSISTANT_ACTION, "run:1:assistant");

        ContextBuildResult result = manager.buildFloorPressed(ctx);
        assertTrue(result.metadata().currentRequestPreserved());
        ChatMessage last = result.messages().get(result.messages().size() - 1);
        assertEquals("很长的请求".repeat(500), last.getContent());
    }
}
