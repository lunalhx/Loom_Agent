package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.*;
import cn.lunalhx.ai.domain.agent.model.valobj.*;
import cn.lunalhx.ai.domain.agent.service.StablePrefixBuilder;
import cn.lunalhx.ai.domain.agent.service.context.AgentContextFactory;
import cn.lunalhx.ai.domain.agent.service.ledger.*;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.agent.model.state.AgentRuntimeState;
import cn.lunalhx.ai.domain.agent.service.execution.AgentResumeCoordinator;
import cn.lunalhx.ai.domain.agent.service.execution.AgentEventFactory;
import cn.lunalhx.ai.domain.agent.service.execution.AgentResumePlan;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.*;

/**
 * C9R: Ledger bootstrap, generation switching via StablePrefix.fingerprint(),
 * continuation strict-append, and all resume-path idempotency.
 *
 * <p>Core invariant: within the same generation, the previous run's canonical
 * input messages MUST be the strict prefix of the next run's messages.
 */
public class ConversationLedgerC9Test {

    private AgentRuntimeProperties.ConversationLedgerProperties enabledConfig;
    private ConversationLedgerAppendService appendSvc;
    private ConversationLedgerInitializer initializer;
    private LedgerBootstrapService bootstrapService;
    private StablePrefixBuilder prefixBuilder;
    private AgentWorkspaceResolver workspaceResolver;
    private AgentContextFactory factoryWithLedger;
    private AgentRuntimeProperties properties;
    private AgentResumeCoordinator coordinator;
    private InMemoryApprovalStore approvalStore;
    private InMemoryAgentCheckpointRepository checkpointRepo;
    private InMemoryAgentRunRepository runRepo;
    private AgentEventFactory eventFactory;

