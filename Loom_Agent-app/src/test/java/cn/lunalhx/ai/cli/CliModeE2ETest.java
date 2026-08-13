package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.infrastructure.loom.WriteFileTool;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CliModeE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void startupModePersistsAndRunKeepsImmutableSnapshot() throws Exception {
        Path workspace = Files.createTempDirectory("cli-plan-mode");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService.CliOptions options = options(workspace, finalGateway(modelCalls));
        options.startupMode = CollaborationMode.PLAN;
        CliSessionService service = service(options, finalGateway(modelCalls));

        assertEquals(CollaborationMode.PLAN, service.sessionState().getCollaborationMode());
        service.runTurn("inspect only");

        AgentRun run = service.runRepository()
                .findLatestRootByConversationId(service.sessionId()).orElseThrow();
        assertEquals(CollaborationMode.PLAN, run.getRunModeSnapshot());
        assertEquals(CollaborationMode.PLAN,
                service.sessionRepository().find(service.sessionId()).orElseThrow()
                        .getCollaborationMode());
        assertEquals(1, modelCalls.get());
        service.close();
    }

    @Test
    public void modeControlPersistsWithoutStartingARun() throws Exception {
        Path workspace = Files.createTempDirectory("cli-mode-control");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService service = service(options(workspace, finalGateway(modelCalls)),
                finalGateway(modelCalls));
        String sessionId = service.sessionId();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        assertTrue(CliMain.handleControl(service, "/mode plan",
                new PrintStream(bytes, true, StandardCharsets.UTF_8)));

        assertEquals(sessionId, service.sessionId());
        assertEquals(CollaborationMode.PLAN, service.sessionState().getCollaborationMode());
        assertEquals(CollaborationMode.PLAN,
                service.sessionRepository().find(sessionId).orElseThrow().getCollaborationMode());
        assertEquals(0, modelCalls.get());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("mode: plan"));
        service.close();
    }

    @Test
    public void explicitModeOnResumeChangesSessionBeforeTheNextRun() throws Exception {
        Path workspace = Files.createTempDirectory("cli-mode-resume");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService first = service(options(workspace, finalGateway(modelCalls)),
                finalGateway(modelCalls));
        String sessionId = first.sessionId();
        first.close();

        CliSessionService.CliOptions resume = options(workspace, finalGateway(modelCalls));
        resume.resumeSessionId = sessionId;
        resume.startupMode = CollaborationMode.PLAN;
        CliSessionService resumed = service(resume, finalGateway(modelCalls));
        assertEquals(CollaborationMode.PLAN, resumed.sessionState().getCollaborationMode());
        assertEquals(0, modelCalls.get());
        resumed.close();
    }

    @Test
    public void planWriteCallIsDeniedBeforeExecutionAndLeavesRepositoryByteIdentical() throws Exception {
        Path workspace = Files.createTempDirectory("cli-plan-unchanged");
        Path target = workspace.resolve("protected.txt");
        Files.writeString(target, "original bytes\n");
        byte[] before = Files.readAllBytes(target);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService.CliOptions options = options(workspace,
                writeThenFinalGateway(modelCalls));
        options.startupMode = CollaborationMode.PLAN;
        CliSessionService service = service(options, writeThenFinalGateway(modelCalls),
                List.of(new WriteFileTool(new LocalWorkspacePort())));

        assertEquals("done", service.runTurn("do not change the repository"));
        assertEquals(2, modelCalls.get());
        assertEquals(CollaborationMode.PLAN,
                service.runRepository().findLatestRootByConversationId(service.sessionId())
                        .orElseThrow().getRunModeSnapshot());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(target)));
        service.close();
    }

    @Test
    public void modeChangedDuringRootRunDoesNotChangeItsSnapshot() throws Exception {
        Path workspace = Files.createTempDirectory("cli-mode-concurrent");
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CliSessionService.CliOptions options = options(workspace,
                blockingFinalGateway(modelStarted, releaseModel));
        options.startupMode = CollaborationMode.PLAN;
        CliSessionService service = service(options,
                blockingFinalGateway(modelStarted, releaseModel));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> service.runTurn("snapshot this mode"));
            assertTrue(modelStarted.await(3, TimeUnit.SECONDS));
            service.setCollaborationMode(CollaborationMode.BUILD);
            releaseModel.countDown();
            assertEquals("done", future.get(5, TimeUnit.SECONDS));

            AgentRun run = service.runRepository()
                    .findLatestRootByConversationId(service.sessionId()).orElseThrow();
            assertEquals(CollaborationMode.PLAN, run.getRunModeSnapshot());
            assertEquals(CollaborationMode.BUILD, service.collaborationMode());
        } finally {
            releaseModel.countDown();
            executor.shutdownNow();
            service.close();
        }
    }

    @Test
    public void buildAutoApprovalStillExecutesRepositoryMutation() throws Exception {
        Path workspace = Files.createTempDirectory("cli-build-write");
        Path target = workspace.resolve("protected.txt");
        Files.writeString(target, "original bytes\n");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService.CliOptions options = options(workspace,
                writeThenFinalGateway(modelCalls));
        options.approvalPolicy = "auto";
        CliSessionService service = service(options, writeThenFinalGateway(modelCalls),
                List.of(new WriteFileTool(new LocalWorkspacePort())));

        assertEquals("done", service.runTurn("change the repository"));
        assertEquals("changed", Files.readString(target));
        assertEquals(CollaborationMode.BUILD,
                service.runRepository().findLatestRootByConversationId(service.sessionId())
                        .orElseThrow().getRunModeSnapshot());
        service.close();
    }

    private CliSessionService service(CliSessionService.CliOptions options, ModelGateway gateway) {
        return service(options, gateway, java.util.List.of());
    }

    private CliSessionService service(CliSessionService.CliOptions options, ModelGateway gateway,
                                      java.util.List<AgentTool> tools) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(Path.of(options.workspaceRoot));
        AgentSessionRepository sessions = new FileAgentSessionRepository(Path.of(options.workspaceRoot), mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(Path.of(options.workspaceRoot), mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(Path.of(options.workspaceRoot), mapper);
        FileTraceRecorder traces = new FileTraceRecorder(Path.of(options.workspaceRoot), mapper);
        AgentLoopService loop = CliLoopTestFixture.build(Path.of(options.workspaceRoot), mapper,
                gateway, agent, java.util.List.of(), tools);
        return new CliSessionService(options, mapper, agent, new ModelRuntimeProperties(),
                sessions, runs, checkpoints, CliLoopTestFixture.historyRepository(Path.of(options.workspaceRoot), mapper), traces, loop);
    }

    private CliSessionService.CliOptions options(Path workspace, ModelGateway gateway) {
        CliSessionService.CliOptions options = new CliSessionService.CliOptions();
        options.provider = "deepseek";
        options.model = "deepseek-v4-flash";
        options.baseUrl = "http://unused";
        options.apiKey = "";
        options.workspaceRoot = workspace.toString();
        options.approvalPolicy = "never";
        options.maxSteps = 4;
        options.maxNewTokens = 256;
        options.timeoutSeconds = 30;
        options.modelGateway = gateway;
        return options;
    }

    private ModelGateway finalGateway(AtomicInteger calls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content("<final>done</final>")
                        .finishReason("stop")
                        .actualModel("test")
                        .build());
            }
        };
    }

    private ModelGateway writeThenFinalGateway(AtomicInteger calls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"write_file\",\"args\":{\"path\":\"protected.txt\",\"content\":\"changed\"}}</tool>")
                            .finishReason("stop")
                            .actualModel("test")
                            .build());
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<final>done</final>")
                        .finishReason("stop")
                        .actualModel("test")
                        .build());
            }
        };
    }

    private ModelGateway blockingFinalGateway(CountDownLatch started, CountDownLatch release) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<final>done</final>")
                        .finishReason("stop")
                        .actualModel("test")
                        .build());
            }
        };
    }
}
