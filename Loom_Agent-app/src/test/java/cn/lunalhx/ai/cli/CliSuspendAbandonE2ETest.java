package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.agent.model.entity.ToolExecutionMarker;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.loom.ReadFileTool;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ticket 11 seam: CLI Session suspend/abandon of an active sandboxed Run, and
 * terminal Run immutability for later History, checkpoint, and recovery writes.
 */
public class CliSuspendAbandonE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void suspendStopsAttemptKeepsRunNonTerminalAndEntersRecoveryRequired()
            throws Exception {
        Path workspace = Files.createTempDirectory("e2e-suspend");
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CliSessionService first = service(workspace,
                blockingFinalGateway(modelStarted, releaseModel, "should-not-finish"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> turn = executor.submit(() -> first.runTurn("finish the original task"));
        try {
            assertTrue(modelStarted.await(10, TimeUnit.SECONDS));
            AgentRun running = first.runRepository().findLatestRootByConversationId(first.sessionId())
                    .orElseThrow();
            assertEquals(AgentRunStatus.RUNNING, running.getStatus());
            assertTrue(first.hasActiveRecoverableRun());

            String suspended = first.suspend();
            assertTrue(suspended.startsWith("suspended:"));
            assertEquals("suspended: " + running.getRunId(), turn.get(10, TimeUnit.SECONDS));

            AgentRun after = first.runRepository().find(running.getRunId()).orElseThrow();
            assertEquals(AgentRunStatus.RUNNING, after.getStatus());
            assertFalse(new FileAttemptLeaseRepository(workspace, mapper).isHealthy(running.getRunId()));

            CliSessionService resumed = resume(workspace, first.sessionId(),
                    countingFinalAnswerGateway("recovered after suspend", new AtomicInteger()));
            try {
                assertTrue(resumed.recoveryRequired());
                assertEquals(running.getRunId(), resumed.recoveryRequiredRun().orElseThrow().getRunId());
                assertEquals("recovered after suspend", resumed.recover());
                assertEquals(AgentRunStatus.COMPLETED,
                        resumed.runRepository().find(running.getRunId()).orElseThrow().getStatus());
            } finally {
                resumed.close();
            }
        } finally {
            releaseModel.countDown();
            turn.cancel(true);
            executor.shutdownNow();
            first.close();
        }
    }

    @Test
    public void suspendRecordsInFlightToolAsInterruptedAndStopsProcessTree() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-suspend-tool");
        Files.writeString(workspace.resolve("notes.txt"), "alpha-content");
        HangingTool tool = HangingTool.of(new ReadFileTool(new LocalWorkspacePort()));
        CliSessionService first = service(workspace,
                toolThenFinalGateway("read_file", "notes.txt", "should-not-finish"),
                List.of(tool.delegate()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> turn = executor.submit(() -> first.runTurn("read notes"));
        try {
            assertTrue(tool.started.await(10, TimeUnit.SECONDS));
            AgentRun running = first.runRepository().findLatestRootByConversationId(first.sessionId())
                    .orElseThrow();
            AgentCheckpoint window = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(running.getRunId()).orElseThrow();
            assertEquals("execution_window", window.getReason());
            assertNotNull(window.getContextSnapshot().getExecutionWindow());

            String suspended = first.suspend();
            assertTrue(suspended.startsWith("suspended:"));
            assertTrue(tool.cancelled.get());
            assertEquals(0, tool.completed.get());
            turn.get(10, TimeUnit.SECONDS);

            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(running.getRunId()).orElseThrow();
            List<ToolExecutionMarker> interrupted = latest.getContextSnapshot().getInterruptedToolCalls();
            assertNotNull(interrupted);
            assertFalse(interrupted.isEmpty());
            assertEquals("read_file", interrupted.get(0).getToolName());
            AgentRun after = first.runRepository().find(running.getRunId()).orElseThrow();
            assertEquals(AgentRunStatus.RUNNING, after.getStatus());

            CliSessionService resumed = resume(workspace, first.sessionId(),
                    countingFinalAnswerGateway("observed-after-suspend", new AtomicInteger()),
                    List.of(new ReadFileTool(new LocalWorkspacePort())));
            try {
                assertTrue(resumed.recoveryRequired());
                resumed.recover();
            } finally {
                resumed.close();
            }
        } finally {
            tool.release.countDown();
            turn.cancel(true);
            executor.shutdownNow();
            first.close();
        }
    }

    @Test
    public void abandonActiveRunIsTerminalAndRejectsLaterWrites() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-abandon-active");
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CliSessionService first = service(workspace,
                blockingFinalGateway(modelStarted, releaseModel, "should-not-finish"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> turn = executor.submit(() -> first.runTurn("finish the original task"));
        try {
            assertTrue(modelStarted.await(10, TimeUnit.SECONDS));
            AgentRun running = first.runRepository().findLatestRootByConversationId(first.sessionId())
                    .orElseThrow();
            ConversationHistoryDocument before = historyRepository(workspace)
                    .find(first.sessionId()).orElseThrow();
            assertFalse(before.getEntries().isEmpty());

            String abandoned = first.abandon();
            assertTrue(abandoned.startsWith("abandoned:"));
            assertEquals("abandoned: " + running.getRunId(), turn.get(10, TimeUnit.SECONDS));

            AgentRun after = first.runRepository().find(running.getRunId()).orElseThrow();
            assertEquals(AgentRunStatus.ABANDONED, after.getStatus());
            ConversationHistoryDocument history = historyRepository(workspace)
                    .find(first.sessionId()).orElseThrow();
            assertEquals(before.getEntries().get(0).content(), history.getEntries().get(0).content());
            long terminalCheckpoint = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(running.getRunId()).orElseThrow().getVersion();

            AgentRun mutated = first.runRepository().find(running.getRunId()).orElseThrow();
            mutated.setStatus(AgentRunStatus.RUNNING);
            try {
                first.runRepository().save(mutated);
                fail("terminal Run must reject later run writes");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("terminal"));
            }

            CliSessionService resumed = resume(workspace, first.sessionId(),
                    countingFinalAnswerGateway("after abandon", new AtomicInteger()));
            try {
                try {
                    resumed.recover();
                    fail("abandoned Run must not be recoverable");
                } catch (CliSessionService.OptionsException e) {
                    assertTrue(e.getMessage().contains("no Recovery Required"));
                }
                AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                        .latest(running.getRunId()).orElseThrow();
                assertEquals(terminalCheckpoint, (long) latest.getVersion());
                assertEquals("after abandon", resumed.runTurn("new work after abandon"));
                AgentRun next = resumed.runRepository()
                        .findLatestRootByConversationId(first.sessionId()).orElseThrow();
                assertNotEquals(running.getRunId(), next.getRunId());
            } finally {
                resumed.close();
            }
        } finally {
            releaseModel.countDown();
            turn.cancel(true);
            executor.shutdownNow();
            first.close();
        }
    }

    private CliSessionService service(Path workspace, ModelGateway gateway) {
        return service(workspace, gateway, List.of());
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, List<AgentTool> tools) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        return new CliSessionService(options(workspace, gateway), mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of(), tools));
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway) {
        return resume(workspace, sessionId, gateway, List.of());
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway,
                                     List<AgentTool> tools) {
        CliSessionService.CliOptions opts = options(workspace, gateway);
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
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("model wait interrupted");
                }
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

    private ModelGateway toolThenFinalGateway(String tool, String path, String answer) {
        AtomicInteger calls = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("model wait interrupted");
                }
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"" + tool
                                    + "\",\"args\":{\"path\":\"" + path + "\"}}</tool>")
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

    private static final class HangingTool implements AgentTool {
        private final AgentTool delegate;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger completed = new AtomicInteger();

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
            if (call.getSecurityScope() != null) {
                call.getSecurityScope().registerShellCanceller(() -> {
                    cancelled.set(true);
                    release.countDown();
                });
            }
            started.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to cancel tool");
                }
            } catch (InterruptedException e) {
                cancelled.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("tool interrupted", e);
            }
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("tool cancelled");
            }
            ToolResult result = delegate.call(call);
            completed.incrementAndGet();
            return result;
        }
    }
}