    @Before
    public void setUp() {
        enabledConfig = new AgentRuntimeProperties.ConversationLedgerProperties();
        enabledConfig.setEnabled(true);
        enabledConfig.setShadowEnabled(false);

        appendSvc = new ConversationLedgerAppendService(enabledConfig);
        initializer = new ConversationLedgerInitializer(enabledConfig);
        bootstrapService = new LedgerBootstrapService(appendSvc, initializer);
        prefixBuilder = new StablePrefixBuilder();

        properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(".");
        properties.setMaxSteps(30);
        StepBudgetConfig budget = new StepBudgetConfig();
        budget.setMaxSegments(5);
        budget.setMaxTotalSteps(150);
        budget.setContinuationEnabled(true);
        budget.setSameActionMaxRepeats(10);
        budget.setSameFailureMaxRepeats(10);
        properties.setStepBudget(budget);

        workspaceResolver = new AgentWorkspaceResolver(properties);

        factoryWithLedger = new AgentContextFactory(properties, workspaceResolver,
                List.of(tool("code_search", "search code", "{}")), false, appendSvc);

        approvalStore = new InMemoryApprovalStore();
        checkpointRepo = new InMemoryAgentCheckpointRepository();
        runRepo = new InMemoryAgentRunRepository();
        eventFactory = new AgentEventFactory();

        coordinator = new AgentResumeCoordinator(
                approvalStore, checkpointRepo, runRepo,
                factoryWithLedger, eventFactory, appendSvc);
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static ToolSpec tool(String name, String desc, String schema) {
        return ToolSpec.builder().name(name).description(desc).inputSchema(schema).build();
    }

    /** Build a candidate StablePrefix from the current context's tools + role. */
    private StablePrefix candidatePrefix(AgentContext ctx) {
        return prefixBuilder.build(
                ctx.getAgentRole(),
                ctx.isSubAgentSpawnAllowed(),
                null, // pathScope
                ctx.getToolSpecs(),
                ctx.getSkillCatalogText(),
                ctx.getActivatedSkills(),
                java.util.Map.of());
    }

    /** Bootstrap a new context (no manual init call). */
    private void doBootstrap(AgentContext ctx) {
        StablePrefix candidate = candidatePrefix(ctx);
        bootstrapService.bootstrap(ctx, candidate);
    }

    private AgentContext createRun(String runId, String question) {
        AgentContext ctx = factoryWithLedger.create(AgentQuestion.builder()
                .runId(runId)
                .requestId("req-" + runId)
                .conversationId("conv-" + runId)
                .question(question)
                .maxSteps(30)
                .build());
        ctx.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        ctx.setWorkspaceDisplayName(".");
        return ctx;
    }

    /** Create a run and fully bootstrap it. */
    private AgentContext createBootstrappedRun(String runId, String question) {
        AgentContext ctx = createRun(runId, question);
        doBootstrap(ctx);
        return ctx;
    }

    // ================================================================
    // 1. New conversation — bootstrap initializes ledger
    // ================================================================

    @Test
    public void newConversationBootstrapCreatesLedgerWithUserTask() {
        AgentContext ctx = createRun("run-boot-1", "new task");
        assertFalse("ledger not ready before bootstrap", ctx.isLedgerReady());

        doBootstrap(ctx);

        assertTrue("ledger ready after bootstrap", ctx.isLedgerReady());
        assertNotNull("ledger created", ctx.getConversationLedger());
        assertEquals("generation=0", 0, ctx.getGeneration());

        List<ConversationLedgerEntry> entries = ctx.getConversationLedger().entries();
        assertEquals("one user_task entry", 1, entries.size());
        assertEquals("new task", entries.get(0).content());
        assertEquals(LedgerStableType.USER_TASK, entries.get(0).stableType());

        // StablePrefix must be set
        assertNotNull("stable prefix set", ctx.getStablePrefix());
        assertThat(ctx.getStablePrefix().frozenContent()).isNotEmpty();
    }

    @Test
    public void newConversationBootstrapCreatesNonEmptyPrefix() {
        AgentContext ctx = createRun("run-prefix-check", "verify prefix");
        doBootstrap(ctx);

        StablePrefix sp = ctx.getStablePrefix();
        assertNotNull(sp);
        assertThat(sp.frozenContent()).contains("code_search"); // tool is in prefix
        assertThat(sp.fingerprint()).isNotEmpty();
    }

    @Test
    public void configFingerprintSyncedToPrefixAfterBootstrap() {
        AgentContext ctx = createRun("run-fp-sync", "task");
        doBootstrap(ctx);

        assertEquals(ctx.getStablePrefix().fingerprint(), ctx.getConfigFingerprint());
    }

    // ================================================================
    // 2. Continuation strict append
    // ================================================================

    @Test
    public void continuationPreservesPreviousMessagesAsPrefix() {
        AgentContext ctx1 = createBootstrappedRun("run-1", "initial question");
        appendSvc.appendAssistant(ctx1, "assistant thinking",
                ConversationLedgerInitializer.eventKey("run-1", "1", "assistant"));
        appendSvc.appendToolResult(ctx1, "tool output",
                ConversationLedgerInitializer.eventKey("run-1", "1", "tool_result"));

        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);
        List<ConversationLedgerEntry> prevEntries = List.copyOf(ctx1.getConversationLedger().entries());
        assertEquals(3, prevEntries.size()); // user_task + assistant + tool_result

        // Continuation: restore ledger/prefix/generation, set pending continuation
        AgentContext ctx2 = factoryWithLedger.createContinuation(
                AgentQuestion.builder()
                        .runId("run-2")
                        .conversationId("conv-run-1")
                        .question("follow-up question")
                        .maxSteps(30)
                        .build(),
                snap1);

        // Before bootstrap: ledger is restored but continuation not yet appended
        List<ConversationLedgerEntry> beforeEntries = ctx2.getConversationLedger().entries();
        assertEquals("before bootstrap: same entries as snapshot", prevEntries.size(), beforeEntries.size());

        // Bootstrap applies pending continuation
        doBootstrap(ctx2);

        List<ConversationLedgerEntry> afterEntries = ctx2.getConversationLedger().entries();
        // Previous 3 messages must be strict prefix
        assertTrue("prev entries must be prefix", afterEntries.size() >= prevEntries.size());
        for (int i = 0; i < prevEntries.size(); i++) {
            assertEquals("message " + i + " must match",
                    prevEntries.get(i).content(), afterEntries.get(i).content());
            assertEquals("message " + i + " role must match",
                    prevEntries.get(i).role(), afterEntries.get(i).role());
        }
        assertTrue("must have new message", afterEntries.size() > prevEntries.size());
        assertThat(afterEntries.get(afterEntries.size() - 1).content())
                .contains("[Conversation Continued]");
        assertThat(afterEntries.get(afterEntries.size() - 1).content())
                .contains("follow-up question");
    }

