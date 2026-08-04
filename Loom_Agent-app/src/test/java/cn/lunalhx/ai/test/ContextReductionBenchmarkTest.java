package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistory;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.agent.service.context.ContextBuildResult;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * Fixed 12-group pressure matrix (3 history × 2 memory × 2 request), each run
 * with reduction on and off. Writes results to
 * {@code target/context-reduction-benchmark.json} (not version-controlled).
 */
public class ContextReductionBenchmarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentContext build(int historyGroups, int memoryGroups, int requestChars) {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        AgentContext ctx = new AgentContext();
        ctx.setQuestion("x".repeat(requestChars));
        ctx.ensureLedgerActive();
        ctx.setStablePrefix(new StablePrefix("p".repeat(400), "fp", null, null, null, null));
        ctx.setLedgerReady(true);

        ConversationHistory h = ctx.getConversationHistory();
        int historyEntries = historyGroups * 20;
        for (int i = 0; i < historyEntries; i++) {
            if (i % 2 == 0) {
                h.appendWithEventKey("assistant",
                        "a".repeat(1500), ConversationEntryType.ASSISTANT_ACTION,
                        "run:" + i + ":assistant", "read_file", null, null, null);
            } else {
                h.appendWithEventKey("user",
                        "<untrusted_tool_output>\n" + "f".repeat(1500) + "\n</untrusted_tool_output>",
                        ConversationEntryType.TOOL_RESULT, "run:" + i + ":tool_result",
                        "read_file", null, null, null);
            }
        }
        h.appendWithEventKey("user", "current".repeat(requestChars), ConversationEntryType.USER_TASK, "run:init:user_task");

        WorkingContextMemory wm = ctx.workingMemoryOrCreate();
        for (int i = 0; i < memoryGroups; i++) {
            wm.putFileSummary(new WorkingContextMemory.FileSummary("f" + i + ".java",
                    "s".repeat(100), Instant.now(), "sha" + i));
            wm.addNote("note" + i + ": " + "n".repeat(80));
        }
        return ctx;
    }

    @Test
    public void runPressureMatrix() throws Exception {
        List<Integer> historyGroups = List.of(1, 5, 15);
        List<Integer> memoryGroups = List.of(0, 8);
        List<Integer> requestChars = List.of(50, 3000);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int h : historyGroups) {
            for (int m : memoryGroups) {
                for (int r : requestChars) {
                    rows.add(measure(h, m, r, true));
                    rows.add(measure(h, m, r, false));
                }
            }
        }
        // 3 history × 2 memory × 2 request = 12 groups, each measured on/off → 24 rows.
        assertTrue("expected 24 rows, got " + rows.size(), rows.size() == 24);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        // Assertions: current request preserved everywhere, reduction never longer
        // than non-reduction, and long contexts shrink by at least 10%.
        for (Map<String, Object> row : rows) {
            assertTrue((Boolean) row.get("currentRequestPreserved"));
        }
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("reductionEnabled"))) {
                int raw = (Integer) row.get("rawChars");
                int rendered = (Integer) row.get("renderedChars");
                assertTrue("reduction must not exceed raw for " + row.get("name"),
                        rendered <= raw);
                // The 10% shrinkage assertion applies to large history contexts
                // (h15 = 300 entries), where genuine compression occurs.
                if (raw > 20000) {
                    double ratio = 1.0 - (double) rendered / raw;
                    assertTrue("long context " + row.get("name") + " must shrink >=10% (got "
                            + (int) (ratio * 100) + "%)", ratio >= 0.10);
                }
            }
        }

        Path output = Path.of("target", "context-reduction-benchmark.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }

    private Map<String, Object> measure(int h, int m, int r, boolean reduction) {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.getContext().setContextReductionEnabled(reduction);
        ContextManager mgr = new ContextManager(props);
        AgentContext ctx = build(h, m, r);
        ContextBuildResult result = mgr.build(ctx);

        // Raw baseline: the true uncompressed send size, i.e. full raw history
        // entry contents plus prefix, memory, and current request.
        int raw = ctx.getStablePrefix().frozenContent().length()
                + ctx.getConversationHistory().entries().stream()
                        .mapToInt(e -> e.content().length()).sum()
                + ctx.getQuestion().length();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "h" + h + "-m" + m + "-r" + r + "-" + (reduction ? "on" : "off"));
        row.put("reductionEnabled", reduction);
        row.put("rawChars", raw);
        row.put("renderedChars", result.budgetText().length());
        row.put("reductions", result.metadata().reductions());
        row.put("sectionRenderedChars", result.metadata().sectionRenderedChars());
        row.put("currentRequestPreserved", result.metadata().currentRequestPreserved());
        return row;
    }
}
