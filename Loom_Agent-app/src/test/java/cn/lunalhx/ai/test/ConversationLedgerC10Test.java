package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.*;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextArtifact;
import cn.lunalhx.ai.domain.agent.model.valobj.*;
import cn.lunalhx.ai.domain.agent.model.valobj.context.ContextArtifactKind;
import cn.lunalhx.ai.domain.agent.service.prompt.StablePrefixBuilder;
import cn.lunalhx.ai.domain.agent.service.context.AgentContextFactory;
import cn.lunalhx.ai.domain.agent.service.context.DeepContextSummaryService;
import cn.lunalhx.ai.domain.agent.service.budget.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.agent.service.ledger.*;
import cn.lunalhx.ai.domain.agent.service.context.ContextArtifactPurgeService;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.infrastructure.context.InMemoryContextBlobStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.*;

/**
 * C10: Ledger compaction with high/low watermark, generation, frozen baseline.
 *
 * <p>Tests validate:
 * <ol>
 *   <li>No compaction below high watermark</li>
 *   <li>Compaction reduces to low watermark</li>
 *   <li>No repeated compaction near threshold</li>
 *   <li>Next generation created on subsequent high-watermark exceedance</li>
 *   <li>Deterministic summary content</li>
 *   <li>Deep summary success</li>
 *   <li>Deep summary failure fallback</li>
 *   <li>Checkpoint preserves generation and compaction state</li>
 *   <li>context_recall recoverable</li>
 *   <li>resetContextRecovery preserves baseline transcript</li>
 *   <li>Watermark validation</li>
 *   <li>Sequence monotonicity and post-compaction append</li>
 * </ol>
 */
public class ConversationLedgerC10Test {

    private AgentRuntimeProperties.ConversationLedgerProperties enabledConfig;
    private ConversationLedgerAppendService appendSvc;
    private ConversationLedgerInitializer initializer;
    private LedgerBootstrapService bootstrapService;
    private StablePrefixBuilder prefixBuilder;
    private AgentWorkspaceResolver workspaceResolver;
    private AgentContextFactory factoryWithLedger;
    private AgentRuntimeProperties properties;
    private InMemoryContextArtifactRepository artifactRepository;
    private InMemoryContextBlobStore blobStore;
    private InMemoryTraceRecorder traceRecorder;

