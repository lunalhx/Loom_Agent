package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.agent.model.entity.ToolExecutionMarker;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.agent.service.context.SecretRedactor;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.loom.RunShellTool;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ticket 09 seam: interrupted run_shell is an unknown external effect. Lost
 * durable results enter Ambiguity Review without replay, reconnect, or an
 * exactly-once claim. Conversation History remains authoritative when the
 * next AgentCheckpoint lags.
 */
public class CliShellNonReplayRecoveryE2ETest {

    private static final String SHELL_COMMAND = "echo recovered-shell";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void lostShellResultEntersAmbiguityReviewWithoutReplay() throws Exception {
        Path workspace = Files.createTempDirectory("shell-ambiguity");
        CountingTool tool = CountingTool.of(shellTool(workspace));
        tool.crashBeforeAdapter.set(true);
        CliSessionService first = service(workspace,
                shellThenFinalGateway(SHELL_COMMAND, "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("run shell");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        assertEquals(0, tool.invocations());
        AgentCheckpoint window = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(runId).orElseThrow();
        assertEquals("execution_window", window.getReason());
        assertNotNull(window.getContextSnapshot().getExecutionWindow());
        assertEquals("run_shell", window.getContextSnapshot().getExecutionWindow().getToolName());

        tool.crashBeforeAdapter.set(false);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalAnswerGateway("must-not-run", modelCalls),
                List.of(tool.delegate()));
        try {
            assertTrue(resumed.recoveryRequired());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/recover",
                    new PrintStream(output, true, StandardCharsets.UTF_8)));
            String shown = output.toString(StandardCharsets.UTF_8);
            assertTrue(shown.contains("Ambiguity Review"));
            assertTrue(shown.contains("Interrupted Tool Call") || shown.contains("run_shell"));
            assertTrue(shown.contains("/recovery fact"));
            assertTrue(shown.contains("/recovery continue"));
            assertTrue(shown.contains("/abandon"));
            assertFalse(shown.toLowerCase().contains("exactly-once"));
            assertFalse(shown.toLowerCase().contains("exactly once"));
            assertEquals(0, modelCalls.get());
            assertEquals(0, tool.invocations());
            assertTrue(resumed.ambiguityReview());

            try {
                resumed.runTurn("start a different task");
                fail("ordinary request must be blocked during Ambiguity Review");
            } catch (CliSessionService.OptionsException e) {
                assertTrue(e.getMessage().contains("Ambiguity Review"));
            }

            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertFalse(history.getEntries().stream()
                    .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow();
            List<ToolExecutionMarker> interrupted = latest.getContextSnapshot().getInterruptedToolCalls();
            assertNotNull(interrupted);
            assertEquals(1, interrupted.size());
            assertEquals("run_shell", interrupted.get(0).getToolName());
            assertFalse(Boolean.TRUE.equals(interrupted.get(0).getReconciled()));
        } finally {
            resumed.close();
        }
    }

    @Test
    public void shellEffectWithoutDurableResultIsNeverReconciledFromRepositoryState() throws Exception {
        Path workspace = Files.createTempDirectory("shell-unreconciled");
        CountingTool tool = CountingTool.of(shellTool(workspace));
        tool.crashAfterAdapter.set(true);
        CliSessionService first = service(workspace,
                shellThenFinalGateway(SHELL_COMMAND, "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("write via shell");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        assertEquals(1, tool.invocations());

        tool.crashAfterAdapter.set(false);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalAnswerGateway("must-not-run", modelCalls),
                List.of(tool.delegate()));
        try {
            String shown = resumed.recover();
            assertTrue(shown.contains("Ambiguity Review"));
            assertTrue(shown.contains("Interrupted Tool Call"));
            assertFalse(shown.toLowerCase().contains("exactly-once"));
            assertEquals(1, tool.invocations());
            assertEquals(0, modelCalls.get());
            assertTrue(resumed.ambiguityReview());
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow();
            ToolExecutionMarker interrupted = latest.getContextSnapshot()
                    .getInterruptedToolCalls().get(0);
            assertEquals("run_shell", interrupted.getToolName());
            assertFalse(Boolean.TRUE.equals(interrupted.getReconciled()));
            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertFalse(history.getEntries().stream()
                    .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));
        } finally {
            resumed.close();
        }
    }

