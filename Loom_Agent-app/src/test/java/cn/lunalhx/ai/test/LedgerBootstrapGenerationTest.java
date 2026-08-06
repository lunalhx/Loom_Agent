package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerBootstrapService;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Prefix/generation 状态机：真实不兼容变更切换 generation 并保留冻结基线；
 * 动态工作区信息与工具写入不触发切换。
 */
public class LedgerBootstrapGenerationTest {

    private final ConversationHistoryAppendService appendService = new ConversationHistoryAppendService();

    private AgentContext activeContext() {
        AgentContext ctx = new AgentContext();
        ctx.setRunId("gen-test");
        ctx.setQuestion("q");
        ctx.ensureLedgerActive();
        ctx.setGeneration(0);
        return ctx;
    }

    private StablePrefix prefix(String fingerprint, String ws, String tools, String runtime) {
        return new StablePrefix("content-" + fingerprint, fingerprint, ws, tools, runtime, 1L);
    }

    private LedgerBootstrapService service() {
        return new LedgerBootstrapService(appendService, new ConversationHistoryInitializer());
    }

    @Test
    public void incompatiblePrefixSwitchBumpsGenerationWithFrozenBaseline() {
        AgentContext ctx = activeContext();
        LedgerBootstrapService service = service();
        StablePrefix oldPrefix = prefix("fp-old", "ws-1", "tools-1", "rt-1");

        // First bootstrap = new conversation (generation 0).
        service.bootstrap(ctx, oldPrefix);
        assertEquals(0, ctx.getGeneration());
        int sizeAfterInit = ctx.getConversationHistory().size();

        // Tool schema changed → incompatible prefix → generation 1 + system note baseline.
        StablePrefix newPrefix = prefix("fp-new", "ws-1", "tools-2", "rt-1");
        service.bootstrap(ctx, newPrefix);

        assertEquals(1, ctx.getGeneration());
        assertEquals("fp-new", ctx.getStablePrefix().fingerprint());
        List<cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry> entries =
                ctx.getConversationHistory().entries();
        assertEquals(sizeAfterInit + 1, entries.size());
        cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry note =
                entries.get(entries.size() - 1);
        assertEquals(ConversationEntryType.SYSTEM_NOTE, note.stableType());
        assertTrue(note.content().contains("Generation 1")
                || note.content().contains("generation 1"));
        // Old messages remain as-is (frozen baseline).
        assertEquals(sizeAfterInit, entries.size() - 1);
    }

    @Test
    public void compatiblePrefixReuseDoesNotBumpGeneration() {
        AgentContext ctx = activeContext();
        LedgerBootstrapService service = service();
        service.bootstrap(ctx, prefix("fp-1", "ws-1", "tools-1", "rt-1"));
        assertEquals(0, ctx.getGeneration());
        int size = ctx.getConversationHistory().size();

        // Same workspace identity + tools, only dynamic git status changed →
        // fingerprint text differs but signatures match (identity-based matches).
        StablePrefix sameIdentity = new StablePrefix("content-diff", "fp-2", "ws-1", "tools-1", "rt-1", 2L);
        // matches() compares signatures, not fingerprint → reuse.
        assertTrue(ctx.getStablePrefix().matches(sameIdentity));

        service.bootstrap(ctx, sameIdentity);
        assertEquals(0, ctx.getGeneration());
        assertEquals("fp-1", ctx.getStablePrefix().fingerprint());
        assertEquals(size, ctx.getConversationHistory().size());
    }

    @Test
    public void toolWriteAfterBootstrapKeepsGeneration() {
        AgentContext ctx = activeContext();
        LedgerBootstrapService service = service();
        service.bootstrap(ctx, prefix("fp-1", "ws-1", "tools-1", "rt-1"));
        int gen = ctx.getGeneration();

        // Simulate a tool execution round: ledger grows, prefix signatures unchanged.
        appendService.appendToolResult(ctx, "file written",
                ConversationHistoryInitializer.eventKey("gen-test", "1", "tool_result"));

        service.bootstrap(ctx, prefix("fp-1", "ws-1", "tools-1", "rt-1"));
        assertEquals(gen, ctx.getGeneration());
    }
}