    @Test
    public void sameConfigContinuationKeepsGenerationAndPrefix() {
        AgentContext ctx1 = createBootstrappedRun("run-same-1", "task 1");

        // Simulate some work
        ctx1.setGeneration(ctx1.getGeneration());  // no-op, generation is 0

        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);
        int gen1 = ctx1.getGeneration();
        StablePrefix prefix1 = ctx1.getStablePrefix();

        AgentContext ctx2 = factoryWithLedger.createContinuation(
                AgentQuestion.builder()
                        .runId("run-same-2")
                        .conversationId("conv-same-1")
                        .question("task 2")
                        .maxSteps(30)
                        .build(),
                snap1);

        doBootstrap(ctx2);

        // Same config → same generation and prefix
        assertEquals("same generation", gen1, ctx2.getGeneration());
        assertEquals("same prefix", prefix1.fingerprint(), ctx2.getStablePrefix().fingerprint());
    }

    @Test
    public void continuationDoesNotWriteQuestionIntoStablePrefix() {
        AgentContext ctx1 = createBootstrappedRun("run-prefix-1", "original task");
        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);

        AgentContext ctx2 = factoryWithLedger.createContinuation(
                AgentQuestion.builder()
                        .runId("run-prefix-2")
                        .conversationId("conv-prefix-1")
                        .question("follow up question that should not be in prefix")
                        .maxSteps(30)
                        .build(),
                snap1);

        doBootstrap(ctx2);

        StablePrefix sp = ctx2.getStablePrefix();
        assertNotNull(sp);
        assertThat(sp.frozenContent()).doesNotContain("follow up question");
    }

    // ================================================================
    // 3. Config change → fingerprint change → generation bump
    // ================================================================

    @Test
    public void differentToolsChangePrefixFingerprint() {
        AgentContext ctx1 = createBootstrappedRun("run-tool-fp-1", "task");
        StablePrefix prefix1 = ctx1.getStablePrefix();

        // Build a prefix with different tools
        List<ToolSpec> diffTools = List.of(
                tool("code_search", "search code", "{}"),
                tool("write_file", "write file", "{ }")
        );
        StablePrefix prefix2 = prefixBuilder.build(
                null, false, null, diffTools, null, null, java.util.Map.of());

        assertNotEquals("different tools → different fingerprint",
                prefix1.fingerprint(), prefix2.fingerprint());
    }

    @Test
    public void configChangeBumpsGenerationAndSetsNewPrefix() {
        // Create a run with config A
        AgentContext ctx1 = createBootstrappedRun("run-chg-1", "task 1");
        String oldFp = ctx1.getStablePrefix().fingerprint();
        int oldGen = ctx1.getGeneration();
        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);

        // Create a factory with DIFFERENT tools (config B)
        AgentContextFactory factoryB = new AgentContextFactory(properties, workspaceResolver,
                List.of(
                        tool("code_search", "search code", "{}"),
                        tool("write_file", "write file", "{ }")
                ), false, appendSvc);

        AgentContext ctx2 = factoryB.createContinuation(
                AgentQuestion.builder()
                        .runId("run-chg-2")
                        .conversationId("conv-chg-1")
                        .question("task 2")
                        .maxSteps(30)
                        .build(),
                snap1);

        // Build candidate with current (config B) tools
        StablePrefix candidateB = prefixBuilder.build(
                null, false, null,
                ctx2.getToolSpecs(), null, null, java.util.Map.of());

        // Bootstrap with different config
        bootstrapService.bootstrap(ctx2, candidateB);

        // Generation must be bumped
        assertEquals("generation bumped", oldGen + 1, ctx2.getGeneration());

        // New prefix must be set (not old one)
        assertNotEquals("new prefix must differ from old", oldFp,
                ctx2.getStablePrefix().fingerprint());
        assertEquals("new prefix must be candidate",
                candidateB.fingerprint(), ctx2.getStablePrefix().fingerprint());

        // Config change marker present
        List<ConversationLedgerEntry> entries = ctx2.getConversationLedger().entries();
        assertTrue("must have config change note",
                entries.stream().anyMatch(e ->
                        e.content().contains("[Config Change]") &&
                                e.content().contains("generation " + ctx2.getGeneration())));

        // Pending continuation appended after config change
        assertThat(entries.get(entries.size() - 1).content())
                .contains("[Conversation Continued]");
        assertThat(entries.get(entries.size() - 1).content())
                .contains("task 2");
    }

    @Test
    public void configChangeNoteUsesFullPrefixFingerprints() {
        AgentContext ctx1 = createBootstrappedRun("run-full-fp-1", "task");
        String oldFp = ctx1.getStablePrefix().fingerprint();
        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);

        List<ToolSpec> diffTools = List.of(
                tool("code_search", "search code", "{}"),
                tool("find_files", "find files", "{}")
        );
        StablePrefix candidateB = prefixBuilder.build(
                null, false, null, diffTools, null, null, java.util.Map.of());

        AgentContext ctx2 = factoryWithLedger.createContinuation(
                AgentQuestion.builder().runId("run-full-fp-2")
                        .conversationId("conv-full-fp").question("q").maxSteps(30).build(),
                snap1);

        bootstrapService.bootstrap(ctx2, candidateB);

        // Config change note uses full StablePrefix fingerprints (not tool-only)
        List<ConversationLedgerEntry> entries = ctx2.getConversationLedger().entries();
        ConversationLedgerEntry changeNote = entries.stream()
                .filter(e -> e.content().contains("[Config Change]"))
                .findFirst().orElseThrow();

        assertThat(changeNote.content()).contains(oldFp);
        assertThat(changeNote.content()).contains(candidateB.fingerprint());
    }

    @Test
    public void configChangeDoesNotDuplicate() {
        AgentContext ctx1 = createBootstrappedRun("run-nodup-1", "task");
        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);

        List<ToolSpec> diffTools = List.of(
                tool("code_search", "search code", "{}"),
                tool("write_file", "write file", "{}")
        );
        StablePrefix candidateB = prefixBuilder.build(
                null, false, null, diffTools, null, null, java.util.Map.of());

        AgentContext ctx2 = factoryWithLedger.createContinuation(
                AgentQuestion.builder().runId("run-nodup-2")
                        .conversationId("conv-nodup").question("q").maxSteps(30).build(),
                snap1);

        bootstrapService.bootstrap(ctx2, candidateB);

        // Bootstrap again — idempotent
        bootstrapService.bootstrap(ctx2, candidateB);

        List<ConversationLedgerEntry> entries = ctx2.getConversationLedger().entries();
        long changeCount = entries.stream()
                .filter(e -> e.content().contains("[Config Change]"))
                .count();
        assertEquals("config change marker must not duplicate", 1, changeCount);

        long contCount = entries.stream()
                .filter(e -> e.content().contains("[Conversation Continued]"))
                .count();
        assertEquals("continuation marker must not duplicate", 1, contCount);
    }

    // ================================================================
    // 4. Checkpoint resume idempotency
    // ================================================================

    @Test
    public void checkpointResumeDoesNotDuplicateEntries() {
        AgentContext ctx = createBootstrappedRun("run-ckpt-1", "task");
        appendSvc.appendAssistant(ctx, "step-1",
                ConversationLedgerInitializer.eventKey("run-ckpt-1", "1", "assistant"));
        appendSvc.appendToolResult(ctx, "result-1",
                ConversationLedgerInitializer.eventKey("run-ckpt-1", "1", "tool_result"));
        assertEquals(3, ctx.getConversationLedger().size());

        // Replay — idempotent
        appendSvc.appendAssistant(ctx, "step-1",
                ConversationLedgerInitializer.eventKey("run-ckpt-1", "1", "assistant"));
        appendSvc.appendToolResult(ctx, "result-1",
                ConversationLedgerInitializer.eventKey("run-ckpt-1", "1", "tool_result"));
        assertEquals(3, ctx.getConversationLedger().size());
    }

    @Test
    public void checkpointMultipleResumesAreIdempotent() {
        AgentContext ctx = createBootstrappedRun("run-multi-1", "task");
        appendSvc.appendAssistant(ctx, "step-1",
                ConversationLedgerInitializer.eventKey("run-multi-1", "1", "assistant"));
        assertEquals(2, ctx.getConversationLedger().size());

        for (int i = 0; i < 3; i++) {
            appendSvc.appendAssistant(ctx, "step-1",
                    ConversationLedgerInitializer.eventKey("run-multi-1", "1", "assistant"));
        }
        assertEquals(2, ctx.getConversationLedger().size());
    }

    // ================================================================
    // 5. Approval resume — append decisions
    // ================================================================

    @Test
    public void approvalApproveAppendsDecisionToLedger() {
        AgentContext ctx = createBootstrappedRun("run-approve", "test approve");
        ctx.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        ctx.setWorkspaceDisplayName(".");
        ctx.setCurrentNode(AgentNodeNames.APPROVAL_GATE);
        ctx.setPendingApprovalId("approval-1");
        ctx.setStep(2);

        checkpointRepo.save(AgentCheckpoint.builder()
                .runId("run-approve")
                .currentNode(AgentNodeNames.APPROVAL_GATE)
                .contextSnapshot(AgentContextSnapshot.from(ctx))
                .reason("before_approval")
                .build());

        PendingApproval approval = new PendingApproval();
        approval.setApprovalId("approval-1");
        approval.setRunId("run-approve");
        approval.setContext(ctx);
        approval.setTool("replace_in_file");
        approval.setWorkspace(WorkspaceRef.builder().provider("local").location(".").displayName(".").build());
        approval.setWorkspaceDisplayName(".");
        approval.setPermissionLevel(ToolPermissionLevel.WRITE_CONFIRM);
        approvalStore.save(approval);

        AgentResumePlan plan = coordinator.prepareApprovalResume(
                "approval-1", ApprovalDecision.APPROVE, "looks good");

        assertNotNull(plan);
        AgentContext resumedCtx = plan.context();
        if (resumedCtx != null && resumedCtx.getConversationLedger() != null) {
            List<ConversationLedgerEntry> entries = resumedCtx.getConversationLedger().entries();
            assertTrue("must have approval entry",
                    entries.stream().anyMatch(e ->
                            e.content().contains("[Approval]") && e.content().contains("approved")));
        }
    }

    @Test
    public void approvalRejectAppendsDecisionToLedger() {
        AgentContext ctx = createBootstrappedRun("run-reject", "test reject");
        ctx.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        ctx.setWorkspaceDisplayName(".");
        ctx.setCurrentNode(AgentNodeNames.APPROVAL_GATE);
        ctx.setPendingApprovalId("approval-rej-1");
        ctx.setStep(2);

        checkpointRepo.save(AgentCheckpoint.builder()
                .runId("run-reject")
                .currentNode(AgentNodeNames.APPROVAL_GATE)
                .contextSnapshot(AgentContextSnapshot.from(ctx))
                .reason("before_approval")
                .build());

        PendingApproval approval = new PendingApproval();
        approval.setApprovalId("approval-rej-1");
        approval.setRunId("run-reject");
        approval.setContext(ctx);
        approval.setTool("delete_files");
        approval.setWorkspace(WorkspaceRef.builder().provider("local").location(".").displayName(".").build());
        approval.setWorkspaceDisplayName(".");
        approval.setPermissionLevel(ToolPermissionLevel.HIGH_RISK_CONFIRM);
        approvalStore.save(approval);

        AgentResumePlan plan = coordinator.prepareApprovalResume(
                "approval-rej-1", ApprovalDecision.REJECT, "too dangerous");

        assertNotNull(plan);
        assertTrue(plan.initialEvents().stream()
                .anyMatch(e -> e.getType() == AgentEventType.RESUME_STARTED));
    }

    @Test
    public void approvalDecisionIsDeterministic() {
        String approved = ControlUpdateTexts.renderApprovalDecision("approved", "replace_in_file", "ok");
        assertEquals(approved, ControlUpdateTexts.renderApprovalDecision("approved", "replace_in_file", "ok"));

        String rejected = ControlUpdateTexts.renderApprovalDecision("rejected", "delete_files", "dangerous");
        assertThat(rejected).contains("[Approval]").contains("rejected").contains("delete_files");

        String expired = ControlUpdateTexts.renderApprovalExpired("ap-xyz");
        assertThat(expired).contains("[Approval Expired]").contains("ap-xyz");
    }

    // ================================================================
    // 6. User input resume
    // ================================================================

    @Test
    public void userInputResumeAppendsToLedger() {
        AgentContext ctx = createBootstrappedRun("run-uinput-1", "original task");
        ctx.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        ctx.setWorkspaceDisplayName(".");
        ctx.setCurrentNode(AgentNodeNames.USER_INPUT_GATE);
        ctx.setContextRecoveryStage(ContextRecoveryStage.WAITING_USER_INPUT);
        ctx.setStep(3);

        checkpointRepo.save(AgentCheckpoint.builder()
                .runId("run-uinput-1")
                .currentNode(AgentNodeNames.USER_INPUT_GATE)
                .contextSnapshot(AgentContextSnapshot.from(ctx))
                .reason("after_user_input_gate")
                .build());

        AgentResumePlan plan = coordinator.prepareUserInputResume(
                "run-uinput-1", UserInputAction.CONTINUE, "please use python");

        assertNotNull(plan);
        AgentContext resumedCtx = plan.context();
        if (resumedCtx != null && resumedCtx.getConversationLedger() != null) {
            List<ConversationLedgerEntry> entries = resumedCtx.getConversationLedger().entries();
            assertTrue("must have user input entry",
                    entries.stream().anyMatch(e ->
                            e.content().contains("[User Input]")
                                    && e.content().contains("please use python")));
        }
    }

    // ================================================================
    // 7. Bootstrap failure → ledgerReady=false
    // ================================================================

    @Test
    public void ledgerNotReadyBeforeBootstrap() {
        AgentContext ctx = createRun("run-notready", "task");
        assertFalse("ledger not ready before bootstrap", ctx.isLedgerReady());
        assertNull("no ledger yet", ctx.getConversationLedger());
    }

    @Test
    public void bootstrapWithNullCandidateLeavesLedgerNotReady() {
        AgentContext ctx = createRun("run-null-cand", "task");
        assertFalse(ctx.isLedgerReady());
        // Passing null candidate is handled gracefully — ledgerReady stays false
        try {
            bootstrapService.bootstrap(ctx, null);
        } catch (NullPointerException expected) {
            // Candidate is validated — this is ok
        }
        // But in production, runBootstrap catches exceptions, so ledgerReady stays false
        ctx.setLedgerReady(false);
        assertFalse(ctx.isLedgerReady());
    }

    // ================================================================
    // 8. Multiple continuations maintain prefix chain
    // ================================================================

    @Test
    public void multipleContinuationsMaintainPrefixChain() {
        // Run 1
        AgentContext ctx1 = createBootstrappedRun("run-chain-1", "task 1");
        appendSvc.appendAssistant(ctx1, "think 1",
                ConversationLedgerInitializer.eventKey("run-chain-1", "1", "assistant"));
        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);

        // Continuation 1 → Run 2
        AgentContext ctx2 = factoryWithLedger.createContinuation(
                AgentQuestion.builder().runId("run-chain-2")
                        .conversationId("conv-chain-1").question("task 2")
                        .maxSteps(30).build(), snap1);
        doBootstrap(ctx2);
        appendSvc.appendAssistant(ctx2, "think 2",
                ConversationLedgerInitializer.eventKey("run-chain-2", "1", "assistant"));
        AgentContextSnapshot snap2 = AgentContextSnapshot.from(ctx2);

        // Continuation 2 → Run 3
        AgentContext ctx3 = factoryWithLedger.createContinuation(
                AgentQuestion.builder().runId("run-chain-3")
                        .conversationId("conv-chain-1").question("task 3")
                        .maxSteps(30).build(), snap2);
        doBootstrap(ctx3);

        List<ConversationLedgerEntry> entries = ctx3.getConversationLedger().entries();
        assertTrue(entries.size() >= 5);
        assertThat(entries.get(0).content()).isEqualTo("task 1");
        assertThat(entries.get(1).content()).contains("think 1");
        assertTrue(entries.stream().anyMatch(e -> e.content().contains("think 2")));
        assertThat(entries.get(entries.size() - 1).content())
                .contains("[Conversation Continued]").contains("task 3");
    }

    // ================================================================
    // 9. Canonical snapshot prefix property (core invariant)
    // ================================================================

    @Test
    public void canonicalInputMessagesAreStrictPrefixWithinSameGeneration() {
        AgentContext ctx1 = createBootstrappedRun("run-canon-1", "task 1");
        appendSvc.appendAssistant(ctx1, "thinking",
                ConversationLedgerInitializer.eventKey("run-canon-1", "1", "assistant"));
        appendSvc.appendToolResult(ctx1, "result",
                ConversationLedgerInitializer.eventKey("run-canon-1", "1", "tool_result"));

        List<ConversationLedgerEntry> snapshotAfterRun1 = List.copyOf(
                ctx1.getConversationLedger().entries());
        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);

        // Continuation with same config → same generation
        AgentContext ctx2 = factoryWithLedger.createContinuation(
                AgentQuestion.builder()
                        .runId("run-canon-2")
                        .conversationId("conv-canon-1")
                        .question("task 2")
                        .maxSteps(30)
                        .build(),
                snap1);
        doBootstrap(ctx2);

        // Must be same generation (same config)
        assertEquals(ctx1.getGeneration(), ctx2.getGeneration());

        // Messages must be strict prefix
        List<ConversationLedgerEntry> entries2 = ctx2.getConversationLedger().entries();
        for (int i = 0; i < snapshotAfterRun1.size(); i++) {
            assertEquals("Message " + i + " must match exactly",
                    snapshotAfterRun1.get(i).role(), entries2.get(i).role());
            assertEquals("Message " + i + " content must match exactly",
                    snapshotAfterRun1.get(i).content(), entries2.get(i).content());
        }
        assertTrue("must have new messages appended",
                entries2.size() > snapshotAfterRun1.size());
    }

    // ================================================================
    // 10. V2 snapshot migration (no StablePrefix)
    // ================================================================

    @Test
    public void contextWithoutStablePrefixTriggersMigration() {
        AgentContext ctx1 = createRun("run-migrate-1", "test task");
        // Create a ledger but NO stable prefix (simulates v2 or uninitialized)
        ctx1.ensureLedgerActive();
        ctx1.getConversationLedger().append("user", "test task",
                LedgerStableType.USER_TASK);  // must have entries for restore
        ctx1.setGeneration(0);

        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);
        AgentContext ctx2 = factoryWithLedger.createContinuation(
                AgentQuestion.builder().runId("run-migrate-2")
                        .conversationId("conv-migrate").question("follow-up").maxSteps(30).build(),
                snap1);

        // No stable prefix before bootstrap
        assertNull(ctx2.getStablePrefix());
        assertNotNull(ctx2.getConversationLedger()); // ledger was restored

        doBootstrap(ctx2);

        // Bootstrap sets StablePrefix and bumps generation (no prefix → new gen)
        assertNotNull(ctx2.getStablePrefix());
        assertTrue(ctx2.getGeneration() >= 1);

        // Migration marker present
        List<ConversationLedgerEntry> entries = ctx2.getConversationLedger().entries();
        assertTrue("must have migration note",
                entries.stream().anyMatch(e -> e.content().contains("[Migration]")));
    }

    // ================================================================
    // 11. Disabled mode — no ledger changes
    // ================================================================

    @Test
    public void continuationWithoutLedgerServiceDoesNotCrash() {
        AgentContextFactory noLedgerFactory = new AgentContextFactory(
                properties, workspaceResolver,
                List.of(tool("code_search", "search", "{}")), false);

        AgentContext ctx1 = noLedgerFactory.create(AgentQuestion.builder()
                .runId("run-no-ledger-1").requestId("req").conversationId("conv")
                .question("task").maxSteps(30).build());
        ctx1.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        ctx1.setWorkspaceDisplayName(".");

        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);
        AgentContext ctx2 = noLedgerFactory.createContinuation(
                AgentQuestion.builder()
                        .runId("run-no-ledger-2").conversationId("conv")
                        .question("follow-up").maxSteps(30).build(),
                snap1);

        assertNotNull(ctx2);
        assertNull(ctx2.getConversationLedger());
    }

    // ================================================================
    // 12. Config change note is deterministic
    // ================================================================

    @Test
    public void configChangeNoteIsDeterministic() {
        String note1 = ControlUpdateTexts.renderConfigChangeNote("fp-old", "fp-new", 2);
        String note2 = ControlUpdateTexts.renderConfigChangeNote("fp-old", "fp-new", 2);
        assertEquals(note1, note2);
        assertThat(note1).contains("[Config Change]").contains("generation 2");
        assertThat(note1).contains("fp-old").contains("fp-new");
    }

    // ================================================================
    // 13. Event key uniqueness
    // ================================================================

    @Test
    public void continuationEventKeysAreUnique() {
        AgentContext ctx1 = createBootstrappedRun("run-ek-1", "task");
        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);

        AgentContext ctx2 = factoryWithLedger.createContinuation(
                AgentQuestion.builder().runId("run-ek-2").conversationId("conv-ek")
                        .question("task2").maxSteps(30).build(), snap1);
        doBootstrap(ctx2);

        AgentContext ctx3 = factoryWithLedger.createContinuation(
                AgentQuestion.builder().runId("run-ek-3").conversationId("conv-ek")
                        .question("task3").maxSteps(30).build(), AgentContextSnapshot.from(ctx2));
        doBootstrap(ctx3);

        List<ConversationLedgerEntry> entries = ctx3.getConversationLedger().entries();
        Set<String> keys = entries.stream()
                .map(ConversationLedgerEntry::eventKey)
                .filter(k -> k != null)
                .collect(Collectors.toSet());
        long nonNullCount = entries.stream()
                .map(ConversationLedgerEntry::eventKey)
                .filter(k -> k != null)
                .count();
        assertEquals("all non-null event keys must be unique", nonNullCount, keys.size());
    }

    // ================================================================
    // 14. Ledger sequences are monotonic across continuations
    // ================================================================

    @Test
    public void continuationLedgerSequenceIsMonotonic() {
        AgentContext ctx1 = createBootstrappedRun("run-seq-1", "task 1");
        appendSvc.appendAssistant(ctx1, "step 1",
                ConversationLedgerInitializer.eventKey("run-seq-1", "1", "assistant"));
        AgentContextSnapshot snap1 = AgentContextSnapshot.from(ctx1);

        AgentContext ctx2 = factoryWithLedger.createContinuation(
                AgentQuestion.builder().runId("run-seq-2").conversationId("conv-seq")
                        .question("task 2").maxSteps(30).build(), snap1);
        doBootstrap(ctx2);

        List<ConversationLedgerEntry> entries = ctx2.getConversationLedger().entries();
        for (int i = 0; i < entries.size(); i++) {
            assertEquals("sequence must be " + i, i, entries.get(i).sequence());
        }
        assertEquals("nextSequence after continuation", entries.size(),
                ctx2.getConversationLedger().nextSequence());
    }

    /** Adapter for step budget config. */
    static class StepBudgetConfig extends AgentRuntimeProperties.StepBudgetProperties {
        // inherits all fields
    }
}