    @Test
    public void durableHistoryResultIsAuthoritativeWhenCheckpointLags() throws Exception {
        Path workspace = Files.createTempDirectory("shell-history-lag");
        CountingTool tool = CountingTool.of(shellTool(workspace));
        FailingCheckpointRepository checkpoints = FailingCheckpointRepository
                .failOnReason(workspace, mapper, "after_tool");
        CliSessionService first = service(workspace,
                shellThenFinalGateway(SHELL_COMMAND, "should-not-finish"),
                List.of(tool.delegate()), checkpoints.histories(), checkpoints);
        String sessionId;
        String runId;
        try {
            first.runTurn("run shell");
            sessionId = first.sessionId();
            AgentRun run = first.runRepository().findLatestRootByConversationId(sessionId).orElseThrow();
            runId = run.getRunId();
            assertEquals(AgentRunStatus.RUNNING, run.getStatus());
        } finally {
            first.close();
        }
        assertEquals(1, tool.invocations());
        ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
        assertTrue(history.getEntries().stream()
                .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));
        AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(runId).orElseThrow();
        assertEquals("execution_window", latest.getReason());
        assertNotNull(latest.getContextSnapshot().getExecutionWindow());

        checkpoints.stopFailing();
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                promptAwareFinalGateway("recovered-shell", "history-confirmed", modelCalls),
                List.of(tool.delegate()));
        try {
            assertTrue(resumed.recoveryRequired());
            assertEquals("history-confirmed", resumed.recover());
            assertEquals(1, tool.invocations());
            assertEquals(1, modelCalls.get());
            assertFalse(resumed.ambiguityReview());
            AgentRun recovered = resumed.runRepository().find(runId).orElseThrow();
            assertEquals(AgentRunStatus.COMPLETED, recovered.getStatus());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void continueWithAmbiguityDoesNotReplayInterruptedShell() throws Exception {
        Path workspace = Files.createTempDirectory("shell-continue");
        CountingTool tool = CountingTool.of(shellTool(workspace));
        tool.crashBeforeAdapter.set(true);
        CliSessionService first = service(workspace,
                shellThenFinalGateway(SHELL_COMMAND, "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        try {
            first.runTurn("run shell");
            sessionId = first.sessionId();
        } finally {
            first.close();
        }
        tool.crashBeforeAdapter.set(false);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                shellThenFinalGateway(SHELL_COMMAND, "replanned", modelCalls),
                List.of(tool.delegate()));
        try {
            resumed.recover();
            assertTrue(resumed.ambiguityReview());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/recovery continue",
                    new PrintStream(output, true, StandardCharsets.UTF_8)));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("replanned"));
            assertEquals(2, modelCalls.get());
            assertEquals(1, tool.invocations());
            assertFalse(resumed.ambiguityReview());

            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertEquals(2, history.getEntries().stream()
                    .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_CALL
                            && "run_shell".equals(entry.toolName()))
                    .count());
            assertEquals(1, history.getEntries().stream()
                    .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT)
                    .count());
        } finally {
            resumed.close();
        }
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, List<AgentTool> tools) {
        return service(workspace, options(workspace, gateway), gateway, tools,
                historyRepository(workspace), new FileAgentCheckpointRepository(workspace, mapper));
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, List<AgentTool> tools,
                                      ConversationHistoryRepository histories,
                                      AgentCheckpointRepository checkpoints) {
        return service(workspace, options(workspace, gateway), gateway, tools, histories, checkpoints);
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway,
                                     List<AgentTool> tools) {
        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.resumeSessionId = sessionId;
        return service(workspace, opts, gateway, tools,
                historyRepository(workspace), new FileAgentCheckpointRepository(workspace, mapper));
    }

    private CliSessionService service(Path workspace, CliSessionService.CliOptions opts,
                                      ModelGateway gateway, List<AgentTool> tools,
                                      ConversationHistoryRepository histories,
                                      AgentCheckpointRepository checkpoints) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        agent.setApprovalPolicy(opts.approvalPolicy);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                List.of(), tools, SecretRedactor.none(), histories, checkpoints);
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                checkpoints instanceof FileAgentCheckpointRepository fileCheckpoints
                        ? fileCheckpoints : new FileAgentCheckpointRepository(workspace, mapper),
                histories instanceof FileConversationHistoryRepository fileHistories
                        ? fileHistories : historyRepository(workspace),
                new FileTraceRecorder(workspace, mapper), loop);
    }

    private FileConversationHistoryRepository historyRepository(Path workspace) {
        return CliLoopTestFixture.historyRepository(workspace, mapper);
    }

    private RunShellTool shellTool(Path workspace) {
        return new RunShellTool(new LocalWorkspacePort(), new FileAttemptLeaseRepository(workspace, mapper));
    }

    private CliSessionService.CliOptions options(Path workspace, ModelGateway gateway) {
        CliSessionService.CliOptions o = new CliSessionService.CliOptions();
        o.provider = "deepseek";
        o.model = "deepseek-v4-flash";
        o.baseUrl = "http://unused";
        o.apiKey = "";
        o.workspaceRoot = workspace.toString();
        o.approvalPolicy = "auto";
        o.maxSteps = 6;
        o.maxNewTokens = 512;
        o.temperature = 0.2;
        o.topP = 0.9;
        o.timeoutSeconds = 30;
        o.modelGateway = gateway;
        return o;
    }

    private ModelGateway shellThenFinalGateway(String command, String answer) {
        return shellThenFinalGateway(command, answer, new AtomicInteger());
    }

    private ModelGateway shellThenFinalGateway(String command, String answer, AtomicInteger modelCalls) {
        AtomicInteger calls = modelCalls;
        String payload = "<tool>{\"name\":\"run_shell\",\"args\":{\"command\":\"" + command + "\"}}</tool>";
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content(payload)
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

    private ModelGateway countingFinalAnswerGateway(String answer, AtomicInteger modelCalls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                modelCalls.incrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway promptAwareFinalGateway(String needle, String answer, AtomicInteger modelCalls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                modelCalls.incrementAndGet();
                String visible = (prompt.getSystemPrompt() == null ? "" : prompt.getSystemPrompt())
                        + "\n" + (prompt.getMessage() == null ? "" : prompt.getMessage());
                if (prompt.getMessages() != null) {
                    visible += prompt.getMessages().stream()
                            .map(m -> m.getContent() == null ? "" : m.getContent())
                            .reduce("", (a, b) -> a + "\n" + b);
                }
                if (!visible.contains(needle)) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"run_shell\",\"args\":{\"command\":\""
                                    + SHELL_COMMAND + "\"}}</tool>")
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

    private static final class CountingTool implements AgentTool {
        private final AgentTool delegate;
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicBoolean crashBeforeAdapter = new AtomicBoolean();
        private final AtomicBoolean crashAfterAdapter = new AtomicBoolean();

        private CountingTool(AgentTool delegate) {
            this.delegate = delegate;
        }

        static CountingTool of(AgentTool delegate) {
            return new CountingTool(delegate);
        }

        int invocations() {
            return invocations.get();
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
            if (crashBeforeAdapter.get()) {
                throw new SimulatedProcessCrash("before adapter");
            }
            ToolResult result = delegate.call(call);
            invocations.incrementAndGet();
            if (crashAfterAdapter.get()) {
                throw new SimulatedProcessCrash("after adapter");
            }
            return result;
        }
    }

    private static final class SimulatedProcessCrash extends Error {
        SimulatedProcessCrash(String message) {
            super(message);
        }
    }

    private static final class FailingCheckpointRepository implements AgentCheckpointRepository {
        private final FileAgentCheckpointRepository delegate;
        private final FileConversationHistoryRepository histories;
        private final String failReason;
        private final AtomicBoolean failing = new AtomicBoolean(true);

        private FailingCheckpointRepository(Path workspace, ObjectMapper mapper, String failReason) {
            this.delegate = new FileAgentCheckpointRepository(workspace, mapper);
            this.histories = new FileConversationHistoryRepository(workspace, mapper);
            this.failReason = failReason;
        }

        static FailingCheckpointRepository failOnReason(Path workspace, ObjectMapper mapper, String reason) {
            return new FailingCheckpointRepository(workspace, mapper, reason);
        }

        FileConversationHistoryRepository histories() {
            return histories;
        }

        void stopFailing() {
            failing.set(false);
        }

        @Override
        public AgentCheckpoint save(AgentCheckpoint checkpoint) {
            if (failing.get() && failReason.equals(checkpoint.getReason())) {
                throw new IllegalStateException("injected failure at " + failReason);
            }
            return delegate.save(checkpoint);
        }

        @Override
        public java.util.Optional<AgentCheckpoint> latest(String runId) {
            return delegate.latest(runId);
        }
    }
}