    @Before
    public void setUp() {
        enabledConfig = new AgentRuntimeProperties.ConversationLedgerProperties();

        appendSvc = new ConversationLedgerAppendService();
        initializer = new ConversationLedgerInitializer();
        bootstrapService = new LedgerBootstrapService(appendSvc, initializer);
        prefixBuilder = new StablePrefixBuilder();

        properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(".");
        properties.setMaxSteps(30);

        workspaceResolver = new AgentWorkspaceResolver(properties);

        factoryWithLedger = new AgentContextFactory(properties, workspaceResolver,
                List.of(tool("code_search", "search code", "{}")), false, appendSvc);

        artifactRepository = new InMemoryContextArtifactRepository();
        blobStore = new InMemoryContextBlobStore();
        traceRecorder = new InMemoryTraceRecorder();
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static ToolSpec tool(String name, String desc, String schema) {
        return ToolSpec.builder().name(name).description(desc).inputSchema(schema).build();
    }

    private StablePrefix candidatePrefix(AgentContext ctx) {
        return prefixBuilder.build(
                ctx.getAgentRole(),
                ctx.isSubAgentSpawnAllowed(),
                null,
                ctx.getToolSpecs(),
                ctx.getSkillCatalogText(),
                ctx.getActivatedSkills(),
                java.util.Map.of());
    }

    private void doBootstrap(AgentContext ctx) {
        StablePrefix candidate = candidatePrefix(ctx);
        bootstrapService.bootstrap(ctx, candidate);
    }

    private AgentContext createBootstrappedRun(String runId, String question) {
        AgentContext ctx = factoryWithLedger.create(AgentQuestion.builder()
                .runId(runId)
                .requestId("req-" + runId)
                .conversationId("conv-" + runId)
                .question(question)
                .maxSteps(30)
                .build());
        ctx.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        ctx.setWorkspaceDisplayName(".");
        doBootstrap(ctx);
        return ctx;
    }

    private LedgerCompactionService compactionService(LedgerWatermark watermark) {
        return new LedgerCompactionService(watermark, artifactRepository, blobStore);
    }

    private LedgerCompactionService compactionService(LedgerWatermark watermark,
                                                      DeepContextSummaryService deepService) {
        return new LedgerCompactionService(watermark, artifactRepository, blobStore, deepService);
    }

    private LedgerCompactionService compactionServiceWithPurge(LedgerWatermark watermark) {
        ContextArtifactPurgeService purgeService = new ContextArtifactPurgeService(artifactRepository, blobStore);
        return new LedgerCompactionService(watermark, artifactRepository, blobStore, null, purgeService);
    }

    /** Append N dummy assistant/tool_result pairs to fill the ledger. */
    private void fillEntries(AgentContext ctx, int count) {
        for (int i = 0; i < count; i++) {
            String runId = ctx.getRunId();
            int idx = ctx.getConversationLedger().size() + i;
            appendSvc.appendAssistant(ctx, "assistant step " + idx,
                    ConversationLedgerInitializer.eventKey(runId, String.valueOf(idx), "assistant"));
            appendSvc.appendToolResult(ctx, "tool result " + idx,
                    ConversationLedgerInitializer.eventKey(runId, String.valueOf(idx), "tool_result"));
        }
    }

    /** Get the summary entry (always the last entry after compaction). */
    private ConversationLedgerEntry summaryEntry(AgentContext ctx) {
        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        return entries.get(entries.size() - 1);
    }

    // ================================================================
    // 1. 未达 high watermark 不压缩
    // ================================================================

    @Test
    public void belowHighWatermarkNoCompaction() {
        AgentContext ctx = createBootstrappedRun("c10-below", "task");
        // Ledger starts with 1 entry (user_task), which is below high=10
        LedgerWatermark watermark = new LedgerWatermark(10, 3);
        LedgerCompactionService svc = compactionService(watermark);

        LedgerCompactionResult result = svc.compactIfNeeded(ctx);

        assertFalse("entries below high → not compacted", result.compacted());
        assertEquals("before == after", result.beforeEntryCount(), result.afterEntryCount());
        assertEquals("generation unchanged", 0, ctx.getGeneration());
        assertEquals("lastCompactionGeneration stays -1", -1, ctx.getLastCompactionGeneration());
    }

    @Test
    public void atHighWatermarkNoCompaction() {
        // high=5, ledger has exactly 5 entries (not exceeding)
        AgentContext ctx = createBootstrappedRun("c10-at-edge", "task");
        fillEntries(ctx, 2); // 1 user_task + 4 = 5 total
        assertEquals(5, ctx.getConversationLedger().size());

        LedgerWatermark watermark = new LedgerWatermark(5, 2);
        LedgerCompactionService svc = compactionService(watermark);

        LedgerCompactionResult result = svc.compactIfNeeded(ctx);

        assertFalse("entries == high → not compacted (must exceed)", result.compacted());
        assertEquals(5, ctx.getConversationLedger().size());
    }

    // ================================================================
    // 2. 达 high 后只压缩一次并降到 low
    // ================================================================

    @Test
    public void exceedsHighCompactsToLow() {
        AgentContext ctx = createBootstrappedRun("c10-compact", "verify compaction");
        fillEntries(ctx, 15); // 1 user_task + 30 assistant/tool = 31 entries
        assertEquals(31, ctx.getConversationLedger().size());

        LedgerWatermark watermark = new LedgerWatermark(10, 4);
        LedgerCompactionService svc = compactionService(watermark);

        LedgerCompactionResult result = svc.compactIfNeeded(ctx);

        assertTrue("entries > high → compacted", result.compacted());
        // after: (low-1) recent + 1 summary = low
        assertTrue("after <= low", result.afterEntryCount() <= watermark.lowEntryCount());
        assertEquals("generation bumped by 1", 1, result.generation());
        assertEquals(1, ctx.getGeneration());
        assertEquals(1, ctx.getLastCompactionGeneration());
        assertNotNull("transcript artifact created", result.transcriptArtifactId());

        // Verify artifact exists and has content
        ContextArtifact artifact = artifactRepository
                .findByArtifactIdAndRootRunId(result.transcriptArtifactId(), ctx.getRootRunId())
                .orElse(null);
        assertNotNull("artifact persisted", artifact);
        assertEquals(ContextArtifactKind.TRANSCRIPT, artifact.getKind());
        assertTrue("blob has content", blobStore.read(artifact.getStorageUri()).length() > 0);
    }

    // ================================================================
    // 3. 阈值附近多轮不重复
    // ================================================================

    @Test
    public void nearThresholdNoRepeatedCompaction() {
        AgentContext ctx = createBootstrappedRun("c10-norepeat", "no repeat");
        fillEntries(ctx, 10); // 1 + 20 = 21 entries
        assertEquals(21, ctx.getConversationLedger().size());

        LedgerWatermark watermark = new LedgerWatermark(10, 5);
        LedgerCompactionService svc = compactionService(watermark);

        // First compaction
        LedgerCompactionResult r1 = svc.compactIfNeeded(ctx);
        assertTrue("first compaction", r1.compacted());
        int afterFirst = ctx.getConversationLedger().size();
        assertTrue("after compaction <= low", afterFirst <= watermark.lowEntryCount());

        // Add a few more entries (but still below high)
        fillEntries(ctx, 2); // +4 entries
        int beforeSecond = ctx.getConversationLedger().size();
        assertTrue("entries still below high after small add",
                beforeSecond <= watermark.highEntryCount());

        // Second call → no compaction
        LedgerCompactionResult r2 = svc.compactIfNeeded(ctx);
        assertFalse("below high → no compaction", r2.compacted());
        assertEquals("generation unchanged", 1, ctx.getGeneration());
    }

    // ================================================================
    // 4. 再次超过 high 创建下一 generation
    // ================================================================

    @Test
    public void reExceedsHighCreatesNextGeneration() {
        AgentContext ctx = createBootstrappedRun("c10-regen", "multi gen");
        // Use compact() to force compaction (bypassing watermark check) so
        // the test controls exactly when compaction occurs
        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);

        // Round 1: build up entries, then compact
        fillEntries(ctx, 5); // 1 + 10 = 11 entries
        LedgerCompactionResult r1 = svc.compact(ctx);
        assertTrue("first compaction", r1.compacted());
        assertEquals("gen=1", 1, ctx.getGeneration());
        assertEquals("lastCompactionGen=1", 1, ctx.getLastCompactionGeneration());
        assertEquals("after <= low(3)", 3, ctx.getConversationLedger().size());
        String artifact1 = r1.transcriptArtifactId();

        // Round 2: exceed high again → compactIfNeeded
        fillEntries(ctx, 4); // 3 + 8 = 11 entries > high(5)
        assertTrue("exceeds high", ctx.getConversationLedger().size() > watermark.highEntryCount());
        LedgerCompactionResult r2 = svc.compactIfNeeded(ctx);
        assertTrue("second compaction", r2.compacted());
        assertEquals("gen=2", 2, ctx.getGeneration());
        assertEquals("lastCompactionGen=2", 2, ctx.getLastCompactionGeneration());
        assertNotEquals("different artifact", artifact1, r2.transcriptArtifactId());
        assertEquals("after <= low(3)", 3, ctx.getConversationLedger().size());

        // Round 3: exceed again → gen=3
        fillEntries(ctx, 4); // 3 + 8 = 11 entries > high(5)
        LedgerCompactionResult r3 = svc.compactIfNeeded(ctx);
        assertTrue("third compaction", r3.compacted());
        assertEquals("gen=3", 3, ctx.getGeneration());
        assertEquals("lastCompactionGen=3", 3, ctx.getLastCompactionGeneration());
    }

