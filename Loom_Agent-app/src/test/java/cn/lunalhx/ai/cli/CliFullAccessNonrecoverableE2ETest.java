package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AttemptLease;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.agent.model.entity.ToolExecutionMarker;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.loom.ReadFileTool;
import cn.lunalhx.ai.infrastructure.loom.WriteFileTool;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileAttemptLeaseRepository;
import cn.lunalhx.ai.infrastructure.store.FileConversationHistoryRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ticket 13 seam: Full Access Runs are inspect/abandon-only after process
 * exit, cannot recover or downgrade to sandbox, and active exit offers
 * continue or Abandon rather than Suspend.
 */
public class CliFullAccessNonrecoverableE2ETest {

    private static final String WRITE_CONTENT = "full-access-bytes";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void interruptedFullAccessRunIsInspectAbandonOnlyAndRecoverIsUnavailable()
            throws Exception {
        Path workspace = Files.createTempDirectory("e2e-fa-blocked");
        InterruptedRun interrupted = interruptNoToolRun(workspace);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId, false,
                countingFinalAnswerGateway("must not recover", modelCalls));
        try {
            assertTrue(resumed.recoveryRequired());
            assertTrue("reason=" + resumed.recoveryBlockedReason(), resumed.recoveryBlocked());
            assertTrue(resumed.recoveryBlockedReason().toLowerCase().contains("full access"));

            try {
                resumed.runTurn("ordinary request while blocked");
                fail("ordinary request must stay blocked");
            } catch (CliSessionService.OptionsException e) {
                assertTrue(e.getMessage().contains("Recovery Blocked"));
                assertTrue(e.getMessage().contains("/abandon"));
                assertFalse(e.getMessage().contains("/recover"));
            }
            assertEquals(0, modelCalls.get());

            ByteArrayOutputStream recoverOut = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/recover",
                    new PrintStream(recoverOut, true, StandardCharsets.UTF_8)));
            assertTrue(recoverOut.toString(StandardCharsets.UTF_8).contains("Recovery Blocked"));
            assertEquals(0, modelCalls.get());

            AgentRun stillOpen = resumed.runRepository().find(interrupted.runId).orElseThrow();
            assertEquals(AgentRunStatus.RUNNING, stillOpen.getStatus());
            assertEquals(interrupted.attemptId, stillOpen.getCurrentAttemptId());

            ByteArrayOutputStream abandonOut = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/abandon",
                    new PrintStream(abandonOut, true, StandardCharsets.UTF_8)));
            assertTrue(abandonOut.toString(StandardCharsets.UTF_8)
                    .contains("abandoned: " + interrupted.runId));
            assertEquals(0, modelCalls.get());
            assertFalse(resumed.recoveryRequired());
            assertEquals(AgentRunStatus.ABANDONED,
                    resumed.runRepository().find(interrupted.runId).orElseThrow().getStatus());
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void resumingWithFullAccessStillCannotRecoverOrDowngradeTheSameRun() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-fa-no-restore");
        InterruptedRun interrupted = interruptNoToolRun(workspace);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId, true,
                countingFinalAnswerGateway("must not restore full access", modelCalls));
        try {
            assertTrue(resumed.fullAccessActive());
            assertTrue(resumed.recoveryBlocked());
            assertTrue(resumed.recoveryBlockedReason().toLowerCase().contains("full access"));
            try {
                resumed.recover();
                fail("Full Access must not restore the same Run");
            } catch (CliSessionService.OptionsException e) {
                assertTrue(e.getMessage().contains("Recovery Blocked"));
            }
            assertEquals(0, modelCalls.get());
            AgentRun stillOpen = resumed.runRepository().find(interrupted.runId).orElseThrow();
            assertEquals(interrupted.attemptId, stillOpen.getCurrentAttemptId());
            assertEquals(ExecutionProfileKind.DANGER_FULL_ACCESS,
                    new FileAgentCheckpointRepository(workspace, mapper)
                            .latest(interrupted.runId).orElseThrow()
                            .getContextSnapshot().getExecutionProfileKind());
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void fullAccessInterruptionKeepsHistoryChangesAndFactsOnASingleAuthorityRun()
            throws Exception {
        Path workspace = Files.createTempDirectory("e2e-fa-facts");
        HangingTool hanging = HangingTool.of(new ReadFileTool(new LocalWorkspacePort()));
        List<AgentTool> tools = List.of(new WriteFileTool(new LocalWorkspacePort()), hanging.delegate());
        InterruptedRun interrupted = interruptAfterWrite(workspace, tools, hanging);
        try {
            assertEquals(WRITE_CONTENT, Files.readString(workspace.resolve("notes.txt")));
            ConversationHistoryDocument history = historyRepository(workspace)
                    .find(interrupted.sessionId).orElseThrow();
            assertTrue(history.getEntries().stream()
                    .anyMatch(entry -> entry.stableType() == ConversationEntryType.USER_TASK));
            assertTrue(history.getEntries().stream()
                    .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));
            AgentCheckpoint checkpoint = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(interrupted.runId).orElseThrow();
            assertEquals(ExecutionProfileKind.DANGER_FULL_ACCESS,
                    checkpoint.getContextSnapshot().getExecutionProfileKind());
            ToolExecutionMarker window = checkpoint.getContextSnapshot().getExecutionWindow();
            assertNotNull(window);
            assertEquals("read_file", window.getToolName());

            AtomicInteger modelCalls = new AtomicInteger();
            CliSessionService resumed = resume(workspace, interrupted.sessionId, false,
                    countingFinalAnswerGateway("after abandon", modelCalls), tools);
            try {
                assertTrue(resumed.recoveryBlocked());
                assertEquals(WRITE_CONTENT, Files.readString(workspace.resolve("notes.txt")));
                assertTrue(CliMain.handleControl(resumed, "/abandon",
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)));
                assertEquals(WRITE_CONTENT, Files.readString(workspace.resolve("notes.txt")));
                ConversationHistoryDocument after = historyRepository(workspace)
                        .find(interrupted.sessionId).orElseThrow();
                assertEquals(history.getEntries().get(0).content(), after.getEntries().get(0).content());
                AgentCheckpoint abandoned = new FileAgentCheckpointRepository(workspace, mapper)
                        .latest(interrupted.runId).orElseThrow();
                assertEquals("read_file",
                        abandoned.getContextSnapshot().getExecutionWindow().getToolName());
                assertEquals(ExecutionProfileKind.DANGER_FULL_ACCESS,
                        abandoned.getContextSnapshot().getExecutionProfileKind());

                assertEquals("after abandon", resumed.runTurn("new work after full-access abandon"));
                AgentRun next = resumed.runRepository()
                        .findLatestRootByConversationId(interrupted.sessionId).orElseThrow();
                assertNotEquals(interrupted.runId, next.getRunId());
                assertEquals(ExecutionProfileKind.BUILD_SANDBOX,
                        new FileAgentCheckpointRepository(workspace, mapper)
                                .latest(next.getRunId()).orElseThrow()
                                .getContextSnapshot().getExecutionProfileKind());
            } finally {
                resumed.close();
            }
        } finally {
            interrupted.close();
        }
    }

    @Test
    public void activeFullAccessRunRejectsSuspendAndAllowsAbandon() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-fa-active-exit");
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CliSessionService first = service(workspace, true,
                blockingFinalGateway(modelStarted, releaseModel, "should-not-finish"), List.of());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> turn = executor.submit(() -> first.runTurn("finish the original task"));
        try {
            assertTrue(modelStarted.await(10, TimeUnit.SECONDS));
            AgentRun running = first.runRepository().findLatestRootByConversationId(first.sessionId())
                    .orElseThrow();
            assertTrue(first.hasActiveRun());
            assertFalse(first.hasActiveRecoverableRun());
            try {
                first.suspend();
                fail("Full Access must not offer Suspend");
            } catch (CliSessionService.OptionsException e) {
                assertTrue(e.getMessage().contains("Full Access"));
            }
            assertTrue(first.hasActiveRun());
            String abandoned = first.abandon();
            assertTrue(abandoned.startsWith("abandoned:"));
            assertEquals("abandoned: " + running.getRunId(), turn.get(10, TimeUnit.SECONDS));
            assertEquals(AgentRunStatus.ABANDONED,
                    first.runRepository().find(running.getRunId()).orElseThrow().getStatus());
        } finally {
            releaseModel.countDown();
            turn.cancel(true);
            executor.shutdownNow();
            first.close();
        }
    }

    private InterruptedRun interruptNoToolRun(Path workspace) throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CliSessionService first = service(workspace, true,
                blockingFinalGateway(modelStarted, releaseModel, "unused"), List.of());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> turn = executor.submit(() -> first.runTurn("finish the original task"));
        assertTrue(modelStarted.await(10, TimeUnit.SECONDS));
        return releaseLease(workspace, first, executor, turn, releaseModel);
    }

    private InterruptedRun interruptAfterWrite(Path workspace, List<AgentTool> tools,
                                               HangingTool hanging) throws Exception {
        CliSessionService first = service(workspace, true,
                writeThenReadGateway("notes.txt", WRITE_CONTENT), tools);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> turn = executor.submit(() -> first.runTurn("write notes then inspect"));
        assertTrue(hanging.started.await(10, TimeUnit.SECONDS));
        return releaseLease(workspace, first, executor, turn, hanging.release);
    }

    private InterruptedRun releaseLease(Path workspace, CliSessionService first,
                                        ExecutorService executor, Future<String> turn,
                                        CountDownLatch releaseModel) throws Exception {
        AgentRun running = first.runRepository().findLatestRootByConversationId(first.sessionId())
                .orElseThrow();
        assertEquals(AgentRunStatus.RUNNING, running.getStatus());
        FileAttemptLeaseRepository leases = new FileAttemptLeaseRepository(workspace, mapper);
        AttemptLease lease = leases.find(running.getRunId()).orElseThrow();
        assertTrue(leases.release(running.getRunId(), lease.getFence()));
        return new InterruptedRun(first, executor, turn, releaseModel,
                first.sessionId(), running.getRunId(), running.getCurrentAttemptId());
    }

    private CliSessionService service(Path workspace, boolean fullAccess, ModelGateway gateway,
                                      List<AgentTool> tools) {
        CliSessionService.CliOptions opts = options(workspace, gateway, fullAccess);
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of(), tools));
    }

    private CliSessionService resume(Path workspace, String sessionId, boolean fullAccess,
                                     ModelGateway gateway) {
        return resume(workspace, sessionId, fullAccess, gateway, List.of());
    }

    private CliSessionService resume(Path workspace, String sessionId, boolean fullAccess,
                                     ModelGateway gateway, List<AgentTool> tools) {
        CliSessionService.CliOptions opts = options(workspace, gateway, fullAccess);
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of(), tools));
    }

    private FileConversationHistoryRepository historyRepository(Path workspace) {
        return CliLoopTestFixture.historyRepository(workspace, mapper);
    }

    private CliSessionService.CliOptions options(Path workspace, ModelGateway gateway,
                                                 boolean fullAccess) {
        CliSessionService.CliOptions options = new CliSessionService.CliOptions();
        options.provider = "deepseek";
        options.model = "deepseek-v4-flash";
        options.baseUrl = "http://unused";
        options.apiKey = "";
        options.workspaceRoot = workspace.toString();
        options.approvalPolicy = "never";
        options.maxSteps = 6;
        options.maxNewTokens = 256;
        options.timeoutSeconds = 30;
        options.modelGateway = gateway;
        options.fullAccess = fullAccess;
        return options;
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

    private ModelGateway writeThenReadGateway(String path, String content) {
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
                            .content("<tool>{\"name\":\"write_file\",\"args\":{\"path\":\""
                                    + path + "\",\"content\":\"" + content + "\"}}</tool>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
                return Mono.just(ModelChatResult.builder()
                        .content("<tool>{\"name\":\"read_file\",\"args\":{\"path\":\""
                                + path + "\"}}</tool>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private static final class HangingTool implements AgentTool {
        private final AgentTool delegate;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private HangingTool(AgentTool delegate) {
            this.delegate = delegate;
        }

        static HangingTool of(AgentTool delegate) {
            return new HangingTool(delegate);
        }

        AgentTool delegate() {
            return this;
        }

        @Override
        public cn.lunalhx.ai.domain.tool.model.ToolSpec spec() {
            return delegate.spec();
        }

        @Override
        public ToolResult call(ToolCall call) {
            started.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to cancel tool");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("tool interrupted", e);
            }
            return delegate.call(call);
        }
    }

    private static final class InterruptedRun implements AutoCloseable {
        private final CliSessionService first;
        private final ExecutorService executor;
        private final Future<String> turn;
        private final CountDownLatch releaseModel;
        private final String sessionId;
        private final String runId;
        private final String attemptId;

        private InterruptedRun(CliSessionService first, ExecutorService executor,
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
            try {
                first.close();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
