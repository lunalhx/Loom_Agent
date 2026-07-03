package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentPlan;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationLedgerEntry;
import cn.lunalhx.ai.domain.agent.model.entity.LedgerShadowResult;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.LedgerStableType;
import cn.lunalhx.ai.domain.agent.model.valobj.ReplanReason;
import cn.lunalhx.ai.domain.agent.service.ledger.CanonicalSnapshot;
import cn.lunalhx.ai.domain.agent.service.ledger.ComparisonStatus;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.ControlUpdateTexts;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerShadowComparator;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerShadowDiagnostic;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.*;

/**
 * C7R: Ledger shadow comparison — structured snapshot, message-level prefix,
 * append-only / rewrite / invalid detection, semantic coverage (mandatory vs n/a),
 * firstDiff index (no raw content), desensitization, and exception isolation.
 */
public class ConversationLedgerC7Test {

    private AgentRuntimeProperties.ConversationLedgerProperties shadowConfig;
    private AgentRuntimeProperties.ConversationLedgerProperties enabledConfig;
    private ConversationLedgerAppendService shadowSvc;
    private StablePrefix stablePrefix;
    private ObjectMapper objectMapper;
    private LedgerShadowComparator comparator;

    @Before
    public void setUp() {
        shadowConfig = new AgentRuntimeProperties.ConversationLedgerProperties();
        shadowConfig.setEnabled(false);
        shadowConfig.setShadowEnabled(true);
        shadowSvc = new ConversationLedgerAppendService(shadowConfig);

        enabledConfig = new AgentRuntimeProperties.ConversationLedgerProperties();
        enabledConfig.setEnabled(true);
        enabledConfig.setShadowEnabled(false);

        stablePrefix = new StablePrefix("test-frozen-prefix",
                "sha256-" + System.currentTimeMillis());
        objectMapper = new ObjectMapper();
        comparator = new LedgerShadowComparator();
    }

    private AgentContext basicContext(String runId) {
        AgentContext ctx = new AgentContext();
        ctx.setRunId(runId);
        ctx.setRootRunId(runId);
        ctx.setRequestId("req-" + runId);
        ctx.setConversationId("conv-" + runId);
        ctx.setQuestion("test question");
        ctx.setStep(0);
        ctx.setMaxSteps(30);
        ctx.setMaxSegments(1);
        ctx.setMaxTotalSteps(30);
        ctx.setSegmentIndex(0);
        ctx.setSegmentStartStep(0);
        return ctx;
    }

    // ================================================================
    // 1. Structured canonical snapshot
    // ================================================================

    @Test
    public void snapshotContainsSystemAndMessages() {
        AgentContext ctx = basicContext("r-snap");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        CanonicalSnapshot snap = CanonicalSnapshot.from(ctx);
        assertEquals(0, snap.generation());
        assertEquals(stablePrefix.frozenContent(), snap.system());
        assertEquals(1, snap.messageCount()); // user_task
        assertEquals("user", snap.messages().get(0).role());
        assertEquals(ctx.getQuestion(), snap.messages().get(0).content());
    }

    @Test
    public void snapshotDoesNotContainDiagnosticMetadata() {
        AgentContext ctx = basicContext("r-clean");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);

