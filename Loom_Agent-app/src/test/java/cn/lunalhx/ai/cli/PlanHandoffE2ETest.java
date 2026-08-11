package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.DelegateRequest;
import cn.lunalhx.ai.domain.agent.model.entity.EvidenceReceipt;
import cn.lunalhx.ai.domain.agent.model.entity.PlanBinding;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
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

    private CliSessionService service(Path workspace, ModelGateway gateway) {
        return service(workspace, gateway, null);
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, String resumeSessionId) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                java.util.List.of());
        CliSessionService.CliOptions options = new CliSessionService.CliOptions();
        options.provider = "deepseek";
        options.model = "deepseek-v4-flash";
        options.baseUrl = "http://unused";
        options.apiKey = "";
        options.workspaceRoot = workspace.toString();
        options.approvalPolicy = "never";
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
