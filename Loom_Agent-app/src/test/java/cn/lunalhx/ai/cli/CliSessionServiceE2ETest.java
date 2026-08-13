package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.DelegateRunner;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateProvenance;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateRequest;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import cn.lunalhx.ai.domain.agent.model.entity.Plan;
import cn.lunalhx.ai.domain.agent.model.entity.PlanRevision;
import cn.lunalhx.ai.domain.agent.model.entity.PlanSubmission;
import cn.lunalhx.ai.domain.agent.model.entity.PlanSubmissionTransaction;
import cn.lunalhx.ai.domain.agent.model.entity.ResumeResult;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.test.FakeModelGateway;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileAttemptLeaseRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AttemptLease;
import cn.lunalhx.ai.infrastructure.store.FileConversationHistoryRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.infrastructure.loom.DelegateTool;
import cn.lunalhx.ai.infrastructure.loom.ReadFileTool;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.*;

/**
 * CLI-level offline E2E: runs the real loop through the CliSessionService
 * against file-backed stores with a deterministic fake gateway.
 */
public class CliSessionServiceE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private ModelGateway finalAnswerGateway(String answer) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private CliSessionService.CliOptions options(Path workspace, ModelGateway gateway) {
        CliSessionService.CliOptions o = new CliSessionService.CliOptions();
        o.provider = "deepseek";
        o.model = "deepseek-v4-flash";
        o.baseUrl = "http://unused";
        o.apiKey = "";
        o.workspaceRoot = workspace.toString();
        o.approvalPolicy = "never";
        o.maxSteps = 6;
        o.maxNewTokens = 512;
        o.temperature = 0.2;
        o.topP = 0.9;
        o.timeoutSeconds = 30;
        o.modelGateway = gateway;
        return o;
    }

    private FileConversationHistoryRepository historyRepository(Path workspace) {
        return CliLoopTestFixture.historyRepository(workspace, mapper);
    }

    private CliSessionService service(Path workspace, ModelGateway gateway) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        ModelRuntimeProperties model = new ModelRuntimeProperties();
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent, java.util.List.of());
        return new CliSessionService(options(workspace, gateway), mapper, agent, model,
                sessions, runs, checkpoints, historyRepository(workspace), traces, loop);
    }

    // ---- first turn: answer, run artifacts, session persisted ----

    @Test
    public void firstTurnPersistsRunAndSession() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-first");
        CliSessionService session = service(workspace, finalAnswerGateway("done."));
        String answer = session.runTurn("hello");
        assertEquals("done.", answer);

        Optional<AgentRun> run = session.runRepository().findLatestRootByConversationId(session.sessionId());
        assertTrue(run.isPresent());
        assertEquals(AgentRun.CURRENT_SCHEMA_VERSION, (int) run.get().getSchemaVersion());
        assertEquals("COMPLETED", run.get().getStatus().name());
        assertEquals("FINAL_ANSWER_RETURNED", run.get().getStopReason());
        assertEquals(0, (int) run.get().getToolSteps());

        AgentSession persisted = session.sessionRepository().find(session.sessionId()).orElseThrow();
        assertEquals(AgentSession.CURRENT_SCHEMA_VERSION, (int) persisted.getSchemaVersion());
        assertNotNull(persisted.getWorkingMemory());

        // Session must not copy History or checkpoint
        String sessionJson = Files.readString(
                workspace.resolve(".loom-code").resolve("sessions").resolve(session.sessionId() + ".json"));
        assertFalse(sessionJson.contains("\"history\""));
        assertFalse(sessionJson.contains("\"checkpoint\""));
        assertFalse(sessionJson.contains("\"ledgerNextSequence\""));

        ConversationHistoryDocument history = historyRepository(workspace).find(session.sessionId()).orElseThrow();
        assertEquals(ConversationHistoryDocument.CURRENT_SCHEMA_VERSION, (int) history.getSchemaVersion());
        assertFalse(history.getEntries().isEmpty());

        AgentCheckpoint checkpoint = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(run.get().getRunId()).orElseThrow();
        AgentContextSnapshot snapshot = checkpoint.getContextSnapshot();
        assertEquals(AgentContextSnapshot.CURRENT_SCHEMA_VERSION, (int) snapshot.getSchemaVersion());
        assertNotNull(snapshot.getHistoryAnchor());
        assertEquals(history.getNextSequence(), snapshot.getHistoryAnchor().getNextSequence());
        String checkpointJson = Files.readString(
                workspace.resolve(".loom-code").resolve("checkpoints")
                        .resolve(run.get().getRunId())
                        .resolve(checkpoint.getVersion() + ".json"));
        assertFalse(checkpointJson.contains("\"ledgerEntries\""));
        session.close();
    }

    @Test
    public void approvedPlanSubmissionCreatesRevisionOneAndReadOnlyViews() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-submit");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService session = service(workspace, planSubmissionGateway(modelCalls));
        session.setCollaborationMode(CollaborationMode.PLAN);

        String answer = session.runTurn("research and submit the first plan");

        assertTrue(answer.contains("Plan submitted:"));
        assertEquals(1, modelCalls.get());
        AgentRun run = session.runRepository().findLatestRootByConversationId(session.sessionId())
                .orElseThrow();
        assertEquals("COMPLETED", run.getStatus().name());
        assertEquals("PLAN_SUBMITTED", run.getStopReason());
        assertEquals("PLAN", run.getRunModeSnapshot().name());
        assertEquals("NEW", run.getPlanTarget());
        assertEquals(0L, (long) run.getPlanStateVersion());

        AgentSession persisted = session.sessionRepository().find(session.sessionId()).orElseThrow();
        assertEquals(1L, persisted.getPlanStateVersion());
        assertEquals(1, persisted.getPlans().size());
        assertEquals(persisted.getCurrentPlanId(), persisted.getPlans().get(0).getPlanId());
        assertEquals(1, persisted.getPlans().get(0).currentRevision().getRevision().intValue());
        assertFalse(persisted.getPlans().get(0).currentRevision().getContentDigest().isBlank());
        assertTrue(historyRepository(workspace).find(session.sessionId()).orElseThrow().getEntries().stream()
                .anyMatch(entry -> entry.content().contains("research and submit the first plan")));

        ByteArrayOutputStream listOutput = new ByteArrayOutputStream();
        assertTrue(CliMain.handleControl(session, "/plan list",
                new PrintStream(listOutput, true, java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(listOutput.toString(java.nio.charset.StandardCharsets.UTF_8)
                .contains(persisted.getCurrentPlanId()));
        assertTrue(listOutput.toString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("freshness: fresh"));

        ByteArrayOutputStream showOutput = new ByteArrayOutputStream();
        assertTrue(CliMain.handleControl(session, "/plan show",
                new PrintStream(showOutput, true, java.nio.charset.StandardCharsets.UTF_8)));
        String shown = showOutput.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(shown.contains("revision: 1"));
        assertTrue(shown.contains("First plan"));
        assertEquals(1, modelCalls.get());
        session.close();
    }

    @Test
    public void buildRunDoesNotBindSelectedPlan() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-build-unbound-plan");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService session = service(workspace, planThenFinalGateway(modelCalls));
        session.setCollaborationMode(CollaborationMode.PLAN);
        session.runTurn("submit a plan");

        session.setCollaborationMode(CollaborationMode.BUILD);
        assertEquals("build answer", session.runTurn("continue without a Plan"));

        AgentRun buildRun = session.runRepository()
                .findLatestRootByConversationId(session.sessionId()).orElseThrow();
        assertEquals(CollaborationMode.BUILD, buildRun.getRunModeSnapshot());
        assertNull(buildRun.getPlanTarget());
        assertNull(buildRun.getPlanRevision());
        assertEquals(0L, (long) buildRun.getPlanStateVersion());
        assertEquals(2, modelCalls.get());
        session.close();
    }

    @Test
    public void planNewClearsSelectionKeepsHistoryAndTargetsNextRunAsNew() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-new");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService session = service(workspace, planSubmissionGateway(modelCalls));
        session.setCollaborationMode(CollaborationMode.PLAN);

        session.runTurn("submit the first plan");
        AgentSession first = session.sessionRepository().find(session.sessionId()).orElseThrow();
        String firstPlanId = first.getCurrentPlanId();
        long firstVersion = first.getPlanStateVersion();
        int runsBeforeControl = session.runRepository()
                .findByConversationId(session.sessionId()).size();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(CliMain.handleControl(session, "/plan new",
                new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)));

        AgentSession afterNew = session.sessionRepository().find(session.sessionId()).orElseThrow();
        assertEquals(1, modelCalls.get());
        assertEquals(runsBeforeControl, session.runRepository()
                .findByConversationId(session.sessionId()).size());
        assertNull(afterNew.getCurrentPlanId());
        assertEquals(firstVersion + 1, afterNew.getPlanStateVersion());
        assertEquals(1, afterNew.getPlans().size());
        assertEquals(firstPlanId, afterNew.getPlans().get(0).getPlanId());

        assertTrue(session.runTurn("submit an unrelated plan").contains("Plan submitted:"));
        AgentSession afterSecond = session.sessionRepository().find(session.sessionId()).orElseThrow();
        assertEquals(2, afterSecond.getPlans().size());
        assertNotEquals(firstPlanId, afterSecond.getCurrentPlanId());
        assertEquals("NEW", session.runRepository()
                .findLatestRootByConversationId(session.sessionId()).orElseThrow().getPlanTarget());
        assertEquals(2, modelCalls.get());
        session.close();
    }

    @Test
    public void planSelectChangesCurrentPlanWithoutStartingRun() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-select");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService session = service(workspace, sequencedPlanGateway(modelCalls,
                "First plan", "Second plan", "Revision two"));
        session.setCollaborationMode(CollaborationMode.PLAN);

        session.runTurn("submit first plan");
        String firstPlanId = session.sessionRepository().find(session.sessionId()).orElseThrow()
                .getCurrentPlanId();
        session.newPlan();
        session.runTurn("submit second plan");
        AgentSession beforeSelect = session.sessionRepository().find(session.sessionId()).orElseThrow();
        String secondPlanId = beforeSelect.getCurrentPlanId();
        long versionBeforeSelect = beforeSelect.getPlanStateVersion();
        int runsBeforeSelect = session.runRepository().findByConversationId(session.sessionId()).size();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(CliMain.handleControl(session, "/plan select " + firstPlanId,
                new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)));

        AgentSession afterSelect = session.sessionRepository().find(session.sessionId()).orElseThrow();
        assertEquals(firstPlanId, afterSelect.getCurrentPlanId());
        assertEquals(versionBeforeSelect + 1, afterSelect.getPlanStateVersion());
        assertEquals(2, afterSelect.getPlans().size());
        assertEquals("Second plan", afterSelect.getPlans().stream()
                .filter(plan -> secondPlanId.equals(plan.getPlanId()))
                .findFirst().orElseThrow().currentRevision().getTitle());
        assertEquals(runsBeforeSelect,
                session.runRepository().findByConversationId(session.sessionId()).size());
        assertEquals(2, modelCalls.get());
        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("plan selected: " + firstPlanId));
        session.close();
    }

    @Test
    public void selectedPlanSubmissionAppendsRevisionAndKeepsExactHeadHistory() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-revision");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService session = service(workspace, sequencedPlanGateway(modelCalls,
                "First plan", "Second plan", "Revision two"));
        session.setCollaborationMode(CollaborationMode.PLAN);

        session.runTurn("submit first plan");
        String firstPlanId = session.sessionRepository().find(session.sessionId()).orElseThrow()
                .getCurrentPlanId();
        session.newPlan();
        session.runTurn("submit second plan");
        session.selectPlan(firstPlanId);
        AgentSession selected = session.sessionRepository().find(session.sessionId()).orElseThrow();
        long selectedVersion = selected.getPlanStateVersion();
        int runsBeforeRevision = session.runRepository().findByConversationId(session.sessionId()).size();

        String revisionAnswer = session.runTurn("revise the first plan");
        assertTrue("unexpected revision answer: " + revisionAnswer,
                revisionAnswer.contains("revision 2"));

        AgentSession persisted = session.sessionRepository().find(session.sessionId()).orElseThrow();
        Plan first = persisted.getPlans().stream()
                .filter(plan -> firstPlanId.equals(plan.getPlanId())).findFirst().orElseThrow();
        assertEquals(firstPlanId, persisted.getCurrentPlanId());
        assertEquals(selectedVersion + 1, persisted.getPlanStateVersion());
        assertEquals(2, first.getRevisions().size());
        assertEquals(Integer.valueOf(1), first.getRevisions().get(0).getRevision());
        assertEquals("First plan", first.getRevisions().get(0).getTitle());
        assertEquals(Integer.valueOf(2), first.currentRevision().getRevision());
        assertEquals("Revision two", first.currentRevision().getTitle());
        assertEquals("Second plan", persisted.getPlans().stream()
                .filter(plan -> !firstPlanId.equals(plan.getPlanId()))
                .findFirst().orElseThrow().currentRevision().getTitle());

        AgentRun revisionRun = session.runRepository()
                .findLatestRootByConversationId(session.sessionId()).orElseThrow();
        assertEquals(firstPlanId, revisionRun.getPlanTarget());
        assertEquals(Integer.valueOf(1), revisionRun.getPlanRevision());
        assertEquals(selectedVersion, (long) revisionRun.getPlanStateVersion());
        assertEquals(runsBeforeRevision + 1,
                session.runRepository().findByConversationId(session.sessionId()).size());

        String shown = session.planShowView();
        assertTrue(shown.contains("revision: 2"));
        assertTrue(shown.contains("First plan"));
        assertTrue(shown.contains("body:\n    Body for First plan"));

        ByteArrayOutputStream oldRevisionOutput = new ByteArrayOutputStream();
        assertTrue(CliMain.handleControl(session, "/plan select " + firstPlanId + "@1",
                new PrintStream(oldRevisionOutput, true, java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(oldRevisionOutput.toString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("error:"));
        AgentSession afterOldRevisionAttempt = session.sessionRepository()
                .find(session.sessionId()).orElseThrow();
        assertEquals(firstPlanId, afterOldRevisionAttempt.getCurrentPlanId());
        assertEquals(2, afterOldRevisionAttempt.getPlans().stream()
                .filter(plan -> firstPlanId.equals(plan.getPlanId()))
                .findFirst().orElseThrow().currentRevision().getRevision().intValue());
        session.close();
    }

    @Test
    public void concurrentHeadChangeMakesLateRevisionTerminalPlanConflict() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-head-conflict");
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CliSessionService session = service(workspace,
                blockingSequencedPlanGateway(new AtomicInteger(), "First plan", "Late revision",
                        modelStarted, releaseModel));
        session.setCollaborationMode(CollaborationMode.PLAN);
        session.runTurn("submit first plan");

        AgentSession selected = session.sessionRepository().find(session.sessionId()).orElseThrow();
        String planId = selected.getCurrentPlanId();
        assertEquals(1L, selected.getPlanStateVersion());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> lateSubmission = executor.submit(() -> session.runTurn("revise the plan"));
            assertTrue(modelStarted.await(10, TimeUnit.SECONDS));

            FileAgentSessionRepository externalSessions = new FileAgentSessionRepository(workspace, mapper);
            FileAgentRunRepository externalRuns = new FileAgentRunRepository(workspace, mapper);
            FilePlanSubmissionHandler externalHandler = new FilePlanSubmissionHandler(
                    externalSessions, externalRuns, mapper);
            AgentContext concurrent = revisionPlanContext(workspace, session.sessionId(),
                    "concurrent-run", planId, 1, 1L, "Concurrent revision");
            assertEquals("PREPARED", externalHandler.prepare(concurrent).outcome().name());
            externalRuns.save(AgentRun.builder()
                    .runId(concurrent.getRunId())
                    .sessionId(concurrent.getSessionId())
                    .rootRunId(concurrent.getRootRunId())
                    .runKind(AgentRunKind.ROOT)
                    .runModeSnapshot(CollaborationMode.PLAN)
                    .planTarget(planId)
                    .planRevision(1)
                    .planStateVersion(1L)
                    .status(AgentRunStatus.COMPLETED)
                    .stopReason("PLAN_SUBMITTED")
                    .build());
            assertEquals("SUBMITTED", externalHandler.commit(concurrent).outcome().name());

            releaseModel.countDown();
            String answer = lateSubmission.get(30, TimeUnit.SECONDS);
            assertTrue(answer.toLowerCase().contains("plan conflict"));

            AgentSession persisted = externalSessions.find(session.sessionId()).orElseThrow();
            Plan persistedPlan = persisted.getPlans().stream()
                    .filter(plan -> planId.equals(plan.getPlanId())).findFirst().orElseThrow();
            assertEquals(planId, persisted.getCurrentPlanId());
            assertEquals(2L, persisted.getPlanStateVersion());
            assertEquals(2, persistedPlan.getRevisions().size());
            assertEquals("Concurrent revision", persistedPlan.currentRevision().getTitle());
            AgentRun lateRun = session.runRepository().findByConversationId(session.sessionId()).stream()
                    .filter(run -> "PLAN_CONFLICT".equals(run.getStopReason()))
                    .findFirst().orElseThrow();
            assertEquals("PLAN_CONFLICT", lateRun.getStopReason());
        } finally {
            releaseModel.countDown();
            executor.shutdownNow();
            session.close();
        }
    }

    @Test
    public void ordinaryFinalInPlanModeDoesNotCreatePlan() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-final");
        CliSessionService session = service(workspace, finalAnswerGateway("research only"));
        session.setCollaborationMode(CollaborationMode.PLAN);

        assertEquals("research only", session.runTurn("research only"));

        AgentSession persisted = session.sessionRepository().find(session.sessionId()).orElseThrow();
        assertTrue(persisted.getPlans().isEmpty());
        assertNull(persisted.getCurrentPlanId());
        assertEquals(0L, persisted.getPlanStateVersion());
        assertEquals("FINAL_ANSWER_RETURNED",
                session.runRepository().findLatestRootByConversationId(session.sessionId())
                        .orElseThrow().getStopReason());
        session.close();
    }

    @Test
    public void planSubmissionFromBuildModeCreatesNoPlan() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-build-reject");
        CliSessionService session = service(workspace,
                planSubmissionGateway(new AtomicInteger()));

        String answer = session.runTurn("submit a plan from build mode");

        assertTrue(answer.contains("root PLAN Run"));
        AgentSession persisted = session.sessionRepository().find(session.sessionId()).orElseThrow();
        assertTrue(persisted.getPlans().isEmpty());
        assertNull(persisted.getCurrentPlanId());
        assertEquals(0L, persisted.getPlanStateVersion());
        assertEquals("PLAN_SUBMISSION_REJECTED",
                session.runRepository().findLatestRootByConversationId(session.sessionId())
                        .orElseThrow().getStopReason());
        session.close();
    }

    @Test
    public void delegatePlanSubmissionCreatesNoPlan() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-delegate-reject");
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        AgentLoopService childLoop = CliLoopTestFixture.build(workspace, mapper,
                planSubmissionGateway(new AtomicInteger()), agent, java.util.List.of());

        childLoop.ask(AgentQuestion.builder()
                        .runId("child-plan-run")
                        .parentRunId("parent-run")
                        .rootRunId("root-run")
                        .sessionId("missing-session")
                        .conversationId("conversation")
                        .question("submit a plan")
                        .workspace(workspace.toString())
                        .maxSteps(3)
                        .maxAttempts(3)
                        .approvalPolicy("never")
                        .collaborationMode(CollaborationMode.PLAN)
                        .build())
                .collectList()
                .block();

        AgentRun child = runs.find("child-plan-run").orElseThrow();
        assertEquals("PLAN_SUBMISSION_REJECTED", child.getStopReason());
        assertFalse(Files.exists(workspace.resolve(".loom-code/sessions/missing-session.json")));
    }

    @Test
    public void changedPlanStateVersionBeforeSubmissionEndsInPlanConflict() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-state-conflict");
        CliSessionService session = service(workspace,
                planSubmissionGateway(new AtomicInteger()));
        session.setCollaborationMode(CollaborationMode.PLAN);

        FileAgentSessionRepository external = new FileAgentSessionRepository(workspace, mapper);
        AgentSession changed = external.find(session.sessionId()).orElseThrow();
        changed.setPlanStateVersion(9L);
        external.save(changed);

        String answer = session.runTurn("submit the first plan");

        assertTrue(answer.toLowerCase().contains("plan conflict"));
        AgentSession persisted = external.find(session.sessionId()).orElseThrow();
        assertTrue(persisted.getPlans().isEmpty());
        assertNull(persisted.getCurrentPlanId());
        assertEquals(9L, persisted.getPlanStateVersion());
        assertEquals("PLAN_CONFLICT",
                session.runRepository().findLatestRootByConversationId(session.sessionId())
                        .orElseThrow().getStopReason());
        session.close();
    }

    @Test
    public void reliedOnFileMutationBeforeSubmissionEndsInPlanConflict() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-conflict");
        Path observed = workspace.resolve("observed.txt");
        Files.writeString(observed, "before\n");
        CliSessionService session = serviceWithReadTool(workspace,
                readThenPlanWithMutationGateway(observed));
        session.setCollaborationMode(CollaborationMode.PLAN);

        String answer = session.runTurn("inspect and submit a plan");

        assertTrue(answer.toLowerCase().contains("plan conflict"));
        AgentSession persisted = session.sessionRepository().find(session.sessionId()).orElseThrow();
        assertTrue(persisted.getPlans().isEmpty());
        assertNull(persisted.getCurrentPlanId());
        assertEquals(0L, persisted.getPlanStateVersion());
        AgentRun run = session.runRepository().findLatestRootByConversationId(session.sessionId())
                .orElseThrow();
        assertEquals("FAILED", run.getStatus().name());
        assertEquals("PLAN_CONFLICT", run.getStopReason());
        session.close();
    }

    @Test
    public void planShowRecomputesFreshnessAfterRepositoryMutation() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-stale-view");
        Path observed = workspace.resolve("observed.txt");
        Files.writeString(observed, "before\n");
        CliSessionService session = serviceWithReadTool(workspace,
                readThenPlanGateway());
        session.setCollaborationMode(CollaborationMode.PLAN);

        assertTrue(session.runTurn("inspect and submit a plan").contains("Plan submitted:"));
        AgentSession persisted = session.sessionRepository().find(session.sessionId()).orElseThrow();
        assertEquals(1, persisted.getPlans().get(0).currentRevision().getPlanBasis().size());
        assertTrue(session.planShowView().contains("freshness: fresh"));

        Files.writeString(observed, "changed\n");

        assertTrue(session.planShowView().contains("freshness: stale"));
        session.close();
    }

    @Test
    public void planControlsDoNotRecoverOrWritePendingTransactions() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-read-only");
        CliSessionService session = service(workspace, finalAnswerGateway("unused"));
        FileAgentSessionRepository external = new FileAgentSessionRepository(workspace, mapper);
        AgentSession durable = external.find(session.sessionId()).orElseThrow();
        Plan pendingPlan = Plan.builder()
                .planId("pending-plan")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .revisions(List.of(PlanRevision.builder()
                        .revision(1)
                        .title("Pending")
                        .body("Pending body")
                        .dependencies(List.of())
                        .build()))
                .build();
        durable.setPendingPlanSubmission(PlanSubmissionTransaction.builder()
                .transactionId("pending-transaction")
                .runId("missing-run")
                .expectedPlanStateVersion(durable.getPlanStateVersion())
                .plan(pendingPlan)
                .build());
        external.save(durable);

        assertTrue(session.planListView().contains("(none)"));
        assertEquals("plan: (none)", session.planShowView());
        AgentSession after = external.find(session.sessionId()).orElseThrow();
        assertNotNull(after.getPendingPlanSubmission());
        session.close();
    }

    @Test
    public void newCommandCreatesIndependentSessionAndOriginalRemainsResumable() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-new-session");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService first = service(workspace,
                countingFinalAnswerGateway("before new", modelCalls));
        String originalId = first.sessionId();
        first.runTurn("preserve this conversation");

        first.close();
        CliSessionService.CliOptions reopenedOptions = options(workspace,
                countingFinalAnswerGateway("unused", modelCalls));
        reopenedOptions.resumeSessionId = originalId;
        AgentRuntimeProperties reopenedAgent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService reopened = new CliSessionService(reopenedOptions, mapper, reopenedAgent,
                new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper,
                        countingFinalAnswerGateway("unused", modelCalls),
                        reopenedAgent, java.util.List.of()));
        assertEquals(originalId, reopened.sessionId());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(CliMain.handleControl(reopened, "/new",
                new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)));
        String newId = reopened.sessionId();

        assertNotEquals(originalId, newId);
        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("new session: " + newId));
        assertEquals(1, modelCalls.get());
        AgentSession original = reopened.sessionRepository().find(originalId).orElseThrow();
        AgentSession fresh = reopened.sessionRepository().find(newId).orElseThrow();
        assertFalse(historyRepository(workspace).find(originalId).orElseThrow().getEntries().isEmpty());
        assertTrue(historyRepository(workspace).find(newId)
                .map(doc -> doc.getEntries() == null || doc.getEntries().isEmpty())
                .orElse(true));
        assertTrue(fresh.getWorkingMemory().isEmpty());
        assertTrue(fresh.getKeyFiles().isEmpty());
        assertEquals(1, reopened.runRepository().findByConversationId(originalId).size());
        assertTrue(reopened.runRepository().findByConversationId(newId).isEmpty());
        reopened.close();

        CliSessionService.CliOptions resumeOptions = options(workspace,
                countingFinalAnswerGateway("after resume", modelCalls));
        resumeOptions.resumeSessionId = originalId;
        AgentRuntimeProperties resumeAgent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService resumed = new CliSessionService(resumeOptions, mapper, resumeAgent,
                new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper,
                        countingFinalAnswerGateway("after resume", modelCalls),
                        resumeAgent, java.util.List.of()));
        assertEquals(originalId, resumed.sessionId());
        assertEquals("after resume", resumed.runTurn("continue original conversation"));
        assertEquals(2, resumed.runRepository().findByConversationId(originalId).size());
        assertTrue(historyRepository(workspace).find(newId)
                .map(doc -> doc.getEntries() == null || doc.getEntries().isEmpty())
                .orElse(true));
        resumed.close();
    }

    @Test
    public void resetCommandIsUnavailableWithoutStartingARun() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-reset-removed");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService session = service(workspace,
                countingFinalAnswerGateway("must not be called", modelCalls));
        String sessionId = session.sessionId();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertTrue(CliMain.handleControl(session, "/reset",
                new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)));

        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("/reset is unavailable"));
        assertEquals(sessionId, session.sessionId());
        assertEquals(0, modelCalls.get());
        assertTrue(session.runRepository().findByConversationId(sessionId).isEmpty());
        AgentSession persisted = session.sessionRepository().find(sessionId).orElseThrow();
        assertTrue(historyRepository(workspace).find(sessionId)
                .map(doc -> doc.getEntries() == null || doc.getEntries().isEmpty())
                .orElse(true));
        session.close();
    }

    @Test
    public void staleSessionCannotOverwriteNewerPersistedState() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-stale-session");
        CliSessionService session = service(workspace, finalAnswerGateway("unused"));
        String sessionId = session.sessionId();
        session.runTurn("old state");

        FileAgentSessionRepository externalStore = new FileAgentSessionRepository(workspace, mapper);
        FileConversationHistoryRepository externalHistory = historyRepository(workspace);
        AgentSession newer = externalStore.find(sessionId).orElseThrow();
        newer.setRuntimeIdentity("authoritative-newer-state");
        externalStore.save(newer);
        ConversationHistoryDocument existingHistory = externalHistory.find(sessionId).orElseThrow();
        java.util.List<ConversationHistoryEntry> entries = new java.util.ArrayList<>(existingHistory.getEntries());
        entries.add(ConversationHistoryEntry.builder()
                .entryId("external-entry")
                .sequence(existingHistory.getNextSequence())
                .role("user")
                .content("newer persisted state")
                .stableType(ConversationEntryType.USER_INPUT)
                .build());
        externalHistory.save(ConversationHistoryDocument.builder()
                .schemaVersion(ConversationHistoryDocument.CURRENT_SCHEMA_VERSION)
                .sessionId(sessionId)
                .entries(entries)
                .nextSequence(existingHistory.getNextSequence() + 1)
                .build());

        session.persistSession();

        AgentSession persisted = externalStore.find(sessionId).orElseThrow();
        assertEquals("authoritative-newer-state", persisted.getRuntimeIdentity());
        assertTrue(externalHistory.find(sessionId).orElseThrow().getEntries().stream()
                .anyMatch(entry -> "newer persisted state".equals(entry.content())));
        session.close();
    }

    // ---- resume: new root run, history carried over, old run untouched ----

    @Test
    public void resumeCreatesNewRootRunAndKeepsOldRunUntouched() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-resume");
        CliSessionService first = service(workspace, finalAnswerGateway("first answer"));
        String sessionId = first.sessionId();
        first.runTurn("first question");
        String oldRunId = first.runRepository().findLatestRootByConversationId(sessionId)
                .orElseThrow().getRunId();
        first.close();

        CliSessionService.CliOptions opts = options(workspace, finalAnswerGateway("second answer"));
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                finalAnswerGateway("second answer"), agent, java.util.List.of());
        CliSessionService resumed = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints,
                historyRepository(workspace), traces, loop);

        String answer = resumed.runTurn("second question");
        assertEquals("second answer", answer);

        AgentRun newRoot = resumed.runRepository().findLatestRootByConversationId(sessionId)
                .orElseThrow();
        assertNotEquals(oldRunId, newRoot.getRunId());
        assertEquals("ROOT", newRoot.getRunKind().name());

        // old run untouched: same runId, still completed, final answer unchanged
        AgentRun oldRun = resumed.runRepository().find(oldRunId).orElseThrow();
        assertEquals("first answer", oldRun.getFinalAnswer());
        assertEquals("COMPLETED", oldRun.getStatus().name());

        ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
        assertTrue(history.getEntries().size() >= 2);
        assertTrue(history.getEntries().stream()
                .anyMatch(e -> "second question".equals(e.content())));
        resumed.close();
    }

    @Test
    public void completedRootRunProjectsWorkingMemoryOnceAndCrashFinishDoesNotReproject()
            throws Exception {
        Path workspace = Files.createTempDirectory("e2e-wm-project-once");
        CliSessionService first = service(workspace, finalAnswerGateway("projected-summary"));
        String sessionId = first.sessionId();
        first.runTurn("capture memory");
        AgentRun completed = first.runRepository().findLatestRootByConversationId(sessionId)
                .orElseThrow();
        first.close();

        FileAgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        AgentSession projected = sessions.find(sessionId).orElseThrow();
        assertEquals("projected-summary", projected.getWorkingMemory().taskSummary());
        assertEquals(completed.getRunId(), projected.getLastProjectedRunId());

        WorkingContextMemory mutated = projected.getWorkingMemory();
        mutated.addNote(WorkingContextMemory.MemoryNote.of("post-projection session note"));
        projected.setWorkingMemory(mutated);
        // Keep lastProjectedRunId so crash-finish knows this Run already projected.
        sessions.save(projected);

        CliSessionService.CliOptions opts = options(workspace, finalAnswerGateway("unused"));
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService reopened = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(),
                sessions,
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper,
                        finalAnswerGateway("unused"), agent, java.util.List.of()));
        reopened.persistSession();
        reopened.close();

        AgentSession afterCrashFinish = sessions.find(sessionId).orElseThrow();
        assertEquals("projected-summary", afterCrashFinish.getWorkingMemory().taskSummary());
        assertTrue("crash-finish must not re-project and wipe later Session Working Memory",
                afterCrashFinish.getWorkingMemory().notes().stream()
                        .anyMatch(note -> "post-projection session note".equals(note.text())));
    }

    @Test
    public void sessionResumeInheritsProjectedWorkingMemoryOnNewRootRun() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-wm-resume-inherit");
        CliSessionService first = service(workspace, finalAnswerGateway("session-baseline"));
        String sessionId = first.sessionId();
        first.runTurn("establish baseline");
        String oldRunId = first.runRepository().findLatestRootByConversationId(sessionId)
                .orElseThrow().getRunId();
        assertEquals("session-baseline",
                first.sessionRepository().find(sessionId).orElseThrow()
                        .getWorkingMemory().taskSummary());
        first.close();

        CliSessionService.CliOptions opts = options(workspace, finalAnswerGateway("second-turn"));
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        CliSessionService resumed = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                runs, checkpoints, historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper,
                        finalAnswerGateway("second-turn"), agent, java.util.List.of()));

        assertEquals("session-baseline",
                resumed.sessionRepository().find(sessionId).orElseThrow()
                        .getWorkingMemory().taskSummary());
        assertEquals("second-turn", resumed.runTurn("continue with baseline"));

        AgentRun newRoot = runs.findLatestRootByConversationId(sessionId).orElseThrow();
        assertNotEquals(oldRunId, newRoot.getRunId());
        assertEquals(oldRunId, runs.find(oldRunId).orElseThrow().getRunId());
        assertEquals("COMPLETED", runs.find(oldRunId).orElseThrow().getStatus().name());

        AgentContextSnapshot newOverlay = checkpoints.latest(newRoot.getRunId())
                .orElseThrow().getContextSnapshot();
        assertNotNull(newOverlay.getWorkingMemory());
        assertEquals("second-turn", newOverlay.getWorkingMemory().taskSummary());
        AgentSession afterSecond = resumed.sessionRepository().find(sessionId).orElseThrow();
        assertEquals("second-turn", afterSecond.getWorkingMemory().taskSummary());
        assertEquals(newRoot.getRunId(), afterSecond.getLastProjectedRunId());
        resumed.close();
    }

    @Test
    public void failedStoppedAbandonedConflictAndDeviationOverlaysDoNotEnterSessionMemory()
            throws Exception {
        Path workspace = Files.createTempDirectory("e2e-wm-no-bad-project");
        CliSessionService baseline = service(workspace, finalAnswerGateway("kept-baseline"));
        String sessionId = baseline.sessionId();
        baseline.runTurn("keep this");
        baseline.close();
        assertEquals("kept-baseline",
                new FileAgentSessionRepository(workspace, mapper).find(sessionId).orElseThrow()
                        .getWorkingMemory().taskSummary());

        // FAILED via Plan Conflict
        CliSessionService conflictSession = resumeService(workspace, sessionId,
                planSubmissionGateway(new AtomicInteger()));
        conflictSession.setCollaborationMode(CollaborationMode.PLAN);
        FileAgentSessionRepository external = new FileAgentSessionRepository(workspace, mapper);
        AgentSession bumped = external.find(sessionId).orElseThrow();
        bumped.setPlanStateVersion(9L);
        external.save(bumped);
        conflictSession.runTurn("submit conflicting plan");
        AgentRun conflictRun = conflictSession.runRepository()
                .findLatestRootByConversationId(sessionId).orElseThrow();
        assertEquals(AgentRunStatus.FAILED, conflictRun.getStatus());
        assertEquals("PLAN_CONFLICT", conflictRun.getStopReason());
        String conflictSummary = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(conflictRun.getRunId()).orElseThrow()
                .getContextSnapshot().getWorkingMemory().taskSummary();
        assertNotNull(conflictSummary);
        assertTrue(conflictSummary.toLowerCase().contains("conflict"));
        assertEquals("kept-baseline",
                external.find(sessionId).orElseThrow().getWorkingMemory().taskSummary());
        conflictSession.close();

        // STOPPED via step limit
        FakeModelGateway stepLimitGateway = new FakeModelGateway(java.util.List.of(
                "<tool>{\"name\":\"list_files\",\"args\":{\"path\":\"a\"}}</tool>",
                "<tool>{\"name\":\"list_files\",\"args\":{\"path\":\"b\"}}</tool>",
                "<tool>{\"name\":\"list_files\",\"args\":{\"path\":\"c\"}}</tool>"));
        AgentRuntimeProperties stepAgent = CliLoopTestFixture.agentProperties(workspace);
        stepAgent.setMaxSteps(1);
        CliSessionService.CliOptions stepOpts = options(workspace, stepLimitGateway);
        stepOpts.resumeSessionId = sessionId;
        stepOpts.maxSteps = 1;
        CliSessionService stoppedSession = new CliSessionService(stepOpts, mapper, stepAgent,
                new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper, stepLimitGateway, stepAgent,
                        java.util.List.of(),
                        java.util.List.of(new cn.lunalhx.ai.infrastructure.loom.ListFilesTool(
                                new LocalWorkspacePort()))));
        stoppedSession.runTurn("hit step limit");
        AgentRun stoppedRun = stoppedSession.runRepository()
                .findLatestRootByConversationId(sessionId).orElseThrow();
        assertEquals(AgentRunStatus.STOPPED, stoppedRun.getStatus());
        assertEquals("STEP_LIMIT_REACHED", stoppedRun.getStopReason());
        String stoppedSummary = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(stoppedRun.getRunId()).orElseThrow()
                .getContextSnapshot().getWorkingMemory().taskSummary();
        assertNotNull(stoppedSummary);
        assertTrue(stoppedSummary.contains("上限") || stoppedSummary.contains("停止"));
        assertEquals("kept-baseline",
                external.find(sessionId).orElseThrow().getWorkingMemory().taskSummary());
        stoppedSession.close();

        // ABANDONED and PLAN_DEVIATION fabricated overlays must also stay out of Session WM
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        persistNonProjectingTerminalOverlay(runs, checkpoints, sessionId, workspace,
                "run_abandoned_overlay", AgentRunStatus.ABANDONED, "ABANDONED",
                "abandoned-overlay-must-not-project");
        persistNonProjectingTerminalOverlay(runs, checkpoints, sessionId, workspace,
                "run_deviation_overlay", AgentRunStatus.STOPPED, "PLAN_DEVIATION",
                "deviation-overlay-must-not-project");

        CliSessionService.CliOptions reopenOpts = options(workspace, finalAnswerGateway("unused"));
        reopenOpts.resumeSessionId = sessionId;
        AgentRuntimeProperties reopenAgent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService reopened = new CliSessionService(reopenOpts, mapper, reopenAgent,
                new ModelRuntimeProperties(),
                external, runs, checkpoints, historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper,
                        finalAnswerGateway("unused"), reopenAgent, java.util.List.of()));
        reopened.persistSession();
        reopened.close();

        String sessionSummary = external.find(sessionId).orElseThrow()
                .getWorkingMemory().taskSummary();
        assertEquals("kept-baseline", sessionSummary);
        assertFalse(sessionSummary.contains("abandoned-overlay"));
        assertFalse(sessionSummary.contains("deviation-overlay"));
        assertFalse(sessionSummary.toLowerCase().contains("conflict"));
    }

    private CliSessionService resumeService(Path workspace, String sessionId, ModelGateway gateway) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.resumeSessionId = sessionId;
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper, gateway, agent, java.util.List.of()));
    }

    private void persistNonProjectingTerminalOverlay(
            FileAgentRunRepository runs,
            FileAgentCheckpointRepository checkpoints,
            String sessionId,
            Path workspace,
            String runId,
            AgentRunStatus status,
            String stopReason,
            String overlaySummary) {
        WorkingContextMemory overlay = new WorkingContextMemory();
        overlay.setTaskSummary(overlaySummary);
        runs.save(AgentRun.builder()
                .schemaVersion(AgentRun.CURRENT_SCHEMA_VERSION)
                .runId(runId)
                .sessionId(sessionId)
                .rootRunId(runId)
                .requestId("req-" + runId)
                .conversationId(sessionId)
                .runKind(AgentRunKind.ROOT)
                .runModeSnapshot(CollaborationMode.BUILD)
                .status(status)
                .stopReason(stopReason)
                .workspace(workspace.toString())
                .updatedAt(Instant.now())
                .createdAt(Instant.now())
                .build());
        checkpoints.save(AgentCheckpoint.builder()
                .runId(runId)
                .currentNode("prompt_build")
                .reason("terminal_fixture")
                .contextSnapshot(AgentContextSnapshot.builder()
                        .schemaVersion(AgentContextSnapshot.CURRENT_SCHEMA_VERSION)
                        .runModeSnapshot(CollaborationMode.BUILD)
                        .workingMemory(overlay)
                        .build())
                .build());
    }

    // ---- checkpoint + run + trace + report consistency ----

    @Test
    public void runTraceReportConsistentAfterTerminal() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-consistency");
        CliSessionService session = service(workspace, finalAnswerGateway("consistent"));
        session.runTurn("task");

        AgentRun run = session.runRepository().findLatestRootByConversationId(session.sessionId())
                .orElseThrow();
        java.nio.file.Path runDir = workspace.resolve(".loom-code").resolve("runs")
                .resolve(run.getRunId());
        assertTrue(Files.isRegularFile(runDir.resolve("run.json")));
        assertTrue(Files.isRegularFile(runDir.resolve("trace.jsonl")));
        assertTrue(Files.isRegularFile(runDir.resolve("report.json")));
        assertTrue(Files.isRegularFile(runDir.resolve("task_state.json")));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> taskState = mapper.readValue(
                runDir.resolve("task_state.json").toFile(), java.util.Map.class);
        assertEquals("completed", taskState.get("status"));
        assertEquals("FINAL_ANSWER_RETURNED", taskState.get("stop_reason"));
        assertEquals(run.getRunId(), taskState.get("run_id"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> report = mapper.readValue(
                runDir.resolve("report.json").toFile(), java.util.Map.class);
        assertEquals(run.getStatus().name().toLowerCase(), report.get("status"));
        assertEquals(run.getStopReason(), report.get("stop_reason"));
        assertEquals(run.getToolSteps(), report.get("tool_steps"));
        session.close();
    }

    // ---- workspace mismatch on resume is rejected ----

    @Test
    public void resumeWithDifferentWorkspaceIsRejected() throws Exception {
        Path workspaceA = Files.createTempDirectory("e2e-ws-a");
        Path workspaceB = Files.createTempDirectory("e2e-ws-b");
        CliSessionService first = service(workspaceA, finalAnswerGateway("ok"));
        String sessionId = first.sessionId();
        first.runTurn("q");
        first.close();

        // copy the session file into workspace B's store so the loader sees it
        Path targetDir = Files.createDirectories(
                workspaceB.resolve(".loom-code").resolve("sessions"));
        Files.copy(first.sessionPath(), targetDir.resolve(sessionId + ".json"));

        CliSessionService.CliOptions opts = options(workspaceB, finalAnswerGateway("ok"));
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspaceB);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspaceB, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspaceB, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspaceB, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspaceB, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspaceB, mapper,
                finalAnswerGateway("ok"), agent, java.util.List.of());
        try {
            new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                    sessions, runs, checkpoints, historyRepository(workspaceB), traces, loop);
            fail("expected workspace mismatch rejection");
        } catch (CliSessionService.OptionsException e) {
            assertTrue(e.getMessage().contains("workspace"));
        }
    }

    // ---- old schema sessions are rejected without overwrite ----

    @Test
    public void legacySchemaSessionIsRejected() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-schema");
        Path sessionsDir = Files.createDirectories(workspace.resolve(".loom-code").resolve("sessions"));
        Path legacy = sessionsDir.resolve("legacy.json");
        Files.writeString(legacy, "{\"id\":\"legacy\",\"schema_version\":0,\"workspace_root\":\""
                + workspace.toString() + "\",\"history\":[]}");

        CliSessionService.CliOptions opts = options(workspace, finalAnswerGateway("x"));
        opts.resumeSessionId = "legacy";
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                finalAnswerGateway("x"), agent, java.util.List.of());
        try {
            new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                    sessions, runs, checkpoints, historyRepository(workspace), traces, loop);
            fail("expected schema rejection");
        } catch (CliSessionService.OptionsException e) {
            assertTrue(e.getMessage().contains("schema"));
        }
        // original file untouched
        assertTrue(Files.readString(legacy).contains("schema_version"));
    }

    @Test
    public void currentSchemaSessionWithoutModeIsRejected() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-schema-missing-mode");
        Path sessionsDir = Files.createDirectories(workspace.resolve(".loom-code").resolve("sessions"));
        Path invalid = sessionsDir.resolve("missing-mode.json");
        Files.writeString(invalid, "{\"id\":\"missing-mode\",\"schemaVersion\":"
                + AgentSession.CURRENT_SCHEMA_VERSION + ",\"workspaceRoot\":\""
                + workspace + "\"}");

        try {
            new FileAgentSessionRepository(workspace, mapper).find("missing-mode");
            fail("expected missing mode rejection");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("collaboration mode"));
        }
        assertTrue(Files.readString(invalid).contains("schemaVersion"));
    }

    // ---- key file change invalidates checkpoint summary on resume ----

    @Test
    public void changedKeyFileInvalidatesCheckpointSummaryOnResume() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-keyfile");
        Path keyFile = workspace.resolve("A.java");
        Files.writeString(keyFile, "class A {}");

        CliSessionService first = serviceWithReadTool(workspace,
                readThenFinalGateway("A.java", "first"));
        String sessionId = first.sessionId();
        first.runTurn("work on A.java");
        first.close();

        // mutate the key file → resume must invalidate the stale summary
        Files.writeString(keyFile, "class A { void changed() {} }");

        CliSessionService.CliOptions opts = options(workspace, finalAnswerGateway("second"));
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                finalAnswerGateway("second"), agent, java.util.List.of());
        CliSessionService resumed = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints,
                historyRepository(workspace), traces, loop);

        AgentSession loaded = resumed.sessionRepository().find(sessionId).orElseThrow();
        assertFalse("stale file summary must be discarded after key file change",
                loaded.getWorkingMemory().fileSummaries().containsKey("A.java"));
        resumed.close();
    }

    private CliSessionService serviceWithReadTool(Path workspace, ModelGateway gateway) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                java.util.List.of(),
                java.util.List.of(new cn.lunalhx.ai.infrastructure.loom.ReadFileTool(
                        new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort())));
        return new CliSessionService(options(workspace, gateway), mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints,
                historyRepository(workspace), traces, loop);
    }

    /** Gateway: first call asks for a read_file of the path, then returns the final answer. */
    private ModelGateway readThenFinalGateway(String path, String answer) {
        AtomicInteger calls = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"read_file\",\"args\":{\"path\":\""
                                    + path + "\"}}</tool>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway countingFinalAnswerGateway(String answer, AtomicInteger calls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway planSubmissionGateway(AtomicInteger calls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content("<plan_submission>{\"title\":\"First plan\",\"body\":\"Start with repository research.\",\"dependencies\":[]}</plan_submission>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway planThenFinalGateway(AtomicInteger calls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<plan_submission>{\"title\":\"First plan\",\"body\":\"Start with research.\",\"dependencies\":[]}</plan_submission>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<final>build answer</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway sequencedPlanGateway(AtomicInteger calls, String... titles) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int index = calls.getAndIncrement();
                String title = titles[Math.min(index, titles.length - 1)];
                return Mono.just(ModelChatResult.builder()
                        .content("<plan_submission>{\"title\":\"" + title
                                + "\",\"body\":\"Body for " + title
                                + "\",\"dependencies\":[]}</plan_submission>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway blockingSequencedPlanGateway(AtomicInteger calls, String firstTitle,
                                                      String secondTitle,
                                                      CountDownLatch modelStarted,
                                                      CountDownLatch releaseModel) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int index = calls.getAndIncrement();
                if (index == 1) {
                    modelStarted.countDown();
                    try {
                        if (!releaseModel.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to release model");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("model wait interrupted", e);
                    }
                }
                String title = index == 0 ? firstTitle : secondTitle;
                return Mono.just(ModelChatResult.builder()
                        .content("<plan_submission>{\"title\":\"" + title
                                + "\",\"body\":\"Body for " + title
                                + "\",\"dependencies\":[]}</plan_submission>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private AgentContext revisionPlanContext(Path workspace, String sessionId, String runId,
                                             String planId, int planRevision,
                                             long planStateVersion, String title) {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setRootRunId(runId);
        context.setSessionId(sessionId);
        context.setCollaborationMode(CollaborationMode.PLAN);
        context.setPlanTarget(planId);
        context.setPlanRevision(planRevision);
        context.setPlanStateVersion(planStateVersion);
        context.setResolvedWorkspace(workspace);
        context.setDecision(AgentDecision.builder()
                .type("plan_submission")
                .planSubmission(PlanSubmission.builder()
                        .title(title)
                        .body("Body for " + title)
                        .dependencies(List.of())
                        .build())
                .build());
        return context;
    }

    private ModelGateway readThenPlanWithMutationGateway(Path observed) {
        AtomicInteger calls = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"read_file\",\"args\":{\"path\":\"observed.txt\",\"start\":1,\"end\":1}}</tool>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
                try {
                    Files.writeString(observed, "after\n");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<plan_submission>{\"title\":\"First plan\",\"body\":\"Use the observed repository state.\",\"dependencies\":[]}</plan_submission>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway readThenPlanGateway() {
        AtomicInteger calls = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"read_file\",\"args\":{\"path\":\"observed.txt\",\"start\":1,\"end\":1}}</tool>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<plan_submission>{\"title\":\"First plan\",\"body\":\"Use the observed repository state.\",\"dependencies\":[]}</plan_submission>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    // ---- approval: ask in interactive terminal; non-interactive rejects ----

    @Test
    public void approvalDeniedInNonInteractiveModeReachesObservation() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-approval");
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService.CliOptions opts = options(workspace,
                runShellGateway("write_file", "done"));
        opts.approvalPolicy = "ask";
        opts.approvalPrompt = new CliApprovalPrompt(false);

        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                runShellGateway("write_file", "done"), agent, java.util.List.of(),
                java.util.List.of(new cn.lunalhx.ai.infrastructure.loom.WriteFileTool(
                        new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort())));
        CliSessionService session = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints,
                historyRepository(workspace), traces, loop);

        String answer = session.runTurn("write a file");
        assertEquals("done", answer);
        session.close();

        assertTrue("approval denial must reach the model as an observation",
                historyRepository(workspace).find(session.sessionId()).orElseThrow().getEntries().stream()
                        .anyMatch(e -> e.content() != null && e.content().contains("approval denied")));
    }

    /** Gateway: first call invokes the given approval-controlled tool, then returns the final answer. */
    private ModelGateway runShellGateway(String tool, String answer) {
        AtomicInteger calls = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"" + tool
                                    + "\",\"args\":{\"path\":\"x.txt\",\"content\":\"hi\"}}</tool>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    // ---- delegate: real child run with parent/root lineage, read-only, capped ----

    @Test
    public void delegateCreatesRealChildRunWithLineage() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-delegate");
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService.CliOptions opts = options(workspace, delegateGateway("done"));
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                delegateGateway("done"), agent, java.util.List.of(),
                java.util.List.of(new cn.lunalhx.ai.infrastructure.loom.DelegateTool(
                        new TestDelegateRunner(workspace, mapper, agent))));
        CliSessionService session = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints,
                historyRepository(workspace), traces, loop);

        String answer = session.runTurn("delegate to investigate");
        assertEquals("done", answer);
        session.close();

        AgentRun root = runs.findLatestRootByConversationId(session.sessionId()).orElseThrow();
        assertNull(root.getParentRunId());
        assertEquals("ROOT", root.getRunKind().name());

        // the delegate child must exist as a real run with parent lineage
        java.util.List<AgentRun> children = runs.findChildren(root.getRunId());
        assertEquals(1, children.size());
        AgentRun child = children.get(0);
        assertEquals(root.getRunId(), child.getParentRunId());
        assertEquals(root.getRunId(), child.getRootRunId());
        assertEquals(session.sessionId(), child.getConversationId());
        assertEquals("CHILD", child.getRunKind().name());
        assertEquals(3, (int) child.getMaxSteps());
    }

    @Test
    public void planDelegateFoldsChildEvidenceAndMarksRootDriftWithoutWideningAuthority()
            throws Exception {
        Path workspace = Files.createTempDirectory("e2e-delegate-evidence");
        Path file = workspace.resolve("observed.txt");
        Files.writeString(file, "before\n");
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AtomicInteger parentCalls = new AtomicInteger();
        TestDelegateRunner delegateRunner = new TestDelegateRunner(workspace, mapper, agent);
        ModelGateway gateway = delegateReadTwiceGateway(file, parentCalls);

        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.startupMode = CollaborationMode.PLAN;
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                java.util.List.of(), java.util.List.of(
                        new ReadFileTool(new LocalWorkspacePort()),
                        new DelegateTool(delegateRunner)));
        CliSessionService session = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints,
                historyRepository(workspace), traces, loop);

        assertEquals("done", session.runTurn("delegate the file read twice"));
        session.close();

        AgentRun root = runs.findLatestRootByConversationId(session.sessionId()).orElseThrow();
        assertEquals("PLAN", root.getRunModeSnapshot().name());
        assertTrue(Boolean.TRUE.equals(root.getEvidenceDrift()));
        assertEquals(2, root.getEvidenceReceipts().size());
        assertNotEquals(root.getEvidenceReceipts().get(0).getStateDigest(),
                root.getEvidenceReceipts().get(1).getStateDigest());
        assertEquals(root.getRunId(), root.getEvidenceReceipts().get(0).getRootRunId());
        assertEquals(root.getRunId(), root.getEvidenceReceipts().get(1).getRootRunId());
        assertEquals(2, delegateRunner.results.size());
        assertEquals(AgentRunStatus.COMPLETED, delegateRunner.results.get(1).getStatus());
        assertEquals(delegateRunner.results.get(1).getProvenance().getRunId(),
                root.getEvidenceReceipts().get(1).getSourceRunId());
        assertEquals(java.util.List.of("read_file"), delegateRunner.lastAllowedTools);

        java.util.List<AgentRun> children = runs.findChildren(root.getRunId());
        assertEquals(2, children.size());
        for (AgentRun child : children) {
            assertEquals(CollaborationMode.PLAN, child.getRunModeSnapshot());
            assertEquals(1, (int) child.getDepth());
            assertEquals(3, (int) child.getMaxSteps());
        }
        var checkpoint = checkpoints.latest(root.getRunId()).orElseThrow();
        assertNotNull(checkpoint.getContextSnapshot().getToolResult());
        assertNotNull(checkpoint.getContextSnapshot().getToolResult().getDelegateResult());
        assertEquals(AgentRunStatus.COMPLETED,
                checkpoint.getContextSnapshot().getToolResult().getDelegateResult().getStatus());
    }

    /** Test-local DelegateRunner: spawns a real read-only child loop with true lineage. */
    private static final class TestDelegateRunner implements DelegateRunner {
        private final Path workspace;
        private final ObjectMapper mapper;
        private final AgentRuntimeProperties agent;
        private final java.util.List<DelegateResult> results = new java.util.ArrayList<>();
        private java.util.List<String> lastAllowedTools = java.util.List.of();

        TestDelegateRunner(Path workspace, ObjectMapper mapper, AgentRuntimeProperties agent) {
            this.workspace = workspace;
            this.mapper = mapper;
            this.agent = agent;
        }

        @Override
        public DelegateResult delegate(DelegateRequest request) {
            lastAllowedTools = request.getAllowedTools();
            String childRunId = "child-" + java.util.UUID.randomUUID();
            ModelGateway childGateway = new ModelGateway() {
                private final AtomicInteger calls = new AtomicInteger();

                @Override
                public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                    return Flux.empty();
                }

                @Override
                public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                    if (calls.getAndIncrement() == 0
                            && request.getAllowedTools() != null
                            && request.getAllowedTools().contains("read_file")) {
                        return Mono.just(ModelChatResult.builder()
                                .content("<tool>{\"name\":\"read_file\",\"args\":{\"path\":\"observed.txt\",\"start\":1,\"end\":1}}</tool>")
                                .finishReason("stop")
                                .actualModel("deepseek-v4-flash")
                                .build());
                    }
                    return Mono.just(ModelChatResult.builder()
                            .content("<final>child read complete</final>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
            };
            AgentLoopService childLoop = CliLoopTestFixture.build(workspace, mapper,
                    childGateway, agent, java.util.List.of(),
                    java.util.List.of(new cn.lunalhx.ai.infrastructure.loom.ReadFileTool(
                            new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort())));
            AtomicReference<String> answer = new AtomicReference<>("");
            childLoop.ask(cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion.builder()
                            .runId(childRunId)
                            .question(request.getTask())
                            .parentRunId(request.getParentRunId())
                            .rootRunId(request.getRootRunId())
                            .sessionId(request.getSessionId())
                            .conversationId(request.getConversationId())
                            .workspace(request.getWorkspaceRoot())
                            .agentDepth(request.getParentDepth() + 1)
                            .maxSteps(request.getChildMaxSteps())
                            .maxAttempts(request.getChildMaxAttempts())
                            .approvalPolicy("never")
                            .collaborationMode(request.getModeSnapshot())
                            .allowedTools(request.getAllowedTools())
                            .build())
                    .doOnNext(e -> {
                        if (e.getAnswer() != null && !e.getAnswer().isBlank()) {
                            answer.set(e.getAnswer());
                        }
                    })
                    .blockLast();
            AgentRun child = new FileAgentRunRepository(workspace, mapper).find(childRunId).orElseThrow();
            DelegateResult result = DelegateResult.builder()
                    .safeOutcome(answer.get().isBlank() ? "(empty)" : answer.get())
                    .status(child.getStatus())
                    .provenance(DelegateProvenance.builder()
                            .runId(child.getRunId())
                            .parentRunId(child.getParentRunId())
                            .rootRunId(child.getRootRunId())
                            .sessionId(child.getSessionId())
                            .workspaceRoot(realWorkspace())
                            .modeSnapshot(child.getRunModeSnapshot())
                            .depth(child.getDepth())
                            .build())
                    .evidenceReceipts(child.getEvidenceReceipts())
                    .build();
            results.add(result);
            return result;
        }

        private String realWorkspace() {
            try {
                return workspace.toRealPath().toString();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private ModelGateway delegateReadTwiceGateway(Path file, AtomicInteger calls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int call = calls.getAndIncrement();
                if (call == 0 || call == 1) {
                    if (call == 1) {
                        try {
                            Files.writeString(file, "after\n");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"delegate\",\"args\":{\"task\":\"read observed.txt\",\"max_steps\":3}}</tool>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<final>done</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    /** Gateway: parent calls delegate once, then finalizes; the child runs in
     *  a separate loop via the test DelegateRunner. */
    private ModelGateway delegateGateway(String answer) {
        AtomicInteger calls = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int call = calls.getAndIncrement();
                if (call == 0) {
                    // parent: delegate
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"delegate\",\"args\":{\"task\":\"inspect\",\"max_steps\":3}}</tool>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
                // parent: final (child already returned via delegate tool result)
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    // ---- ticket 04: Recovery Required, /recover, /abandon for no-tool runs ----

    @Test
    public void interruptedNoToolRunEntersRecoveryRequiredAndRejectsOrdinaryRequest()
            throws Exception {
        Path workspace = Files.createTempDirectory("e2e-recovery-required");
        InterruptedNoToolRun interrupted = interruptNoToolRun(workspace);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                countingFinalAnswerGateway("must not run", modelCalls));
        try {
            assertTrue(resumed.recoveryRequired());
            AgentRun blocked = resumed.recoveryRequiredRun().orElseThrow();
            assertEquals(interrupted.runId, blocked.getRunId());
            assertEquals(AgentRunStatus.RUNNING, blocked.getStatus());

            try {
                resumed.runTurn("start a different task");
                fail("ordinary request must be rejected while Recovery Required");
            } catch (CliSessionService.OptionsException e) {
                assertTrue(e.getMessage().contains("Recovery Required"));
            }
            assertEquals(0, modelCalls.get());
            assertEquals(interrupted.runId,
                    resumed.runRepository().findLatestRootByConversationId(interrupted.sessionId)
                            .orElseThrow().getRunId());
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void recoverKeepsRunIdCreatesNewAttemptAndCompletesOriginalTask() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-recover");
        InterruptedNoToolRun interrupted = interruptNoToolRun(workspace);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                countingFinalAnswerGateway("original task done", modelCalls));
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/recover",
                    new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)));
            assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
                    .contains("original task done"));
            assertEquals(1, modelCalls.get());
            assertFalse(resumed.recoveryRequired());

            AgentRun recovered = resumed.runRepository().find(interrupted.runId).orElseThrow();
            assertEquals(interrupted.runId, recovered.getRunId());
            assertNotEquals(interrupted.attemptId, recovered.getCurrentAttemptId());
            assertEquals(AgentRunStatus.COMPLETED, recovered.getStatus());
            assertEquals("original task done", recovered.getFinalAnswer());
            assertEquals(1, resumed.runRepository().findByConversationId(interrupted.sessionId).size());
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void abandonDoesNotCallModelIsTerminalAndPreservesHistory() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-abandon");
        InterruptedNoToolRun interrupted = interruptNoToolRun(workspace);
        ConversationHistoryDocument before = historyRepository(workspace)
                .find(interrupted.sessionId).orElseThrow();
        assertFalse(before.getEntries().isEmpty());
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                countingFinalAnswerGateway("after abandon", modelCalls));
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/abandon",
                    new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)));
            assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
                    .contains("abandoned: " + interrupted.runId));
            assertEquals(0, modelCalls.get());
            assertFalse(resumed.recoveryRequired());

            AgentRun abandoned = resumed.runRepository().find(interrupted.runId).orElseThrow();
            assertEquals(AgentRunStatus.ABANDONED, abandoned.getStatus());
            assertEquals("ABANDONED", abandoned.getStopReason());
            ConversationHistoryDocument after = historyRepository(workspace)
                    .find(interrupted.sessionId).orElseThrow();
            assertEquals(before.getEntries().size(), after.getEntries().size());
            assertEquals(before.getEntries().get(0).content(), after.getEntries().get(0).content());

            try {
                resumed.recover();
                fail("abandoned Run must not be recoverable");
            } catch (CliSessionService.OptionsException e) {
                assertTrue(e.getMessage().contains("no Recovery Required"));
            }

            assertEquals("after abandon", resumed.runTurn("new work after abandon"));
            assertEquals(1, modelCalls.get());
            AgentRun next = resumed.runRepository().findLatestRootByConversationId(interrupted.sessionId)
                    .orElseThrow();
            assertNotEquals(interrupted.runId, next.getRunId());
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    private InterruptedNoToolRun interruptNoToolRun(Path workspace) throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CliSessionService first = service(workspace,
                blockingFinalGateway(modelStarted, releaseModel, "unused"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> turn = executor.submit(() -> first.runTurn("finish the original task"));
        assertTrue(modelStarted.await(10, TimeUnit.SECONDS));
        AgentRun running = first.runRepository().findLatestRootByConversationId(first.sessionId())
                .orElseThrow();
        assertEquals(AgentRunStatus.RUNNING, running.getStatus());
        assertNotNull(running.getCurrentAttemptId());
        FileAttemptLeaseRepository leases = new FileAttemptLeaseRepository(workspace, mapper);
        AttemptLease lease = leases.find(running.getRunId()).orElseThrow();
        assertTrue(leases.isHealthy(running.getRunId()));
        assertTrue(leases.release(running.getRunId(), lease.getFence()));
        return new InterruptedNoToolRun(first, executor, turn, releaseModel,
                first.sessionId(), running.getRunId(), running.getCurrentAttemptId());
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway) {
        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper, gateway, agent, java.util.List.of()));
    }

    private ModelGateway blockingFinalGateway(CountDownLatch started, CountDownLatch release,
                                              String answer) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                started.countDown();
                try {
                    if (!release.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release model");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("model wait interrupted", e);
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private static final class InterruptedNoToolRun implements AutoCloseable {
        private final CliSessionService first;
        private final ExecutorService executor;
        private final Future<String> turn;
        private final CountDownLatch releaseModel;
        private final String sessionId;
        private final String runId;
        private final String attemptId;

        private InterruptedNoToolRun(CliSessionService first, ExecutorService executor,
                                     Future<String> turn, CountDownLatch releaseModel,
                                     String sessionId, String runId, String attemptId) {
            this.first = first;
            this.executor = executor;
            this.turn = turn;
            this.releaseModel = releaseModel;
            this.sessionId = sessionId;
            this.runId = runId;
            this.attemptId = attemptId;
        }

        @Override
        public void close() {
            releaseModel.countDown();
            turn.cancel(true);
            executor.shutdownNow();
            first.close();
        }
    }

    // ---- secret values never appear in persisted artifacts ----

    @Test
    public void secretEnvValuesRedactedInPersistedArtifacts() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-secret");
        String secret = "TOPSECRETVALUE_123";
        CliSessionService.CliOptions opts = options(workspace, finalAnswerGateway("done"));
        opts.secretValues.add(secret);
        CliSessionService session = serviceWithSecret(workspace, opts);
        String answer = session.runTurn("remember " + secret + " in your answer");
        assertEquals("done", answer);
        session.close();

        // scan everything under .loom-code for the secret
        Path loomDir = workspace.resolve(".loom-code");
        assertFalse("secret leaked into persisted artifacts",
                containsSecret(loomDir, secret));
    }

    private boolean containsSecret(Path dir, String secret) throws Exception {
        if (!Files.exists(dir)) {
            return false;
        }
        try (var stream = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (Files.readString(file).contains(secret)) {
                    return true;
                }
            }
        }
        return false;
    }

    private CliSessionService serviceWithSecret(Path workspace,
                                                CliSessionService.CliOptions opts) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                finalAnswerGateway("done"), agent, java.util.List.of());
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                sessions, runs, checkpoints, historyRepository(workspace), traces, loop);
    }
}