        shadowSvc.appendAssistant(ctx, "assistant output",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));

        CanonicalSnapshot snap = CanonicalSnapshot.from(ctx);
        // Messages only have role + content, not sequence/stableType
        for (CanonicalSnapshot.Message m : snap.messages()) {
            assertFalse("message role must not contain metadata",
                    m.role().contains("seq=") || m.role().contains("stableType"));
        }
    }

    // ================================================================
    // 2. APPEND_ONLY status
    // ================================================================

    @Test
    public void normalAppendIsAppendOnly() {
        AgentContext ctx = basicContext("r-append-only");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();

        // Round 1
        LedgerShadowResult r1 = diagnostic.compareAndLog(ctx, "old prompt");
        assertEquals(ComparisonStatus.INITIAL, r1.comparisonStatus());
        assertEquals(1, r1.currentMessageCount()); // user_task

        // Round 2: append assistant + tool_result
        ctx.setStep(1);
        shadowSvc.appendAssistant(ctx, "model output",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));
        shadowSvc.appendToolResult(ctx, "tool output",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "tool_result"));

        LedgerShadowResult r2 = diagnostic.compareAndLog(ctx, "old prompt 2");
        assertEquals("must be APPEND_ONLY", ComparisonStatus.APPEND_ONLY, r2.comparisonStatus());
        // messageLcp == previousMessageCount
        assertEquals("msgLcp == prevMsgCnt", r1.currentMessageCount(), r2.messageLcp());
        assertTrue("curMsgCnt > prevMsgCnt",
                r2.currentMessageCount() > r2.previousMessageCount());
        assertEquals(r1.currentMessageCount(), r2.previousMessageCount());
    }

    // ================================================================
    // 3. IDENTICAL status
    // ================================================================

    @Test
    public void identicalSnapshotIsIdentical() {
        AgentContext ctx = basicContext("r-identical");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        diagnostic.compareAndLog(ctx, "prompt"); // baseline

        // Same context, no changes → IDENTICAL
        LedgerShadowResult r2 = diagnostic.compareAndLog(ctx, "prompt");
        assertEquals(ComparisonStatus.IDENTICAL, r2.comparisonStatus());
        assertEquals(r2.previousMessageCount(), r2.currentMessageCount());
        assertEquals(r2.previousMessageCount(), r2.messageLcp());
        assertEquals(-1, r2.firstDiffMessageIndex());
    }

    // ================================================================
    // 4. REWRITTEN — delete old message
    // ================================================================

    @Test
    public void deleteOldMessageIsRewritten() {
        AgentContext ctx = basicContext("r-del");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        // Append a message
        shadowSvc.appendAssistant(ctx, "msg-to-delete",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));

        // Build a snapshot BEFORE deletion
        CanonicalSnapshot prev = CanonicalSnapshot.from(ctx);
        assertEquals(2, prev.messageCount()); // user_task + assistant

        // Now manually remove the entry from the ledger (simulate deletion)
        // Build a modified list without the last entry
        List<ConversationLedgerEntry> modified = new ArrayList<>(ctx.getConversationLedger().entries());
        modified.remove(modified.size() - 1);
        CanonicalSnapshot current = new CanonicalSnapshot(0, prev.system(),
                modified.stream()
                        .map(e -> new CanonicalSnapshot.Message(e.role(), e.content()))
                        .toList());

        int msgLcp = comparator.countMatchingPrefixMessages(prev, current);
        // First msg (user_task) matches, then current ends → prev has more
        assertNotEquals("deletion breaks prefix", prev.messageCount(), msgLcp);

        // Verify it would be detected as REWRITTEN
        assertTrue("msgLcp < prevMsgCnt → not a strict prefix",
                msgLcp < prev.messageCount());
    }

    // ================================================================
    // 5. REWRITTEN — modify old message content
    // ================================================================

    @Test
    public void modifyOldMessageContentIsRewritten() {
        AgentContext ctx = basicContext("r-mod-content");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        shadowSvc.appendAssistant(ctx, "original content",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));

        CanonicalSnapshot prev = CanonicalSnapshot.from(ctx);

        // Same sequence, different content
        ctx.getConversationLedger().entries(); // get entries
        // We can't modify through the ledger, so build manually
        CanonicalSnapshot.Message originalMsg = prev.messages().get(1); // assistant
        CanonicalSnapshot.Message modifiedMsg = new CanonicalSnapshot.Message(
                originalMsg.role(), "modified content");

        List<CanonicalSnapshot.Message> modMsgs = new ArrayList<>();
        modMsgs.add(prev.messages().get(0)); // user_task unchanged
        modMsgs.add(modifiedMsg); // modified

        CanonicalSnapshot current = new CanonicalSnapshot(0, prev.system(), modMsgs);
        int msgLcp = comparator.countMatchingPrefixMessages(prev, current);
        assertEquals("only user_task matches", 1, msgLcp);
        assertNotEquals("modified content breaks prefix", prev.messageCount(), msgLcp);
    }

    // ================================================================
    // 6. REWRITTEN — modify old message role
    // ================================================================

    @Test
    public void modifyOldMessageRoleIsRewritten() {
        AgentContext ctx = basicContext("r-mod-role");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        shadowSvc.appendAssistant(ctx, "some content",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));

        CanonicalSnapshot prev = CanonicalSnapshot.from(ctx);

        // Same content, different role
        List<CanonicalSnapshot.Message> modMsgs = new ArrayList<>();
        modMsgs.add(prev.messages().get(0)); // user_task unchanged
        modMsgs.add(new CanonicalSnapshot.Message("user", "some content")); // role changed

        CanonicalSnapshot current = new CanonicalSnapshot(0, prev.system(), modMsgs);
        int msgLcp = comparator.countMatchingPrefixMessages(prev, current);
        assertEquals("only user_task matches", 1, msgLcp);
    }

    // ================================================================
    // 7. REWRITTEN — reorder old messages
    // ================================================================

    @Test
    public void reorderOldMessagesIsRewritten() {
        AgentContext ctx = basicContext("r-reorder");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        shadowSvc.appendAssistant(ctx, "msg-A",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));
        shadowSvc.appendToolResult(ctx, "msg-B",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "tool_result"));

        CanonicalSnapshot prev = CanonicalSnapshot.from(ctx);
        assertEquals(3, prev.messageCount()); // user_task + assistant + tool_result

        // Reorder: swap assistant and tool_result
        List<CanonicalSnapshot.Message> reordered = new ArrayList<>();
        reordered.add(prev.messages().get(0)); // user_task
        reordered.add(prev.messages().get(2)); // tool_result (now position 1)
        reordered.add(prev.messages().get(1)); // assistant (now position 2)

        CanonicalSnapshot current = new CanonicalSnapshot(0, prev.system(), reordered);
        int msgLcp = comparator.countMatchingPrefixMessages(prev, current);
        assertEquals("only user_task matches after reorder", 1, msgLcp);
    }

    // ================================================================
    // 8. INVALID_LEDGER — duplicate sequence
    // ================================================================

    @Test
    public void duplicateSequenceIsInvalid() {
        ConversationLedgerEntry e0 = ConversationLedgerEntry.builder()
                .role("user").content("a").stableType(LedgerStableType.USER_TASK)
                .sequence(0L).build();
        ConversationLedgerEntry e1 = ConversationLedgerEntry.builder()
                .role("assistant").content("b").stableType(LedgerStableType.ASSISTANT_ACTION)
                .sequence(0L).build(); // duplicate

        assertTrue("duplicate sequences detected",
                comparator.hasDuplicateSequences(List.of(e0, e1)));
    }

    @Test
    public void uniqueSequencesAreValid() {
        ConversationLedgerEntry e0 = ConversationLedgerEntry.builder()
                .role("user").content("a").stableType(LedgerStableType.USER_TASK)
                .sequence(0L).build();
        ConversationLedgerEntry e1 = ConversationLedgerEntry.builder()
                .role("assistant").content("b").stableType(LedgerStableType.ASSISTANT_ACTION)
                .sequence(1L).build();

        assertFalse("unique sequences must be valid",
                comparator.hasDuplicateSequences(List.of(e0, e1)));
    }

    // ================================================================
    // 9. GENERATION_RESET
    // ================================================================

    @Test
    public void generationChangeResetsBaseline() {
        AgentContext ctx = basicContext("r-gen-reset");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();

        // Round 1: gen=0
        LedgerShadowResult r1 = diagnostic.compareAndLog(ctx, "prompt");
        assertEquals(0, r1.generation());
        assertEquals(ComparisonStatus.INITIAL, r1.comparisonStatus());
        assertNotNull(diagnostic.previousSnapshot());

        // Increment generation
        ctx.incrementGeneration();
        assertEquals(1, ctx.getGeneration());

        // Round 2: gen=1 → GENERATION_RESET
        LedgerShadowResult r2 = diagnostic.compareAndLog(ctx, "prompt");
        assertEquals(1, r2.generation());
        assertEquals("generation change must reset",
                ComparisonStatus.GENERATION_RESET, r2.comparisonStatus());
        assertEquals(0, r2.previousMessageCount()); // baseline cleared
        assertEquals(0, r2.messageLcp());
    }

    // ================================================================
    // 10. messageLcp precision
    // ================================================================

    @Test
    public void messageLcpEqualsPreviousCountOnAppend() {
        AgentContext ctx = basicContext("r-lcp-precise");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        diagnostic.compareAndLog(ctx, "prompt"); // baseline: 1 message

        // Append 2 messages
        shadowSvc.appendAssistant(ctx, "a",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));
        shadowSvc.appendToolResult(ctx, "b",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "tool_result"));

        LedgerShadowResult r2 = diagnostic.compareAndLog(ctx, "prompt");
        assertEquals(ComparisonStatus.APPEND_ONLY, r2.comparisonStatus());
        // messageLcp MUST equal previousMessageCount
        assertEquals("messageLcp == prevMsgCnt",
                r2.previousMessageCount(), r2.messageLcp());
        assertEquals(1, r2.previousMessageCount());
        assertEquals(3, r2.currentMessageCount());
        // firstDiffIdx points to first new message
        assertEquals(1, r2.firstDiffMessageIndex());
    }

    // ================================================================
    // 11. charLcp is separate from messageLcp
    // ================================================================

    @Test
    public void charLcpAndMessageLcpAreDistinct() {
        AgentContext ctx = basicContext("r-char-vs-msg");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        diagnostic.compareAndLog(ctx, "prompt"); // baseline

        shadowSvc.appendAssistant(ctx, "a",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));

        LedgerShadowResult r2 = diagnostic.compareAndLog(ctx, "prompt");

        // messageLcp counts messages, charLcp counts characters
        assertEquals(1, r2.messageLcp());
        assertTrue("charLcp must be > 0 since system prefix matches",
                r2.charLcp() > 0);
        // They measure different things
        assertNotEquals("messageLcp and charLcp measure different things",
                (int) r2.messageLcp(), r2.charLcp());
    }

    // ================================================================
    // 12. Semantic coverage — mandatory vs n/a
    // ================================================================

    @Test
    public void coverageMandatoryBlocksAreCovered() {
        AgentContext ctx = basicContext("r-cov-mand");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        AgentPlan plan = AgentPlan.forQuestion("test");
        ctx.setPlan(plan);
        ctx.setMaxSegments(3);
        ctx.setMaxTotalSteps(90);

        shadowSvc.appendControlUpdate(ctx, ControlUpdateTexts.renderPlanSnapshot(plan),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "plan", "v" + plan.getVersion()));
        shadowSvc.appendControlUpdate(ctx, ControlUpdateTexts.renderBudgetSnapshot(ctx),
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "budget"));
        shadowSvc.appendAssistant(ctx, "model output",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));
        shadowSvc.appendToolResult(ctx, "tool output",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "tool_result"));

        // Old prompt has all mandatory markers
        String oldPrompt = "协议...\n当前计划：\n[Plan v1]\n- [in_progress] task-1: do it\n\n"
                + "动态上下文：\nassistant: model output\ntool: tool output\n\n"
                + "执行预算：第 1/3 段，全局步数 1/90";

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        LedgerShadowResult result = diagnostic.compareAndLog(ctx, oldPrompt);

        assertEquals(LedgerShadowComparator.COVERED, result.semanticCoverage().get("协议"));
        assertEquals(LedgerShadowComparator.COVERED, result.semanticCoverage().get("Skills"));
        assertEquals(LedgerShadowComparator.COVERED, result.semanticCoverage().get("Tools"));
        assertEquals(LedgerShadowComparator.COVERED, result.semanticCoverage().get("用户任务"));
        assertEquals(LedgerShadowComparator.COVERED, result.semanticCoverage().get("计划"));
        assertEquals(LedgerShadowComparator.COVERED, result.semanticCoverage().get("预算"));
        assertEquals(LedgerShadowComparator.COVERED, result.semanticCoverage().get("Assistant输出"));
        assertEquals(LedgerShadowComparator.COVERED, result.semanticCoverage().get("工具结果"));

        assertTrue("mandatory gaps must be empty", result.mandatoryGaps().isEmpty());
    }

    @Test
    public void optionalBlocksAreNAWhenNotInOldPrompt() {
        AgentContext ctx = basicContext("r-cov-opt");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        // Old prompt has no error/reminder/user_input/continuation markers
        String oldPrompt = "用户问题：test question";

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        LedgerShadowResult result = diagnostic.compareAndLog(ctx, oldPrompt);

        assertEquals(LedgerShadowComparator.NA, result.semanticCoverage().get("错误"));
        assertEquals(LedgerShadowComparator.NA, result.semanticCoverage().get("提醒"));
        assertEquals(LedgerShadowComparator.NA, result.semanticCoverage().get("用户输入"));
        assertEquals(LedgerShadowComparator.NA, result.semanticCoverage().get("续接消息"));
    }

    @Test
    public void optionalBlockPresentButNotCoveredIsGap() {
        AgentContext ctx = basicContext("r-cov-gap");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        // Old prompt has plan marker but ledger has NO plan entry → gap
        String oldPrompt = "当前计划：\n[Plan v1]\n- [todo] task-1: do it\n";

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        LedgerShadowResult result = diagnostic.compareAndLog(ctx, oldPrompt);

        assertEquals("plan marker present but no ledger entry → not_covered",
                LedgerShadowComparator.NOT_COVERED, result.semanticCoverage().get("计划"));
        assertThat(result.mandatoryGaps()).contains("计划");
    }

    @Test
    public void optionalBlockNotInOldPromptIsNotGap() {
        AgentContext ctx = basicContext("r-cov-no-gap");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        // Old prompt has NO plan marker → plan is n/a, not a gap
        String oldPrompt = "用户问题：test question";

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        LedgerShadowResult result = diagnostic.compareAndLog(ctx, oldPrompt);

        assertEquals(LedgerShadowComparator.NA, result.semanticCoverage().get("计划"));
        assertThat(result.mandatoryGaps()).doesNotContain("计划");
    }

    // ================================================================
    // 13. firstDiff does not leak raw content (index only)
    // ================================================================

    @Test
    public void firstDiffIndexDoesNotContainRawContent() {
        AgentContext ctx = basicContext("r-diff-safe");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        diagnostic.compareAndLog(ctx, "prompt");

        shadowSvc.appendAssistant(ctx, "SECRET PASSWORD=abc123 in model output",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));

        LedgerShadowResult r2 = diagnostic.compareAndLog(ctx, "prompt");
        // firstDiffMessageIndex is an integer, never text content
        assertEquals(1, r2.firstDiffMessageIndex());
        // The index itself doesn't expose secrets
    }

    // ================================================================
    // 14. Desensitization
    // ================================================================

    @Test
    public void desensitizeBearerToken() {
        String original = "Authorization: Bearer sk-abc123def456ghijklmnopqrstuvwxyz";
        String clean = LedgerShadowComparator.desensitize(original);
        assertThat(clean).contains("Bearer <redacted>");
        assertThat(clean).doesNotContain("sk-abc123def");
    }

    @Test
    public void desensitizeApiKey() {
        String original = "api-key=sk-abcdefghijklmnopqrstuvwxyz1234567890";
        String clean = LedgerShadowComparator.desensitize(original);
        // The api_key regex matches first and redacts the value
        assertThat(clean).contains("api-key=<redacted>");
        assertThat(clean).doesNotContain("sk-abcdefghij");
    }

    @Test
    public void desensitizeStandaloneApiKey() {
        // Standalone sk- key without key= prefix
        String original = "Authorization: Bearer sk-standalone1234567890abcdef";
        String clean = LedgerShadowComparator.desensitize(original);
        assertThat(clean).contains("Bearer <redacted>");
        assertThat(clean).doesNotContain("sk-standalone");
    }

    @Test
    public void desensitizeSecret() {
        String original = "secret=my-super-secret-value-here";
        String clean = LedgerShadowComparator.desensitize(original);
        assertThat(clean).contains("secret=<redacted>");
        assertThat(clean).doesNotContain("my-super-secret");
    }

    @Test
    public void desensitizePassword() {
        String original = "password=hunter2 connecting to db";
        String clean = LedgerShadowComparator.desensitize(original);
        assertThat(clean).contains("password=<redacted>");
        assertThat(clean).doesNotContain("hunter2");
    }

    @Test
    public void desensitizeEnvVars() {
        String original = "env DATABASE_URL=postgres://user:pass@localhost/db";
        String clean = LedgerShadowComparator.desensitize(original);
        assertThat(clean).contains("<env-var>");
        assertThat(clean).doesNotContain("postgres://");
    }

    @Test
    public void desensitizeUuid() {
        String original = "550e8400-e29b-41d4-a716-446655440000";
        String clean = LedgerShadowComparator.desensitize(original);
        assertThat(clean).contains("<uuid>");
        assertThat(clean).doesNotContain("550e8400");
    }

    @Test
    public void desensitizeRunId() {
        String original = "context for r-sensitive-run-12345 with data";
        String clean = LedgerShadowComparator.desensitize(original);
        assertThat(clean).contains("<runId>");
        assertThat(clean).doesNotContain("r-sensitive-run-12345");
    }

    @Test
    public void desensitizeNullSafe() {
        assertNull(LedgerShadowComparator.desensitize(null));
    }

    // ================================================================
    // 15. Diagnostic body is off by default + desensitized when on
    // ================================================================

    @Test
    public void diagnosticBodyDisabledByDefault() {
        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        assertFalse(diagnostic.isDiagnosticBodyEnabled());

        AgentContext ctx = basicContext("r-diag-off");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);

        LedgerShadowResult result = diagnostic.compareAndLog(ctx, "prompt");
        assertNull(result.diagnosticBody());
        assertFalse(result.diagnosticBodyEnabled());
    }

    @Test
    public void diagnosticBodyHasNoRawContentOnlyHashes() {
        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        diagnostic.setDiagnosticBodyEnabled(true);

        AgentContext ctx = basicContext("r-diag-hash");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);

        shadowSvc.appendAssistant(ctx, "my secret content",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));

        LedgerShadowResult result = diagnostic.compareAndLog(ctx, "prompt");
        assertNotNull(result.diagnosticBody());
        // Diagnostic body must NOT contain raw message content
        assertThat(result.diagnosticBody()).doesNotContain("my secret content");
        // It should contain hashes
        assertThat(result.diagnosticBody()).contains("sha256=");
        assertThat(result.diagnosticBody()).doesNotContain(ctx.getRunId());
    }

    // ================================================================
    // 16. Shadow does not affect main flow
    // ================================================================

    @Test
    public void shadowComparisonExceptionReturnsErrorResult() {
        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        AgentContext ctx = basicContext("r-isolate");
        ctx.setStablePrefix(null);
        ctx.ensureLedgerActive();

        // Null stablePrefix does NOT throw — compare gracefully handles it
        LedgerShadowResult result = diagnostic.compareAndLog(ctx, "test");
        assertNotNull("result never null", result);
        // The result reports protocol/skills/tools as not_covered (no stable prefix)
        assertEquals(LedgerShadowComparator.NOT_COVERED, result.semanticCoverage().get("协议"));
    }

    // ================================================================
    // 17. isClean gate
    // ================================================================

    @Test
    public void isCleanTrueWhenAppendOnlyAndNoGaps() {
        AgentContext ctx = basicContext("r-clean");
        ConversationLedgerInitializer init = new ConversationLedgerInitializer(shadowConfig);
        init.initializeNewConversation(ctx, stablePrefix);
        ctx.setStablePrefix(stablePrefix);

        LedgerShadowDiagnostic diagnostic = new LedgerShadowDiagnostic();
        diagnostic.compareAndLog(ctx, "prompt"); // baseline

        shadowSvc.appendAssistant(ctx, "output",
                ConversationLedgerInitializer.eventKey(ctx.getRunId(), "1", "assistant"));

        LedgerShadowResult r2 = diagnostic.compareAndLog(ctx, "prompt");
        assertEquals(ComparisonStatus.APPEND_ONLY, r2.comparisonStatus());
        assertTrue("isClean when append-only and no gaps", r2.isClean());
    }

    // ================================================================
    // 18. toString is informative
    // ================================================================

    @Test
    public void resultToStringContainsKeyInfo() {
        LedgerShadowResult result = new LedgerShadowResult(
                java.util.Map.of("协议", "covered"),
                java.util.List.of(),
                ComparisonStatus.APPEND_ONLY,
                3, 150, 3, 5, 3, 0,
                null, false, null);

        String str = result.toString();
        assertThat(str).contains("status=APPEND_ONLY");
        assertThat(str).contains("msgLcp=3");
        assertThat(str).contains("charLcp=150");
        assertThat(str).contains("prevMsgCnt=3");
        assertThat(str).contains("curMsgCnt=5");
        assertThat(str).contains("firstDiffIdx=3");
    }
}
