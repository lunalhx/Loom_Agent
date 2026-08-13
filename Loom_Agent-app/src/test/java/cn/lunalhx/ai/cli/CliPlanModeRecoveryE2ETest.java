package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.AttemptLease;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.infrastructure.loom.ReadFileTool;
import cn.lunalhx.ai.infrastructure.loom.WriteFileTool;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileAttemptLeaseRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import cn.lunalhx.ai.test.FakeModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ticket 14 CLI Session seam: Plan Mode evidence, drift, conflict, and
 * deviation survive crash recovery; terminal Plan outcomes stay immutable
 * and are never projected as success.
 */
public class CliPlanModeRecoveryE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void recoveredPlanEvidenceStillRevalidatesAndCanSubmit() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-recover-fresh");
        Path observed = workspace.resolve("observed.txt");
        Files.writeString(observed, "before\n");
        InterruptedPlanRun interrupted = interruptAfterRead(workspace, observed, false);
        try {
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(interrupted.runId).orElseThrow();
            List<EvidenceReceipt> receipts = latest.getContextSnapshot().getEvidenceReceipts();
            assertNotNull(receipts);
            assertEquals(1, receipts.size());
            assertFalse(latest.getContextSnapshot().isEvidenceDrift());
            assertTrue(PlanEvidenceVerifier.matches(workspace, receipts.get(0)));

            CliSessionService resumed = resume(workspace, interrupted.sessionId,
                    planSubmissionGateway(), List.of(new ReadFileTool(new LocalWorkspacePort())));
            try {
                assertTrue(resumed.recoveryRequired());
                String answer = resumed.recover();
                assertFalse(answer.toLowerCase().contains("plan conflict"));

                AgentRun recovered = resumed.runRepository().find(interrupted.runId).orElseThrow();
                assertEquals(interrupted.runId, recovered.getRunId());
                assertNotEquals(interrupted.attemptId, recovered.getCurrentAttemptId());
                assertEquals(AgentRunStatus.COMPLETED, recovered.getStatus());
                assertEquals("PLAN_SUBMITTED", recovered.getStopReason());
                assertFalse(Boolean.TRUE.equals(recovered.getEvidenceDrift()));
                List<EvidenceReceipt> recoveredReceipts = recovered.getEvidenceReceipts();
                assertNotNull(recoveredReceipts);
                assertEquals(1, recoveredReceipts.size());
                assertTrue(PlanEvidenceVerifier.matches(workspace, recoveredReceipts.get(0)));
                assertTrue(PlanEvidenceVerifier.matchesAll(workspace,
                        recoveredReceipts, recovered.getRootRunId()));

                AgentSession session = resumed.sessionRepository().find(interrupted.sessionId).orElseThrow();
                assertEquals(1, session.getPlans().size());
                assertNotNull(session.getCurrentPlanId());
                assertTrue(PlanEvidenceVerifier.matchesPlan(workspace, session.getPlans().get(0)));
                assertNoLegacyRecoveryArtifacts(workspace);
            } finally {
                resumed.close();
            }
        } finally {
            interrupted.close();
        }
    }

    @Test
    public void recoveredStaleEvidenceEndsInPlanConflictAndDoesNotPersistAPlan() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-recover-stale");
        Path observed = workspace.resolve("observed.txt");
        Files.writeString(observed, "before\n");
        InterruptedPlanRun interrupted = interruptAfterRead(workspace, observed, false);
        try {
            Files.writeString(observed, "after\n");
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(interrupted.runId).orElseThrow();
            assertFalse(PlanEvidenceVerifier.matches(workspace,
                    latest.getContextSnapshot().getEvidenceReceipts().get(0)));

            CliSessionService resumed = resume(workspace, interrupted.sessionId,
                    planSubmissionGateway(), List.of(new ReadFileTool(new LocalWorkspacePort())));
            try {
                String answer = resumed.recover();
                assertTrue(answer.toLowerCase().contains("plan conflict"));

                AgentRun recovered = resumed.runRepository().find(interrupted.runId).orElseThrow();
                assertEquals(AgentRunStatus.FAILED, recovered.getStatus());
                assertEquals("PLAN_CONFLICT", recovered.getStopReason());
                AgentSession session = resumed.sessionRepository().find(interrupted.sessionId).orElseThrow();
                assertTrue(session.getPlans().isEmpty());
                assertNull(session.getCurrentPlanId());
                assertNotEquals(interrupted.runId, session.getLastProjectedRunId());
                assertNoLegacyRecoveryArtifacts(workspace);
            } finally {
                resumed.close();
            }
        } finally {
            interrupted.close();
        }
    }

    @Test
    public void evidenceDriftSurvivesCrashAndRecoveredSubmissionIsPlanConflict() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-recover-drift");
        Path observed = workspace.resolve("observed.txt");
        Files.writeString(observed, "before\n");
        InterruptedPlanRun interrupted = interruptAfterRead(workspace, observed, true);
        try {
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(interrupted.runId).orElseThrow();
            assertTrue(latest.getContextSnapshot().isEvidenceDrift());
            assertEquals(2, latest.getContextSnapshot().getEvidenceReceipts().size());
            assertTrue(Boolean.TRUE.equals(new FileAgentRunRepository(workspace, mapper)
                    .find(interrupted.runId).orElseThrow().getEvidenceDrift()));

            CliSessionService resumed = resume(workspace, interrupted.sessionId,
                    planSubmissionGateway(), List.of(new ReadFileTool(new LocalWorkspacePort())));
            try {
                String answer = resumed.recover();
                assertTrue(answer.toLowerCase().contains("plan conflict"));

                AgentRun recovered = resumed.runRepository().find(interrupted.runId).orElseThrow();
                assertEquals(AgentRunStatus.FAILED, recovered.getStatus());
                assertEquals("PLAN_CONFLICT", recovered.getStopReason());
                assertTrue(Boolean.TRUE.equals(recovered.getEvidenceDrift()));
                AgentSession session = resumed.sessionRepository().find(interrupted.sessionId).orElseThrow();
                assertTrue(session.getPlans().isEmpty());
                assertNull(session.getCurrentPlanId());
                assertNotEquals(interrupted.runId, session.getLastProjectedRunId());
            } finally {
                resumed.close();
            }
        } finally {
            interrupted.close();
        }
    }

    @Test
    public void terminalPlanConflictCannotBeRecoveredOrProjectedAsSuccess() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-conflict-terminal");
        Path observed = workspace.resolve("observed.txt");
        Files.writeString(observed, "before\n");
        CliSessionService first = service(workspace, readThenMutatingPlanGateway(observed),
                List.of(new ReadFileTool(new LocalWorkspacePort())));
        String sessionId;
        String runId;
        try {
            first.setCollaborationMode(CollaborationMode.PLAN);
            String answer = first.runTurn("inspect and submit a plan");
            assertTrue(answer.toLowerCase().contains("plan conflict"));
            AgentRun conflict = first.runRepository()
                    .findLatestRootByConversationId(first.sessionId()).orElseThrow();
            sessionId = first.sessionId();
            runId = conflict.getRunId();
            assertEquals(AgentRunStatus.FAILED, conflict.getStatus());
            assertEquals("PLAN_CONFLICT", conflict.getStopReason());
        } finally {
            first.close();
        }

        CliSessionService resumed = resume(workspace, sessionId, planSubmissionGateway(), List.of());
        try {
            assertFalse(resumed.recoveryRequired());
            try {
                resumed.recover();
                fail("terminal Plan Conflict must not enter Run Recovery");
            } catch (CliSessionService.OptionsException e) {
                assertTrue(e.getMessage().contains("no Recovery Required"));
            }
            AgentRun mutated = resumed.runRepository().find(runId).orElseThrow();
            mutated.setStatus(AgentRunStatus.RUNNING);
            try {
                resumed.runRepository().save(mutated);
                fail("terminal Plan Conflict must reject later run writes");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("terminal"));
            }
            AgentSession session = resumed.sessionRepository().find(sessionId).orElseThrow();
            assertTrue(session.getPlans().isEmpty());
            assertNotEquals(runId, session.getLastProjectedRunId());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void terminalPlanDeviationCannotBeRecoveredOrProjectedAsSuccess() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-deviation-terminal");
        FakeModelGateway gateway = new FakeModelGateway(List.of(
                "<plan_submission>{\"title\":\"First plan\",\"body\":\"Write the requested feature.\",\"dependencies\":[]}</plan_submission>",
                "<tool>{\"name\":\"write_file\",\"args\":{\"path\":\"before-deviation.txt\",\"content\":\"kept\"}}</tool>",
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"Continuing requires changing files outside the bound scope.\"},\"workspace_changes\":[{\"path\":\"before-deviation.txt\",\"operation\":\"created\",\"summary\":\"Created before discovering the scope conflict.\"}]}</plan_deviation>",
                "<final>must not recover as success</final>"));
        CliSessionService first = service(workspace, gateway,
                List.of(new WriteFileTool(new LocalWorkspacePort())));
        String sessionId;
        String planId;
        String deviationRunId;
        try {
            first.setCollaborationMode(CollaborationMode.PLAN);
            first.runTurn("submit a plan");
            AgentSession planned = first.sessionRepository().find(first.sessionId()).orElseThrow();
            planId = planned.getCurrentPlanId();
            first.setCollaborationMode(CollaborationMode.BUILD);
            String answer = first.handoffPlan(planId);
            assertTrue(answer.contains("Plan Deviation"));
            AgentRun deviation = first.runRepository()
                    .findLatestRootByConversationId(first.sessionId()).orElseThrow();
            sessionId = first.sessionId();
            deviationRunId = deviation.getRunId();
            assertEquals(AgentRunStatus.STOPPED, deviation.getStatus());
            assertEquals("PLAN_DEVIATION", deviation.getStopReason());
        } finally {
            first.close();
        }

        CliSessionService resumed = resume(workspace, sessionId,
                FakeModelGateway.finalAnswer("must not recover as success"), List.of());
        try {
            assertFalse(resumed.recoveryRequired());
            try {
                resumed.recover();
                fail("terminal Plan Deviation must not enter Run Recovery");
            } catch (CliSessionService.OptionsException e) {
                assertTrue(e.getMessage().contains("no Recovery Required"));
            }
            AgentRun mutated = resumed.runRepository().find(deviationRunId).orElseThrow();
            mutated.setStatus(AgentRunStatus.RUNNING);
            try {
                resumed.runRepository().save(mutated);
                fail("terminal Plan Deviation must reject later run writes");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("terminal"));
            }
            AgentSession session = resumed.sessionRepository().find(sessionId).orElseThrow();
            assertEquals(planId, session.getCurrentPlanId());
            assertEquals(1, session.getPlans().get(0).getRevisions().size());
            assertNotEquals(deviationRunId, session.getLastProjectedRunId());
            assertEquals("kept", Files.readString(workspace.resolve("before-deviation.txt")));
        } finally {
            resumed.close();
        }
    }

    @Test
    public void recoveredPlanRunFencesTheOldAttemptAndDoesNotWriteLegacyFallback() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-recover-lease");
        Path observed = workspace.resolve("observed.txt");
        Files.writeString(observed, "before\n");
        InterruptedPlanRun interrupted = interruptAfterRead(workspace, observed, false);
        try {
            FileAttemptLeaseRepository leases = new FileAttemptLeaseRepository(workspace, mapper);
            CliSessionService resumed = resume(workspace, interrupted.sessionId,
                    planSubmissionGateway(), List.of(new ReadFileTool(new LocalWorkspacePort())));
            try {
                resumed.recover();
                AgentRun recovered = resumed.runRepository().find(interrupted.runId).orElseThrow();
                assertNotEquals(interrupted.attemptId, recovered.getCurrentAttemptId());
                AttemptLease next = leases.find(interrupted.runId).orElseThrow();
                assertNotEquals(interrupted.fence, next.getFence());
                assertEquals(recovered.getCurrentAttemptId(), next.getAttemptId());
                try {
                    leases.requireWritable(interrupted.runId, interrupted.fence);
                    fail("released fence must not write after a new Attempt starts");
                } catch (IllegalStateException e) {
                    assertTrue(e.getMessage().toLowerCase().contains("fence")
                            || e.getMessage().toLowerCase().contains("lease"));
                }
                assertNoLegacyRecoveryArtifacts(workspace);
            } finally {
                resumed.close();
            }
        } finally {
            interrupted.close();
        }
    }

    private InterruptedPlanRun interruptAfterRead(Path workspace, Path observed, boolean drift)
            throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        ModelGateway gateway = drift
                ? readThenDriftThenBlockGateway(observed, modelStarted, releaseModel)
                : readThenBlockGateway(modelStarted, releaseModel);
        CliSessionService first = service(workspace, gateway,
                List.of(new ReadFileTool(new LocalWorkspacePort())));
        first.setCollaborationMode(CollaborationMode.PLAN);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> turn = executor.submit(() -> first.runTurn("inspect observed.txt"));
        assertTrue(modelStarted.await(10, TimeUnit.SECONDS));
        AgentRun running = first.runRepository().findLatestRootByConversationId(first.sessionId())
                .orElseThrow();
        AgentContextSnapshot snapshot = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(running.getRunId()).orElseThrow().getContextSnapshot();
        assertFalse(snapshot.getEvidenceReceipts() == null || snapshot.getEvidenceReceipts().isEmpty());
        if (drift) {
            assertTrue(snapshot.isEvidenceDrift());
        }
        FileAttemptLeaseRepository leases = new FileAttemptLeaseRepository(workspace, mapper);
        AttemptLease lease = leases.find(running.getRunId()).orElseThrow();
        assertTrue(leases.release(running.getRunId(), lease.getFence()));
        return new InterruptedPlanRun(first, executor, turn, releaseModel,
                first.sessionId(), running.getRunId(), running.getCurrentAttemptId(), lease.getFence());
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, List<AgentTool> tools) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService.CliOptions opts = options(workspace, gateway);
        if (!tools.isEmpty()) {
            opts.approvalPolicy = "auto";
            agent.setApprovalPolicy("auto");
        }
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                CliLoopTestFixture.historyRepository(workspace, mapper),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of(), tools));
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway,
                                     List<AgentTool> tools) {
        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.resumeSessionId = sessionId;
        if (!tools.isEmpty()) {
            opts.approvalPolicy = "auto";
        }
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        if (!tools.isEmpty()) {
            agent.setApprovalPolicy("auto");
        }
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                CliLoopTestFixture.historyRepository(workspace, mapper),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of(), tools));
    }

    private CliSessionService.CliOptions options(Path workspace, ModelGateway gateway) {
        CliSessionService.CliOptions o = new CliSessionService.CliOptions();
        o.provider = "deepseek";
        o.model = "deepseek-v4-flash";
        o.baseUrl = "http://unused";
        o.apiKey = "";
        o.workspaceRoot = workspace.toString();
        o.approvalPolicy = "never";
        o.maxSteps = 8;
        o.maxNewTokens = 512;
        o.temperature = 0.2;
        o.topP = 0.9;
        o.timeoutSeconds = 30;
        o.modelGateway = gateway;
        return o;
    }

    private ModelGateway planSubmissionGateway() {
        return contentGateway(
                "<plan_submission>{\"title\":\"First plan\",\"body\":\"Use the observed repository state.\",\"dependencies\":[]}</plan_submission>");
    }

    private ModelGateway readThenBlockGateway(CountDownLatch started, CountDownLatch release) {
        AtomicInteger calls = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(toolRead("observed.txt"));
                }
                started.countDown();
                awaitRelease(release);
                return Mono.just(finalAnswer("should-not-finish"));
            }
        };
    }

    private ModelGateway readThenDriftThenBlockGateway(Path observed, CountDownLatch started,
                                                       CountDownLatch release) {
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
                    return Mono.just(toolRead("observed.txt"));
                }
                if (call == 1) {
                    try {
                        Files.writeString(observed, "after\n");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return Mono.just(toolRead("observed.txt"));
                }
                started.countDown();
                awaitRelease(release);
                return Mono.just(finalAnswer("should-not-finish"));
            }
        };
    }

    private ModelGateway readThenMutatingPlanGateway(Path observed) {
        AtomicInteger calls = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(toolRead("observed.txt"));
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

    private ModelGateway contentGateway(String content) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private static ModelChatResult toolRead(String path) {
        return ModelChatResult.builder()
                .content("<tool>{\"name\":\"read_file\",\"args\":{\"path\":\"" + path
                        + "\",\"start\":1,\"end\":1}}</tool>")
                .finishReason("stop")
                .actualModel("deepseek-v4-flash")
                .build();
    }

    private static ModelChatResult finalAnswer(String answer) {
        return ModelChatResult.builder()
                .content("<final>" + answer + "</final>")
                .finishReason("stop")
                .actualModel("deepseek-v4-flash")
                .build();
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release model");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model wait interrupted", e);
        }
    }

    private void assertNoLegacyRecoveryArtifacts(Path workspace) throws Exception {
        Path loom = workspace.resolve(".loom-code");
        try (Stream<Path> files = Files.walk(loom)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                assertFalse("legacy TaskCheckpoint artifact " + file,
                        name.toLowerCase().contains("taskcheckpoint")
                                || name.toLowerCase().contains("task_checkpoint"));
                String body = Files.readString(file);
                assertFalse("migration artifact written to " + file,
                        body.contains("Ledger migration from v2"));
                assertFalse("legacy ledger copy written to " + file,
                        body.contains("\"ledgerEntries\"") || body.contains("\"ledgerNextSequence\""));
                assertFalse("legacy schema fallback written to " + file,
                        body.contains("\"schemaVersion\" : 14")
                                || body.contains("\"schemaVersion\":14"));
            }
        }
    }

    private static final class InterruptedPlanRun implements AutoCloseable {
        private final CliSessionService first;
        private final ExecutorService executor;
        private final Future<String> turn;
        private final CountDownLatch releaseModel;
        private final String sessionId;
        private final String runId;
        private final String attemptId;
        private final String fence;

        private InterruptedPlanRun(CliSessionService first, ExecutorService executor,
                                   Future<String> turn, CountDownLatch releaseModel,
                                   String sessionId, String runId, String attemptId, String fence) {
            this.first = first;
            this.executor = executor;
            this.turn = turn;
            this.releaseModel = releaseModel;
            this.sessionId = sessionId;
            this.runId = runId;
            this.attemptId = attemptId;
            this.fence = fence;
        }

        @Override
        public void close() {
            releaseModel.countDown();
            turn.cancel(true);
            executor.shutdownNow();
            first.close();
        }
    }
}
