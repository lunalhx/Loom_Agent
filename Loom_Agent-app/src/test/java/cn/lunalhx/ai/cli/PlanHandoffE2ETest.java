package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateRequest;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.entity.PlanBinding;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import cn.lunalhx.ai.test.FakeModelGateway;
import cn.lunalhx.ai.infrastructure.loom.WriteFileTool;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlanHandoffE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void handoffStartsBoundBuildRunAtFreshPlanHead() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-handoff");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService session = service(workspace, gateway(modelCalls));
        try {
            session.setCollaborationMode(CollaborationMode.PLAN);
            session.runTurn("submit a plan");
            AgentSession planned = session.sessionRepository().find(session.sessionId()).orElseThrow();
            String planId = planned.getCurrentPlanId();

            session.setCollaborationMode(CollaborationMode.BUILD);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(session, "/plan handoff " + planId,
                    new PrintStream(output, true, StandardCharsets.UTF_8)));

            AgentRun run = session.runRepository()
                    .findLatestRootByConversationId(session.sessionId()).orElseThrow();
            assertEquals(CollaborationMode.BUILD, run.getRunModeSnapshot());
            assertNotNull(run.getPlanBinding());
            assertEquals(planId, run.getPlanBinding().getPlanId());
            assertEquals(Integer.valueOf(1), run.getPlanBinding().getRevision());
            assertEquals(planned.getPlans().get(0).currentRevision().getContentDigest(),
                    run.getPlanBinding().getPlanDocumentDigest());
            assertTrue(run.getPlanBinding().getPlanBasisIdentity() != null
                    && !run.getPlanBinding().getPlanBasisIdentity().isBlank());
            assertTrue(run.getPlanBinding().getBody().contains("Start with research"));
            assertEquals(2, modelCalls.get());
        } finally {
            session.close();
        }
    }

    @Test
    public void handoffRejectsIneligibleModeAndDoesNotStartRun() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-handoff-mode");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService session = service(workspace, gateway(modelCalls));
        try {
            session.setCollaborationMode(CollaborationMode.PLAN);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(session, "/plan handoff",
                    new PrintStream(output, true, StandardCharsets.UTF_8)));

            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Build Mode"));
            assertTrue(session.runRepository().findByConversationId(session.sessionId()).isEmpty());
            assertEquals(0, modelCalls.get());
        } finally {
            session.close();
        }
    }

    @Test
    public void handoffRejectsMissingUnknownAndStalePlansBeforeRunStart() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-handoff-invalid");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService session = service(workspace, gateway(modelCalls));
        try {
            ByteArrayOutputStream missing = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(session, "/plan handoff",
                    new PrintStream(missing, true, StandardCharsets.UTF_8)));
            assertTrue(missing.toString(StandardCharsets.UTF_8).contains("no Current Plan"));

            ByteArrayOutputStream unknown = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(session, "/plan handoff plan_missing",
                    new PrintStream(unknown, true, StandardCharsets.UTF_8)));
            assertTrue(unknown.toString(StandardCharsets.UTF_8).contains("unknown Plan"));

            session.setCollaborationMode(CollaborationMode.PLAN);
            session.runTurn("submit a plan");
            AgentSession persisted = session.sessionRepository().find(session.sessionId()).orElseThrow();
            persisted.getPlans().get(0).currentRevision().setPlanBasis(List.of(
                    EvidenceReceipt.builder().build()));
            session.sessionRepository().save(persisted);
            session.setCollaborationMode(CollaborationMode.BUILD);

            ByteArrayOutputStream stale = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(session, "/plan handoff",
                    new PrintStream(stale, true, StandardCharsets.UTF_8)));
            assertTrue(stale.toString(StandardCharsets.UTF_8).contains("stale"));
            assertEquals(1, session.runRepository().findByConversationId(session.sessionId()).size());
            assertEquals(1, modelCalls.get());
        } finally {
            session.close();
        }
    }

    @Test
    public void laterPlanRevisionDoesNotChangeActiveOrRestoredHandoffBinding() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-handoff-immutable");
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CliSessionService session = service(workspace,
                blockingBuildGateway(modelStarted, releaseModel));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CliSessionService restored = null;
        try {
            session.setCollaborationMode(CollaborationMode.PLAN);
            session.runTurn("submit revision one");
            AgentSession planned = session.sessionRepository().find(session.sessionId()).orElseThrow();
            String planId = planned.getCurrentPlanId();
            session.setCollaborationMode(CollaborationMode.BUILD);

            Future<String> handoff = executor.submit(() -> session.handoffPlan(planId));
            assertTrue(modelStarted.await(10, TimeUnit.SECONDS));
            AgentRun active = session.runRepository().findByConversationId(session.sessionId()).stream()
                    .filter(run -> run.getPlanBinding() != null)
                    .findFirst().orElseThrow();
            assertEquals(Integer.valueOf(1), active.getPlanBinding().getRevision());
            assertEquals(Integer.valueOf(1), new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(active.getRunId()).orElseThrow().getContextSnapshot()
                    .getPlanBinding().getRevision());

            restored = service(workspace, revisionTwoGateway(), session.sessionId());
            restored.setCollaborationMode(CollaborationMode.PLAN);
            restored.runTurn("create revision two");
            AgentSession revised = restored.sessionRepository().find(session.sessionId()).orElseThrow();
            assertEquals(Integer.valueOf(2), revised.getPlans().get(0).currentRevision().getRevision());

            AgentRun restoredView = restored.runRepository().find(active.getRunId()).orElseThrow();
            assertEquals(Integer.valueOf(1), restoredView.getPlanBinding().getRevision());
            assertEquals(active.getPlanBinding().getPlanDocumentDigest(),
                    restoredView.getPlanBinding().getPlanDocumentDigest());
            assertEquals(Integer.valueOf(1),
                    restoredView.getPlanBinding().getRevision());

            releaseModel.countDown();
            assertEquals("build complete", handoff.get(30, TimeUnit.SECONDS));
        } finally {
            releaseModel.countDown();
            executor.shutdownNow();
            if (restored != null) {
                restored.close();
            }
            session.close();
        }
    }

    @Test
    public void delegateRequestInheritsTheSameImmutablePlanBinding() {
        PlanBinding binding = new PlanBinding("plan_1", 1, "doc-digest", "basis-digest",
                "Title", "Objective and validation", List.of("dependency"));
        AgentContext context = new AgentContext();
        context.setRunId("run_1");
        context.setRootRunId("run_1");
        context.setSessionId("session_1");
        context.setCollaborationMode(CollaborationMode.BUILD);
        context.setPlanBinding(binding);
        context.setMaxSteps(4);
        context.setMaxAttempts(4);
        context.setToolSpecs(List.of(ToolSpec.builder().name("read_file").build()));

        DelegateRequest request = DelegateRequest.fromParent(context,
                new AgentRuntimeProperties(), null);

        assertEquals(binding, request.getPlanBinding());
        assertEquals("plan_1", request.getPlanBinding().getPlanId());
        assertEquals(Integer.valueOf(1), request.getPlanBinding().getRevision());
    }

    @Test
    public void deviationStopsBoundRunAndPreservesWorkspaceAndPlan() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-deviation");
        FakeModelGateway gateway = new FakeModelGateway(List.of(
                "<plan_submission>{\"title\":\"First plan\",\"body\":\"Write the requested feature.\",\"dependencies\":[]}</plan_submission>",
                "<tool>{\"name\":\"write_file\",\"args\":{\"path\":\"before-deviation.txt\",\"content\":\"kept\"}}</tool>",
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"Continuing requires changing files outside the bound scope.\"},\"workspace_changes\":[{\"path\":\"before-deviation.txt\",\"operation\":\"created\",\"summary\":\"Created before discovering the scope conflict.\"}]}</plan_deviation>"));
        CliSessionService session = service(workspace, gateway, null,
                List.of(new WriteFileTool(new LocalWorkspacePort())));
        try {
            session.setCollaborationMode(CollaborationMode.PLAN);
            session.runTurn("submit a plan");
            AgentSession planned = session.sessionRepository().find(session.sessionId()).orElseThrow();
            String planId = planned.getCurrentPlanId();

            session.setCollaborationMode(CollaborationMode.BUILD);
            String answer = session.handoffPlan(planId);

            assertTrue(answer.contains("Plan Deviation"));
            assertEquals(3, gateway.callCount());
            assertEquals("kept", Files.readString(workspace.resolve("before-deviation.txt")));

            AgentRun run = session.runRepository()
                    .findLatestRootByConversationId(session.sessionId()).orElseThrow();
            assertEquals(AgentRunStatus.STOPPED, run.getStatus());
            assertEquals("PLAN_DEVIATION", run.getStopReason());
            assertNotNull(run.getPlanDeviation());
            assertEquals("scope", run.getPlanDeviation().getConflict().getKind());
            assertEquals("before-deviation.txt",
                    run.getPlanDeviation().getWorkspaceChanges().get(0).getPath());

            AgentSession after = session.sessionRepository().find(session.sessionId()).orElseThrow();
            assertEquals(planId, after.getCurrentPlanId());
            assertEquals(1, after.getPlans().get(0).getRevisions().size());
            assertEquals(Integer.valueOf(1), after.getPlans().get(0).currentRevision().getRevision());
        } finally {
            session.close();
        }
    }

    @Test
    public void fabricatedRootBindingFailsClosed() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-deviation-fabricated-binding");
        FakeModelGateway gateway = deviationGateway();
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                List.of());

        loop.ask(AgentQuestion.builder()
                .runId("fabricated-root")
                .rootRunId("fabricated-root")
                .sessionId("session-1")
                .conversationId("conversation-1")
                .workspace(workspace.toString())
                .question("report a deviation")
                .maxSteps(4)
                .maxAttempts(4)
                .approvalPolicy("never")
                .collaborationMode(CollaborationMode.BUILD)
                .planBinding(new PlanBinding("plan_1", 1, "doc", "basis",
                        "Title", "Objective", List.of()))
                .build()).collectList().block();

        AgentRun run = new FileAgentRunRepository(workspace, mapper)
                .find("fabricated-root").orElseThrow();
        assertEquals(AgentRunStatus.FAILED, run.getStatus());
        assertEquals("RUNTIME_SCHEMA_MISMATCH", run.getStopReason());
        assertNull(run.getPlanDeviation());
    }

    @Test
    public void planDeviationRunCannotBeReentered() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-deviation-reentry");
        FakeModelGateway gateway = new FakeModelGateway(List.of(
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"scope changed\"},\"workspace_changes\":[]}</plan_deviation>",
                "<final>must not run</final>"));
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                List.of());
        PlanBinding binding = PlanBinding.fromHandoff("plan_1", 1, "doc", "basis",
                "Title", "Objective", List.of());
        AgentQuestion question = AgentQuestion.builder()
                .runId("terminal-root")
                .rootRunId("terminal-root")
                .sessionId("session-1")
                .conversationId("conversation-1")
                .workspace(workspace.toString())
                .question("report a deviation")
                .maxSteps(4)
                .maxAttempts(4)
                .approvalPolicy("never")
                .collaborationMode(CollaborationMode.BUILD)
                .planBinding(binding)
                .build();

        loop.ask(question).collectList().block();
        loop.ask(question).collectList().block();

        assertEquals(1, gateway.callCount());
        AgentRun run = new FileAgentRunRepository(workspace, mapper)
                .find("terminal-root").orElseThrow();
        assertEquals(AgentRunStatus.STOPPED, run.getStatus());
        assertEquals("PLAN_DEVIATION", run.getStopReason());
    }

    @Test
    public void deviationFromUnboundBuildRunFailsClosed() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-deviation-unbound");
        FakeModelGateway gateway = deviationGateway();
        CliSessionService session = service(workspace, gateway);
        try {
            String answer = session.runTurn("report a deviation");

            assertTrue(answer.contains("Plan Deviation rejected"));
            AgentRun run = session.runRepository()
                    .findLatestRootByConversationId(session.sessionId()).orElseThrow();
            assertEquals(AgentRunStatus.FAILED, run.getStatus());
            assertEquals("RUNTIME_SCHEMA_MISMATCH", run.getStopReason());
            assertNull(run.getPlanDeviation());
        } finally {
            session.close();
        }
    }

    @Test
    public void deviationFromPlanRunFailsClosed() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-deviation-plan");
        FakeModelGateway gateway = deviationGateway();
        CliSessionService session = service(workspace, gateway);
        try {
            session.setCollaborationMode(CollaborationMode.PLAN);
            String answer = session.runTurn("report a deviation");

            assertTrue(answer.contains("Plan Deviation rejected"));
            AgentRun run = session.runRepository()
                    .findLatestRootByConversationId(session.sessionId()).orElseThrow();
            assertEquals(AgentRunStatus.FAILED, run.getStatus());
            assertEquals("RUNTIME_SCHEMA_MISMATCH", run.getStopReason());
            assertNull(run.getPlanDeviation());
        } finally {
            session.close();
        }
    }

    @Test
    public void deviationFromChildRunFailsClosed() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-plan-deviation-child");
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        FakeModelGateway gateway = deviationGateway();
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                List.of());
        PlanBinding binding = new PlanBinding("plan_1", 1, "doc", "basis",
                "Title", "Objective", List.of());

        loop.ask(AgentQuestion.builder()
                .runId("child-run")
                .parentRunId("parent-run")
                .rootRunId("root-run")
                .sessionId("session-1")
                .conversationId("conversation-1")
                .workspace(workspace.toString())
                .question("report a deviation")
                .maxSteps(4)
                .maxAttempts(4)
                .approvalPolicy("never")
                .collaborationMode(CollaborationMode.BUILD)
                .planBinding(binding)
                .build()).collectList().block();

        AgentRun child = new FileAgentRunRepository(workspace, mapper)
                .find("child-run").orElseThrow();
        assertEquals(AgentRunStatus.FAILED, child.getStatus());
        assertEquals("RUNTIME_SCHEMA_MISMATCH", child.getStopReason());
        assertNull(child.getPlanDeviation());
    }

    private FakeModelGateway deviationGateway() {
        return new FakeModelGateway(List.of(
                "<plan_deviation>{\"conflict\":{\"kind\":\"scope\",\"summary\":\"scope changed\"},\"workspace_changes\":[]}</plan_deviation>"));
    }

    private CliSessionService service(Path workspace, ModelGateway gateway) {
        return service(workspace, gateway, null, List.of());
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, String resumeSessionId) {
        return service(workspace, gateway, resumeSessionId, List.of());
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, String resumeSessionId,
                                      List<cn.lunalhx.ai.domain.tool.adapter.port.AgentTool> tools) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                java.util.List.of(), tools);
        CliSessionService.CliOptions options = new CliSessionService.CliOptions();
        options.provider = "deepseek";
        options.model = "deepseek-v4-flash";
        options.baseUrl = "http://unused";
        options.apiKey = "";
        options.workspaceRoot = workspace.toString();
        options.approvalPolicy = tools.isEmpty() ? "never" : "auto";
        options.maxSteps = 6;
        options.maxNewTokens = 512;
        options.temperature = 0.2;
        options.topP = 0.9;
        options.timeoutSeconds = 30;
        options.modelGateway = gateway;
        options.resumeSessionId = resumeSessionId;
        return new CliSessionService(options, mapper, agent, new ModelRuntimeProperties(),
                sessions, runs, checkpoints, traces, loop);
    }

    private ModelGateway blockingBuildGateway(CountDownLatch modelStarted,
                                              CountDownLatch releaseModel) {
        return new ModelGateway() {
            private final AtomicInteger calls = new AtomicInteger();

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
                modelStarted.countDown();
                try {
                    releaseModel.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<final>build complete</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway revisionTwoGateway() {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content("<plan_submission>{\"title\":\"Revision two\",\"body\":\"Keep the research constraint.\",\"dependencies\":[]}</plan_submission>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway gateway(AtomicInteger calls) {
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
                assertTrue(prompt.getSystemPrompt().contains("Start with research"));
                return Mono.just(ModelChatResult.builder()
                        .content("<final>build complete</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }
}