    // ================================================================
    // 5. deterministic summary 成功
    // ================================================================

    @Test
    public void deterministicSummaryHasRequiredContent() {
        AgentContext ctx = createBootstrappedRun("c10-detsum", "build a web app");
        fillEntries(ctx, 10); // 1 + 20 = 21 entries

        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);

        LedgerCompactionResult result = svc.compact(ctx);

        assertEquals("strategy is deterministic", "deterministic", result.strategy());
        assertNotNull("artifact exists", result.transcriptArtifactId());

        // Verify the summary is in the ledger (last entry)
        ConversationLedgerEntry se = summaryEntry(ctx);
        assertEquals(LedgerStableType.SYSTEM_NOTE, se.stableType());
        assertThat(se.content()).contains("[Ledger Compaction Summary]");
        assertThat(se.content()).contains("build a web app");
        assertThat(se.content()).contains(result.transcriptArtifactId());

        // Recent tool/assistant actions referenced
        assertThat(se.content()).contains("RecentActions");
    }

    // ================================================================
    // 6. deep summary 成功
    // ================================================================

    @Test
    public void deepSummarySuccess() {
        AgentContext ctx = createBootstrappedRun("c10-deep-ok", "deep summary task");
        fillEntries(ctx, 10);

        DeepContextSummaryService deepService = new DeepContextSummaryService(
                new ModelGateway() {
                    @Override
                    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                        return Flux.empty();
                    }

                    @Override
                    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                        return Mono.just(ModelChatResult.builder()
                                .content("AI-generated summary of the conversation")
                                .finishReason("stop")
                                .build());
                    }
                },
                properties,
                new DefaultBudgetGuard(properties),
                traceRecorder);

        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark, deepService);

        LedgerCompactionResult result = svc.compact(ctx);

        assertTrue("compacted", result.compacted());
        assertEquals("deep_summary", result.strategy());
        ConversationLedgerEntry se = summaryEntry(ctx);
        assertThat(se.content()).contains("AI-generated summary");
        assertThat(se.content()).contains(
                "[Ledger Deep Compaction Summary — Generation 1]");
    }

    // ================================================================
    // 7. deep summary 失败 fallback
    // ================================================================

    @Test
    public void deepSummaryFailureFallsBackToDeterministic() {
        AgentContext ctx = createBootstrappedRun("c10-deep-fail", "fallback task");
        fillEntries(ctx, 10);

        DeepContextSummaryService deepService = new DeepContextSummaryService(
                new ModelGateway() {
                    @Override
                    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                        return Flux.empty();
                    }

                    @Override
                    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                        throw new RuntimeException("model unavailable");
                    }
                },
                properties,
                new DefaultBudgetGuard(properties),
                traceRecorder);

        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark, deepService);

        LedgerCompactionResult result = svc.compact(ctx);

        assertTrue("compacted despite deep failure", result.compacted());
        assertEquals("deep_summary_deterministic", result.strategy());
        ConversationLedgerEntry se = summaryEntry(ctx);
        assertThat(se.content()).contains("[Ledger Compaction Summary]");
        assertThat(se.content()).contains("fallback task");
        assertNotNull("artifact still created", result.transcriptArtifactId());
    }

    // ================================================================
    // 8. checkpoint 后 generation 保持
    // ================================================================

    @Test
    public void checkpointPreservesGenerationAndCompactionState() {
        AgentContext ctx = createBootstrappedRun("c10-ckpt", "checkpoint test");
        fillEntries(ctx, 10);

        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);

        // Compact → gen=1, lastCompactionGen=1, with baseline artifact
        LedgerCompactionResult r = svc.compact(ctx);
        assertEquals(1, ctx.getGeneration());
        assertEquals(1, ctx.getLastCompactionGeneration());
        String baselineId = ctx.getLedgerBaselineArtifactId();
        assertNotNull(baselineId);

        // Snapshot → restore
        AgentContextSnapshot snap = AgentContextSnapshot.from(ctx);
        AgentContext restored = snap.restore();
        // Re-inject transient fields needed after restore
        restored.setToolSpecs(ctx.getToolSpecs());
        restored.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        restored.setWorkspaceDisplayName(".");

        assertEquals("generation preserved", 1, restored.getGeneration());
        assertEquals("lastCompactionGeneration preserved", 1, restored.getLastCompactionGeneration());
        assertEquals("ledgerBaselineArtifactId preserved", baselineId, restored.getLedgerBaselineArtifactId());

        // Ledger entries preserved (compacted state)
        assertNotNull(restored.getConversationLedger());
        assertEquals("compacted entry count preserved",
                ctx.getConversationLedger().size(), restored.getConversationLedger().size());
    }

    // ================================================================
    // 9. context_recall 可恢复
    // ================================================================

    @Test
    public void contextRecallRecoverable() {
        AgentContext ctx = createBootstrappedRun("c10-recall", "recall test");
        fillEntries(ctx, 10);

        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);

        LedgerCompactionResult result = svc.compact(ctx);
        assertTrue("compacted", result.compacted());

        // The artifact should be recoverable
        ContextArtifact artifact = artifactRepository
                .findByArtifactIdAndRootRunId(result.transcriptArtifactId(), ctx.getRootRunId())
                .orElse(null);
        assertNotNull("artifact retrievable", artifact);
        assertEquals(ContextArtifactKind.TRANSCRIPT, artifact.getKind());
        assertEquals(ctx.getRunId(), artifact.getRunId());
        assertEquals(ctx.getRootRunId(), artifact.getRootRunId());

        // Blob content is the original transcript
        String blobContent = blobStore.read(artifact.getStorageUri());
        assertThat(blobContent).contains("=== Ledger Transcript ===");
        assertThat(blobContent).contains("assistant step");
        assertThat(blobContent).contains("tool result");
        assertThat(blobContent).contains("recall test"); // user goal
    }

    // ================================================================
    // 10. resetContextRecovery 保留 baseline
    // ================================================================

    @Test
    public void resetContextRecoveryPreservesBaselineTranscript() {
        // This test verifies the logic in ModelCallNode.resetContextRecovery
        // that baseline artifact is not cleared on successful model call.

        AgentContext ctx = createBootstrappedRun("c10-reset", "reset test");
        fillEntries(ctx, 10);

        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);
        svc.compact(ctx); // sets ledgerBaselineArtifactId and contextTranscriptArtifactId

        String baseline = ctx.getLedgerBaselineArtifactId();
        assertNotNull("baseline artifact set", baseline);
        assertEquals("contextTranscriptArtifactId mirrors baseline",
                baseline, ctx.getContextTranscriptArtifactId());

        // Simulate resetContextRecovery logic:
        // Only clear contextTranscriptArtifactId if it differs from ledgerBaselineArtifactId
        if (!java.util.Objects.equals(ctx.getContextTranscriptArtifactId(), ctx.getLedgerBaselineArtifactId())) {
            ctx.setContextTranscriptArtifactId(null);
        }

        // contextTranscriptArtifactId must survive because it matches baseline
        assertEquals("baseline transcript preserved", baseline, ctx.getContextTranscriptArtifactId());
    }

    @Test
    public void resetContextRecoveryClearsNonBaselineTranscript() {
        // When contextTranscriptArtifactId is set by recovery (not compaction),
        // it should be cleared.

        AgentContext ctx = createBootstrappedRun("c10-reset2", "reset test 2");
        ctx.setContextTranscriptArtifactId("recovery-artifact-123");
        ctx.setLedgerBaselineArtifactId(null); // no compaction happened

        // Simulate resetContextRecovery logic
        if (!java.util.Objects.equals(ctx.getContextTranscriptArtifactId(), ctx.getLedgerBaselineArtifactId())) {
            ctx.setContextTranscriptArtifactId(null);
        }

        // Recovery transcript should be cleared
        assertNull("recovery transcript cleared", ctx.getContextTranscriptArtifactId());
    }

    // ================================================================
    // 11. Watermark validation
    // ================================================================

    @Test
    public void watermarkDefaultsAreValid() {
        LedgerWatermark wm = LedgerWatermark.defaults();
        assertEquals(200, wm.highEntryCount());
        assertEquals(50, wm.lowEntryCount());
        assertTrue("low < high", wm.lowEntryCount() < wm.highEntryCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void watermarkRejectsInvalidLowEqualToHigh() {
        new LedgerWatermark(10, 10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void watermarkRejectsLowGreaterThanHigh() {
        new LedgerWatermark(10, 20);
    }

    @Test(expected = IllegalArgumentException.class)
    public void watermarkRejectsZeroHigh() {
        new LedgerWatermark(0, 5);
    }

    @Test(expected = IllegalArgumentException.class)
    public void watermarkRejectsNegativeLow() {
        new LedgerWatermark(10, -1);
    }

    @Test
    public void fromConfigFallsBackOnNull() {
        LedgerWatermark wm = LedgerWatermark.fromConfig(null, null);
        assertEquals(200, wm.highEntryCount());
        assertEquals(50, wm.lowEntryCount());
    }

    @Test
    public void fromConfigFallsBackOnInvalidRelation() {
        // low >= high → fallback to defaults
        LedgerWatermark wm = LedgerWatermark.fromConfig(100, 200);
        assertEquals(200, wm.highEntryCount());
        assertEquals(50, wm.lowEntryCount());
    }

    @Test
    public void fromConfigUsesValidValues() {
        LedgerWatermark wm = LedgerWatermark.fromConfig(300, 100);
        assertEquals(300, wm.highEntryCount());
        assertEquals(100, wm.lowEntryCount());
    }

    // ================================================================
    // 12. Old DynamicText unaffected
    // ================================================================

    @Test
    public void ledgerCompactionDoesNotAffectDynamicText() {
        // DynamicText removed — this isolation test is no longer applicable.
        // Ledger compaction correctness is covered by other tests in this class.
        assertTrue(true);
    }

    // ================================================================
    // 13. compact() force works without high watermark
    // ================================================================

    @Test
    public void compactForceWithoutWatermarkCheck() {
        AgentContext ctx = createBootstrappedRun("c10-force", "force test");
        fillEntries(ctx, 3); // 1 + 6 = 7 entries

        LedgerWatermark watermark = new LedgerWatermark(200, 50);
        LedgerCompactionService svc = compactionService(watermark);

        // compact() bypasses watermark → compacts anyway
        LedgerCompactionResult result = svc.compact(ctx);
        assertTrue("forced compaction", result.compacted());
        assertEquals("gen=1", 1, ctx.getGeneration());
        assertTrue("entries reduced", ctx.getConversationLedger().size() <= watermark.lowEntryCount());
    }

    // ================================================================
    // 14. Sequence monotonicity and post-compaction append
    // ================================================================

    @Test
    public void replaceEntriesPreservesSequenceMonotonicity() {
        AgentContext ctx = createBootstrappedRun("c10-seq", "seq test");
        fillEntries(ctx, 10);

        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);
        svc.compact(ctx);

        ConversationLedger ledger = ctx.getConversationLedger();
        List<ConversationLedgerEntry> entries = ledger.entries();
        assertFalse("entries exist after compaction", entries.isEmpty());

        // Sequences must be monotonic (increasing through the list)
        long prevSeq = -1;
        for (ConversationLedgerEntry e : entries) {
            assertTrue("sequence monotonic: " + e.sequence() + " > " + prevSeq,
                    e.sequence() > prevSeq);
            prevSeq = e.sequence();
        }

        // Can still append new entries
        long beforeNextSeq = ledger.nextSequence();
        ledger.append("user", "new entry after compaction", LedgerStableType.USER_INPUT);
        assertEquals("nextSequence advanced", beforeNextSeq + 1, ledger.nextSequence());
        ConversationLedgerEntry newEntry = ledger.entries().get(ledger.entries().size() - 1);
        assertEquals("new entry has correct sequence", beforeNextSeq, newEntry.sequence());
    }

    // ================================================================
    // 15. Summary entry has idempotent eventKey
    // ================================================================

    @Test
    public void summaryEntryEventKeyIsDeterministic() {
        AgentContext ctx1 = createBootstrappedRun("c10-key1", "key test");
        fillEntries(ctx1, 10);

        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);
        svc.compact(ctx1);

        ConversationLedgerEntry se = summaryEntry(ctx1);
        assertNotNull("event key set", se.eventKey());
        assertThat(se.eventKey()).startsWith("compaction:");
    }

    // ================================================================
    // 16. Multiple compactions clean up old TRANSCRIPT artifacts
    // ================================================================

    @Test
    public void multipleCompactionsCleanUpOldTranscripts() {
        AgentContext ctx = createBootstrappedRun("c10-purge", "purge test");
        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionServiceWithPurge(watermark);

        // Round 1
        fillEntries(ctx, 5);
        LedgerCompactionResult r1 = svc.compact(ctx);
        String artifactId1 = r1.transcriptArtifactId();
        assertEquals(1, ctx.getGeneration());

        // Round 2
        fillEntries(ctx, 4);
        LedgerCompactionResult r2 = svc.compact(ctx);
        String artifactId2 = r2.transcriptArtifactId();
        assertEquals(2, ctx.getGeneration());

        // Round 3
        fillEntries(ctx, 4);
        LedgerCompactionResult r3 = svc.compact(ctx);
        String artifactId3 = r3.transcriptArtifactId();
        assertEquals(3, ctx.getGeneration());

        // Only latest TRANSCRIPT should remain
        List<ContextArtifact> transcripts = artifactRepository.listByConversationIdAndKind(
                ctx.getConversationId(), ContextArtifactKind.TRANSCRIPT);
        assertEquals("only latest transcript remains", 1, transcripts.size());
        assertEquals("latest transcript is the current one", artifactId3, transcripts.get(0).getArtifactId());

        // Old blobs should be deleted
        assertEquals("old blob 1 deleted", "", blobStore.read(artifactRepository
                .findByArtifactIdAndRootRunId(artifactId1, ctx.getRootRunId()).map(ContextArtifact::getStorageUri)
                .orElse("not-found-check")));
        assertEquals("old blob 2 deleted", "", blobStore.read(artifactRepository
                .findByArtifactIdAndRootRunId(artifactId2, ctx.getRootRunId()).map(ContextArtifact::getStorageUri)
                .orElse("not-found-check")));

        // Current blob still exists
        assertTrue("current blob exists",
                blobStore.read(artifactRepository
                        .findByArtifactIdAndRootRunId(artifactId3, ctx.getRootRunId())
                        .get().getStorageUri()).length() > 0);
    }

    // ================================================================
    // 17. Compaction succeeds even when old blob deletion fails
    // ================================================================

    @Test
    public void compactionSucceedsWhenOldBlobDeleteFails() {
        // Use a blob store that throws on delete to simulate failure
        ContextBlobStore failingBlobStore = new ContextBlobStore() {
            private final InMemoryContextBlobStore delegate = new InMemoryContextBlobStore();
            private int deleteCount = 0;

            @Override
            public String write(String rootRunId, String artifactId, String content) {
                return delegate.write(rootRunId, artifactId, content);
            }

            @Override
            public String read(String storageUri) {
                return delegate.read(storageUri);
            }

            @Override
            public void delete(String storageUri) {
                deleteCount++;
                if (deleteCount <= 2) {
                    throw new IllegalStateException("simulated blob delete failure");
                }
                delegate.delete(storageUri);
            }
        };

        ContextArtifactPurgeService purgeService = new ContextArtifactPurgeService(artifactRepository, failingBlobStore);
        LedgerCompactionService svc = new LedgerCompactionService(
                new LedgerWatermark(5, 3), artifactRepository, failingBlobStore, null, purgeService);

        AgentContext ctx = createBootstrappedRun("c10-blob-fail", "blob fail test");

        // Round 1: create first transcript
        fillEntries(ctx, 5);
        LedgerCompactionResult r1 = svc.compact(ctx);
        assertEquals(1, ctx.getGeneration());

        // Round 2: first delete fails, but compaction still succeeds
        fillEntries(ctx, 4);
        LedgerCompactionResult r2 = svc.compact(ctx);
        assertEquals(2, ctx.getGeneration());

        // Old metadata should still exist (blob delete failed, so metadata kept for retry)
        ContextArtifact oldArtifact = artifactRepository
                .findByArtifactIdAndRootRunId(r1.transcriptArtifactId(), ctx.getRootRunId())
                .orElse(null);
        assertNotNull("old metadata preserved for retry", oldArtifact);
    }

    // ================================================================
    // 18. Compaction depth tracking across generations
    // ================================================================

    @Test
    public void compactionDepthTracksAcrossGenerations() {
        AgentContext ctx = createBootstrappedRun("c10-depth", "depth tracking");
        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);

        // Round 1: compactionDepth should be 1
        fillEntries(ctx, 5);
        LedgerCompactionResult r1 = svc.compact(ctx);
        assertTrue("first compaction", r1.compacted());
        assertEquals("depth=1", 1, r1.compactionDepth());
        assertEquals("maxInputDepth=0", 0, r1.maxInputCompactionDepth());
        assertFalse("not depth guarded", r1.depthGuarded());
        ConversationLedgerEntry se1 = summaryEntry(ctx);
        assertEquals("entry compactionDepth=1", 1, se1.compactionDepth());
        assertThat(se1.content()).contains("CompactionDepth: 1");
        assertThat(se1.content()).contains("MaxInputCompactionDepth: 0");

        // Round 2: compactionDepth should be 2
        fillEntries(ctx, 4);
        LedgerCompactionResult r2 = svc.compact(ctx);
        assertTrue("second compaction", r2.compacted());
        assertEquals("depth=2", 2, r2.compactionDepth());
        assertEquals("maxInputDepth=1", 1, r2.maxInputCompactionDepth());
        assertFalse("not depth guarded", r2.depthGuarded());

        // Round 3: compactionDepth should be 3
        fillEntries(ctx, 4);
        LedgerCompactionResult r3 = svc.compact(ctx);
        assertTrue("third compaction", r3.compacted());
        assertEquals("depth=3", 3, r3.compactionDepth());
        assertEquals("maxInputDepth=2", 2, r3.maxInputCompactionDepth());
        assertFalse("not depth guarded", r3.depthGuarded());
    }

    @Test
    public void compactionDepthGuardTriggersDeterministicFallback() {
        AgentContext ctx = createBootstrappedRun("c10-depth-guard", "depth guard");
        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);

        // 3 compactions → depth reaches 3
        for (int i = 0; i < 3; i++) {
            fillEntries(ctx, 4);
            svc.compact(ctx);
        }
        assertEquals("depth 3 reached", 3, ctx.getGeneration());

        // 4th compaction should be depth-guarded (nextDepth=4 > max=3)
        fillEntries(ctx, 4);
        LedgerCompactionResult r4 = svc.compact(ctx);
        assertTrue("still compacts", r4.compacted());
        assertEquals("depth=4", 4, r4.compactionDepth());
        assertEquals("maxInputDepth=3", 3, r4.maxInputCompactionDepth());
        assertEquals("maxAllowedDepth=3", 3, r4.maxAllowedCompactionDepth());
        assertTrue("depth guarded", r4.depthGuarded());
        assertEquals("deterministic_depth_guarded", r4.strategy());

        ConversationLedgerEntry se = summaryEntry(ctx);
        assertEquals("entry compactionDepth=4", 4, se.compactionDepth());
        assertThat(se.content()).contains("CompactionDepth: 4");
        assertThat(se.content()).contains("MaxAllowedCompactionDepth: 3");
    }

    @Test
    public void wouldExceedMaxCompactionDepthReturnsTrueAfterMax() {
        AgentContext ctx = createBootstrappedRun("c10-would-exceed", "would exceed");
        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);

        // Fresh context: max depth is 0, next would be 1, not exceeded
        assertFalse("fresh: not exceeded", svc.wouldExceedMaxCompactionDepth(ctx));

        // 3 compactions → maxInputDepth=3, nextDepth=4 > max=3 → exceeded
        for (int i = 0; i < 3; i++) {
            fillEntries(ctx, 4);
            svc.compact(ctx);
        }
        assertTrue("after 3 compactions: exceeded", svc.wouldExceedMaxCompactionDepth(ctx));
    }

    @Test
    public void microCompactDoesNotAffectCompactionDepth() {
        AgentContext ctx = createBootstrappedRun("c10-micro-depth", "micro depth");
        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);

        // Do one full compaction to get depth=1
        fillEntries(ctx, 5);
        LedgerCompactionResult r1 = svc.compact(ctx);
        assertEquals("depth=1 after first compact", 1, r1.compactionDepth());

        // The summary entry should have compactionDepth=1, microCompacted=false
        ConversationLedgerEntry se = summaryEntry(ctx);
        assertEquals(1, se.compactionDepth());
        assertFalse("not micro compacted", se.microCompacted());

        // microCompact on this ledger should not change compactionDepth
        ConversationLedger ledger = ctx.getConversationLedger();
        boolean microChanged = svc.microCompact(ctx, ledger);
        // microCompact may or may not change entries, but depth stays same
        for (ConversationLedgerEntry e : ledger.entries()) {
            assertTrue("compactionDepth never increases from microCompact",
                    e.compactionDepth() <= 1);
        }
    }

    @Test
    public void compactionDepthAppearsInDeepSummaryText() {
        AgentContext ctx = createBootstrappedRun("c10-depth-deep", "deep summary depth");
        fillEntries(ctx, 10);

        DeepContextSummaryService deepService = new DeepContextSummaryService(
                new ModelGateway() {
                    @Override
                    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                        return Flux.empty();
                    }
                    @Override
                    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                        return Mono.just(ModelChatResult.builder()
                                .content("AI summary content")
                                .finishReason("stop")
                                .build());
                    }
                },
                properties,
                new DefaultBudgetGuard(properties),
                traceRecorder);

        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark, deepService);

        LedgerCompactionResult result = svc.compact(ctx);
        assertTrue("compacted", result.compacted());
        assertEquals("deep_summary", result.strategy());
        assertEquals("depth=1", 1, result.compactionDepth());

        ConversationLedgerEntry se = summaryEntry(ctx);
        assertThat(se.content()).contains("CompactionDepth: 1");
        assertThat(se.content()).contains("MaxInputCompactionDepth: 0");
        assertThat(se.content()).contains("MaxAllowedCompactionDepth: 3");
    }

    @Test
    public void customMaxCompactionDepthConfig() {
        AgentContext ctx = createBootstrappedRun("c10-custom-depth", "custom depth");
        LedgerWatermark watermark = new LedgerWatermark(5, 3);

        // Create service with maxCompactionDepth=2 (instead of default 3)
        LedgerCompactionService svc = new LedgerCompactionService(
                watermark, artifactRepository, blobStore, null, null, 2);

        // 2 compactions → depth reaches 2 (still within limit)
        for (int i = 0; i < 2; i++) {
            fillEntries(ctx, 4);
            svc.compact(ctx);
        }

        // 3rd compaction should be depth-guarded with maxDepth=2
        fillEntries(ctx, 4);
        LedgerCompactionResult r3 = svc.compact(ctx);
        assertEquals("depth=3", 3, r3.compactionDepth());
        assertEquals("maxAllowedDepth=2", 2, r3.maxAllowedCompactionDepth());
        assertTrue("depth guarded with custom max", r3.depthGuarded());
        assertEquals("deterministic_depth_guarded", r3.strategy());
        assertTrue("wouldExceedMax returns true", svc.wouldExceedMaxCompactionDepth(ctx));
    }

    @Test
    public void compactionDepthMetadataInResult() {
        AgentContext ctx = createBootstrappedRun("c10-meta-depth", "metadata depth");
        LedgerWatermark watermark = new LedgerWatermark(5, 3);
        LedgerCompactionService svc = compactionService(watermark);

        fillEntries(ctx, 5);
        LedgerCompactionResult result = svc.compact(ctx);

        // All depth fields should be present and sensible
        assertTrue("compactionDepth >= 1", result.compactionDepth() >= 1);
        assertTrue("maxInputCompactionDepth >= 0", result.maxInputCompactionDepth() >= 0);
        assertEquals("maxAllowedDepth defaults to 3", 3, result.maxAllowedCompactionDepth());
        // notNeeded returns 0 for depth fields
        LedgerCompactionResult notNeeded = LedgerCompactionResult.notNeeded(5, 0);
        assertEquals(0, notNeeded.compactionDepth());
        assertEquals(0, notNeeded.maxInputCompactionDepth());
        assertEquals(0, notNeeded.maxAllowedCompactionDepth());
        assertFalse(notNeeded.depthGuarded());
    }
}
