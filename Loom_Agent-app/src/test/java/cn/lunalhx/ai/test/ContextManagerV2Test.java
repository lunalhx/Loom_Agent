package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.agent.service.context.ContextBuildResult;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Ported loom-code context cases plus edge cases for the five-section fixed
 * render (prefix → memory → relevant_memory → history → current_request).
 */
public class ContextManagerV2Test {

    private ContextManager manager() {
        return new ContextManager(new AgentRuntimeProperties());
    }

    private AgentContext context() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("cmv2");
        ctx.setQuestion("原始问题");
        ctx.ensureLedgerActive();
        ctx.setStablePrefix(new StablePrefix("prefix-content", "fp", null, null, null, null));
        ctx.setLedgerReady(true);
        return ctx;
    }

    private ConversationHistory history(AgentContext ctx) {
        return ctx.getConversationHistory();
    }

    // ---- section order: exactly one system prefix + four user messages ----

    @Test
    public void fiveSectionsInFixedOrder() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "k:init:task");
        history(ctx).appendWithEventKey("assistant", "a", ConversationEntryType.ASSISTANT_ACTION, "k:1:assistant");

        ContextBuildResult result = manager().build(ctx);
        assertEquals("prefix-content", result.systemPrefix());
        List<ChatMessage> messages = result.messages();
        assertEquals(5, messages.size());
        // Workspace snapshot, Memory, Relevant memory, Transcript, Current user request
        assertTrue(messages.get(0).getContent().startsWith("Workspace snapshot:"));
        assertTrue(messages.get(1).getContent().startsWith("Memory:"));
        assertTrue(messages.get(2).getContent().startsWith("Relevant memory:"));
        assertTrue(messages.get(3).getContent().startsWith("Transcript:"));
        assertTrue(messages.get(4).getContent().startsWith("Current user request:"));
        assertEquals(List.of("prefix", "workspace", "memory", "relevant_memory", "history", "current_request"),
                result.metadata().sectionOrder());
    }

    // ---- current request appears exactly once, last ----

    @Test
    public void currentRequestNotDuplicatedInHistory() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "当前请求正文", ConversationEntryType.USER_TASK, "k:init:task");
        history(ctx).appendWithEventKey("assistant", "a", ConversationEntryType.ASSISTANT_ACTION, "k:1:assistant");

        ContextBuildResult result = manager().build(ctx);
        String text = result.budgetText();
        long occurrences = countOccurrences(text, "当前请求正文");
        assertEquals(1, occurrences);
    }

    // ---- current request preserved even when huge ----

    @Test
    public void hugeCurrentRequestNeverTrimmed() {
        AgentContext ctx = context();
        String huge = "很长的请求".repeat(5000);
        history(ctx).appendWithEventKey("user", huge, ConversationEntryType.USER_TASK, "k:init:task");
        history(ctx).appendWithEventKey("assistant", "a".repeat(3000), ConversationEntryType.ASSISTANT_ACTION, "k:1:assistant");

        ContextBuildResult result = manager().build(ctx);
        assertTrue(result.metadata().currentRequestPreserved());
        ChatMessage last = result.messages().get(result.messages().size() - 1);
        assertTrue(last.getContent().endsWith(huge));
    }

    // ---- top-3 relevant notes, fair budget ----

    @Test
    public void topThreeRelevantNotesSelected() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "读取并总结 A.java 和 B.java", ConversationEntryType.USER_TASK, "k:init:task");
        WorkingContextMemory wm = ctx.workingMemoryOrCreate();
        wm.addNote("A.java 包含 main 方法", List.of("A.java", "read_file"), "A.java", "process");
        wm.addNote("B.java 包含测试", List.of("B.java", "read_file"), "B.java", "process");
        wm.addNote("C.java 无关内容", List.of("C.java"), "C.java", "process");
        wm.addNote("D.java 无关内容", List.of("D.java"), "D.java", "process");

        ContextBuildResult result = manager().build(ctx);
        String relevant = result.messages().get(2).getContent();
        assertTrue(relevant.contains("A.java"));
        assertTrue(relevant.contains("B.java"));
        // Only 3 selected at most.
        assertEquals(3, result.metadata().relevantMemorySelected());
    }

    // ---- old read_file folded + fresh file summary reuse ----

    @Test
    public void oldDuplicateReadFoldedWithFreshSummary() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("cmv2");
        java.nio.file.Path file = tempDir.resolve("A.java");
        java.nio.file.Files.writeString(file, "class A {}");
        String sha = org.apache.commons.codec.digest.DigestUtils.sha256Hex(
                java.nio.file.Files.readAllBytes(file));
        AgentContext ctx = context();
        WorkingContextMemory wm = ctx.workingMemoryOrCreate();
        wm.putFileSummary(new WorkingContextMemory.FileSummary(file.toString(), "fresh-summary", Instant.now(), sha));
        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "run:init:user_task");
        // 20 older read_file pairs of A.java
        for (int i = 0; i < 20; i++) {
            history(ctx).appendWithEventKey("assistant", "a", ConversationEntryType.ASSISTANT_ACTION,
                    "run:" + i + ":assistant", "read_file", "{\"path\":\"" + file + "\"}", null, null, null);
            history(ctx).appendWithEventKey("user", "<untrusted_tool_output>\nclass A {}\n</untrusted_tool_output>",
                    ConversationEntryType.TOOL_RESULT, "run:" + i + ":tool_result", "read_file",
                    "{\"path\":\"" + file + "\"}", null, null, null);
        }

        ContextBuildResult result = manager().build(ctx);
        assertTrue(result.metadata().historyDeduped() >= 1);
        assertTrue(result.metadata().summaryReuseCount() >= 1);
    }

    // ---- old shell compressed to command -> first 3 lines ----

    @Test
    public void oldShellCompressedToCommandAndFirstLines() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "run:init:user_task");
        String output = "line1\nline2\nline3\nline4\nline5";
        for (int i = 0; i < 20; i++) {
            history(ctx).appendWithEventKey("assistant", "a", ConversationEntryType.ASSISTANT_ACTION,
                    "run:" + i + ":assistant", "run_shell", "{\"command\":\"echo hi\"}", null, null, null);
            history(ctx).appendWithEventKey("user", "<untrusted_tool_output>\n" + output + "\n</untrusted_tool_output>",
                    ConversationEntryType.TOOL_RESULT, "run:" + i + ":tool_result", "run_shell",
                    "{\"command\":\"echo hi\"}", null, null, null);
        }

        ContextBuildResult result = manager().build(ctx);
        String text = result.budgetText();
        assertTrue(text.contains("command ->"));
    }

    // ---- history immutable across builds ----

    @Test
    public void historyNeverMutatedByBuild() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "run:init:user_task");
        List<ConversationEntryType> beforeTypes = history(ctx).entries().stream()
                .map(e -> e.stableType()).toList();
        int beforeSeq = (int) history(ctx).nextSequence();
        manager().build(ctx);
        manager().buildFloorPressed(ctx);
        assertEquals(beforeTypes, history(ctx).entries().stream().map(e -> e.stableType()).toList());
        assertEquals(beforeSeq, history(ctx).nextSequence());
    }

    // ---- reduction disabled: no budget trimming ----

    @Test
    public void reductionDisabledSendsUncutView() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.getContext().setContextReductionEnabled(false);
        ContextManager raw = new ContextManager(props);
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "run:init:user_task");
        history(ctx).appendWithEventKey("assistant", "a".repeat(5000), ConversationEntryType.ASSISTANT_ACTION, "run:1:assistant");

        ContextBuildResult result = raw.build(ctx);
        assertFalse(result.metadata().reductionEnabled());
        // The long assistant content is preserved in the transcript.
        assertTrue(result.budgetText().contains("a".repeat(5000)));
    }

    // ---- floor mode preserves current request ----

    @Test
    public void floorModePreservesCurrentRequest() {
        AgentContext ctx = context();
        String huge = "请求".repeat(1000);
        history(ctx).appendWithEventKey("user", huge, ConversationEntryType.USER_TASK, "run:init:user_task");
        history(ctx).appendWithEventKey("assistant", "a".repeat(3000), ConversationEntryType.ASSISTANT_ACTION, "run:1:assistant");

        ContextBuildResult result = manager().buildFloorPressed(ctx);
        assertTrue(result.metadata().currentRequestPreserved());
        assertTrue(result.metadata().reductionLog().contains("floor_pressure"));
        ChatMessage last = result.messages().get(result.messages().size() - 1);
        assertTrue(last.getContent().endsWith(huge));
    }

    // ---- emoji / surrogate pairs are not split ----

    @Test
    public void emojiNotSplitByBudget() {
        AgentContext ctx = context();
        history(ctx).appendWithEventKey("user", "任务", ConversationEntryType.USER_TASK, "run:init:user_task");
        String emoji = "😀".repeat(600);
        history(ctx).appendWithEventKey("assistant", emoji, ConversationEntryType.ASSISTANT_ACTION,
                "run:1:assistant", null, null, null, null);

        ContextBuildResult result = manager().build(ctx);
        for (ChatMessage m : result.messages()) {
            String content = m.getContent();
            // count unpaired high surrogates = split emoji
            int unpaired = countUnpairedSurrogates(content);
            assertEquals(0, unpaired);
        }
    }

    // ---- all sections at floor still over budget: current request intact ----

    @Test
    public void allSectionsAtFloorStillOverBudget() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.getContext().setTotalBudgetChars(1000);
        props.getContext().setPrefixBudgetChars(50);
        props.getContext().setPrefixFloorChars(10);
        props.getContext().setMemoryBudgetChars(50);
        props.getContext().setMemoryFloorChars(10);
        props.getContext().setRelevantMemoryBudgetChars(50);
        props.getContext().setRelevantMemoryFloorChars(10);
        props.getContext().setHistoryBudgetChars(50);
        props.getContext().setHistoryFloorChars(10);
        ContextManager mgr = new ContextManager(props);

        AgentContext ctx = new AgentContext();
        ctx.setRunId("edge");
        ctx.ensureLedgerActive();
        ctx.setStablePrefix(new StablePrefix("p".repeat(200), "fp", null, null, null, null));
        ctx.setLedgerReady(true);
        history(ctx).appendWithEventKey("user", "任务".repeat(600), ConversationEntryType.USER_TASK, "run:init:user_task");
        history(ctx).appendWithEventKey("assistant", "a".repeat(2000), ConversationEntryType.ASSISTANT_ACTION, "run:1:assistant");

        ContextBuildResult result = mgr.build(ctx);
        assertTrue(result.metadata().overBudget());
        assertTrue(result.metadata().currentRequestPreserved());
        ChatMessage last = result.messages().get(result.messages().size() - 1);
        assertTrue(last.getContent().endsWith("任务".repeat(600)));
    }

    // ---- prefix signatures: tool change invalidates, git-status churn does not ----

    @Test
    public void prefixReusedWhenWorkspaceFingerprintUnchanged() {
        StablePrefixBuilder builder = new StablePrefixBuilder();
        List<ToolSpec> specs = List.of(ToolSpec.builder().name("read_file")
                .description("Read").inputSchema("{}").build());
        StablePrefix a = builder.build(false, true, null, specs,
                "Workspace:\n- status: M A.java", "ws-fp",
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        StablePrefix b = builder.build(false, true, null, specs,
                "Workspace:\n- status: M B.java", "ws-fp",
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        assertTrue(a.matches(b));
    }

    @Test
    public void prefixInvalidatedWhenToolChanges() {
        StablePrefixBuilder builder = new StablePrefixBuilder();
        List<ToolSpec> specA = List.of(ToolSpec.builder().name("read_file")
                .description("Read").inputSchema("{}").build());
        List<ToolSpec> specB = List.of(ToolSpec.builder().name("write_file")
                .description("Write").inputSchema("{}").build());
        StablePrefix a = builder.build(false, true, null, specA, "", "ws-fp",
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        StablePrefix b = builder.build(false, true, null, specB, "", "ws-fp",
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        assertFalse(a.matches(b));
        assertNotEquals(a.toolSignature(), b.toolSignature());
    }

    @Test
    public void prefixInvalidatedWhenRuntimeChanges() {
        StablePrefixBuilder builder = new StablePrefixBuilder();
        List<ToolSpec> specs = List.of(ToolSpec.builder().name("read_file")
                .description("Read").inputSchema("{}").build());
        StablePrefix main = builder.build(false, true, null, specs, "", "ws-fp",
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        StablePrefix delegate = builder.build(true, false, null, specs, "", "ws-fp",
                cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode.BUILD, null);
        assertFalse(main.matches(delegate));
        assertNotEquals(main.runtimeSignature(), delegate.runtimeSignature());
    }

    // ---- legacy two-field prefix detection ----

    @Test
    public void legacyTwoFieldPrefixDetected() {
        StablePrefix legacy = new StablePrefix("content", "fp", null, null, null, null);
        assertTrue(legacy.isLegacyTwoField());
        StablePrefix full = new StablePrefix("content", "fp", "ws", "tools", "runtime", null);
        assertFalse(full.isLegacyTwoField());
    }

    private static int countUnpairedSurrogates(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
