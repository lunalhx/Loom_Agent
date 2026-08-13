package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
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
import cn.lunalhx.ai.infrastructure.mcp.McpAgentTool;
import cn.lunalhx.ai.infrastructure.store.ArtifactRedactor;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileConversationHistoryRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 10 seam: MCP/external calls with a lost durable result enter Ambiguity
 * Review and are never auto-retried. Recovery keeps only contract-compatible
 * frozen MCP tools; persisted windows, results, and user display stay redacted.
 */
public class CliMcpNonReplayRecoveryE2ETest {

    private static final String MCP_TOOL = "github_echo";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void lostMcpResultEntersAmbiguityReviewAndIsNotReplayed() throws Exception {
        Path workspace = Files.createTempDirectory("mcp-ambiguity");
        CountingTool tool = CountingTool.of(mcpEcho("echoed"));
        tool.crashBeforeAdapter.set(true);
        CliSessionService first = service(workspace,
                mcpThenFinalGateway("hello-mcp", "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("call github echo");
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
        ToolExecutionMarker marker = window.getContextSnapshot().getExecutionWindow();
        assertNotNull(marker);
        assertEquals(MCP_TOOL, marker.getToolName());

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
            assertTrue(shown.contains("Interrupted Tool Call") || shown.contains(MCP_TOOL));
            assertTrue(shown.contains("/recovery fact"));
            assertTrue(shown.contains("/recovery continue"));
            assertTrue(shown.contains("/abandon"));
            assertEquals(0, modelCalls.get());
            assertEquals(0, tool.invocations());
            assertTrue(resumed.ambiguityReview());

            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertFalse(history.getEntries().stream()
                    .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));
        } finally {
            resumed.close();
        }
    }

    @Test
    public void lostMcpResultAfterAdapterEntersAmbiguityReviewAndIsNotReplayed() throws Exception {
        Path workspace = Files.createTempDirectory("mcp-after-adapter");
        CountingTool tool = CountingTool.of(mcpEcho("echoed"));
        tool.crashAfterAdapter.set(true);
        CliSessionService first = service(workspace,
                mcpThenFinalGateway("hello-mcp", "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        try {
            first.runTurn("call github echo");
            sessionId = first.sessionId();
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
            assertTrue(resumed.recoveryRequired());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/recover",
                    new PrintStream(output, true, StandardCharsets.UTF_8)));
            String shown = output.toString(StandardCharsets.UTF_8);
            assertTrue(shown.contains("Ambiguity Review"));
            assertEquals(0, modelCalls.get());
            assertEquals(1, tool.invocations());
            assertTrue(resumed.ambiguityReview());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void continueWithAmbiguityDoesNotReplayInterruptedMcpCall() throws Exception {
        Path workspace = Files.createTempDirectory("mcp-continue");
        CountingTool tool = CountingTool.of(mcpEcho("echoed"));
        tool.crashBeforeAdapter.set(true);
        CliSessionService first = service(workspace,
                mcpThenFinalGateway("hello-mcp", "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("call github echo");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        tool.crashBeforeAdapter.set(false);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                mcpThenFinalGateway("hello-mcp", "replanned", modelCalls),
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
            assertEquals(AgentRunStatus.COMPLETED,
                    resumed.runRepository().find(runId).orElseThrow().getStatus());

            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertEquals(2, history.getEntries().stream()
                    .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_CALL
                            && MCP_TOOL.equals(entry.toolName()))
                    .count());
            assertEquals(1, history.getEntries().stream()
                    .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT)
                    .count());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void matchingHistoryResultIsNotReplayedWhenCheckpointLags() throws Exception {
        Path workspace = Files.createTempDirectory("mcp-history-lag");
        CountingTool tool = CountingTool.of(mcpEcho("echoed-ok"));
        FailingCheckpointRepository checkpoints = FailingCheckpointRepository
                .failOnReason(workspace, mapper, "after_tool");
        CliSessionService first = service(workspace, options(workspace,
                        mcpThenFinalGateway("hello-mcp", "should-not-finish")),
                mcpThenFinalGateway("hello-mcp", "should-not-finish"),
                List.of(tool.delegate()), SecretRedactor.none(),
                checkpoints.histories(), checkpoints);
        String sessionId;
        String runId;
        try {
            first.runTurn("call github echo");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
            assertEquals(AgentRunStatus.RUNNING,
                    first.runRepository().find(runId).orElseThrow().getStatus());
        } finally {
            first.close();
        }
        assertEquals(1, tool.invocations());
        ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
        assertTrue(history.getEntries().stream()
                .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));

        checkpoints.stopFailing();
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalAnswerGateway("reprojected", modelCalls),
                List.of(tool.delegate()));
        try {
            assertTrue(resumed.recoveryRequired());
            assertEquals("reprojected", resumed.recover());
            assertEquals(1, tool.invocations());
            assertEquals(1, modelCalls.get());
            assertFalse(resumed.ambiguityReview());
            assertEquals(AgentRunStatus.COMPLETED,
                    resumed.runRepository().find(runId).orElseThrow().getStatus());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void recoverKeepsOnlyContractCompatibleMcpTools() throws Exception {
        Path workspace = Files.createTempDirectory("mcp-shrink");
        CountingTool echo = CountingTool.of(mcpEcho("echoed"));
        echo.crashBeforeAdapter.set(true);
        AgentTool list = mcpTool("github", "list", "list issues",
                Map.of("query", Map.of("type", "string")), List.of("query"), "listed");
        CliSessionService first = service(workspace,
                mcpThenFinalGateway("hello-mcp", "should-not-finish"),
                List.of(echo.delegate(), list));
        String sessionId;
        try {
            first.runTurn("call github echo");
            sessionId = first.sessionId();
        } finally {
            first.close();
        }

        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        AgentTool driftedEcho = mcpTool("github", "echo", "echo a message drifted",
                Map.of("message", Map.of("type", "string")), List.of("message"), "drifted");
        AgentTool created = mcpTool("github", "create_issue", "create an issue",
                Map.of("title", Map.of("type", "string")), List.of("title"), "created");
        CliSessionService resumed = resume(workspace, sessionId,
                capturingFinalGateway(prompts, modelCalls, "shrunk"),
                List.of(driftedEcho, list, created));
        try {
            resumed.recover();
            assertTrue(resumed.ambiguityReview());
        } finally {
            resumed.close();
        }

        CliSessionService again = resume(workspace, sessionId,
                capturingFinalGateway(prompts, modelCalls, "shrunk"),
                List.of(driftedEcho, list, created));
        try {
            assertTrue(again.ambiguityReview());
            assertEquals("shrunk", again.continueWithAmbiguity());
            assertEquals(1, modelCalls.get());
            assertFalse(prompts.isEmpty());
            String system = prompts.get(0).getSystemPrompt();
            assertTrue(system.contains("- github_list("));
            assertFalse(system.contains("- github_echo("));
            assertFalse(system.contains("- github_create_issue("));
        } finally {
            again.close();
        }
    }

    @Test
    public void mcpSecretsDoNotLeakIntoWindowResultOrDisplay() throws Exception {
        Path workspace = Files.createTempDirectory("mcp-redact");
        String secret = "TOPSECRETVALUE_123";
        CountingTool tool = CountingTool.of(mcpEcho("payload " + secret));
        tool.crashBeforeAdapter.set(true);
        SecretRedactor redactor = SecretRedactor.of(Set.of(), Set.of(secret), Set.of());
        ArtifactRedactor artifacts = new ArtifactRedactor(redactor);
        CliSessionService.CliOptions opts = options(workspace,
                mcpThenFinalGateway(secret, "should-not-finish"));
        opts.secretValues.add(secret);
        CliSessionService first = service(workspace, opts,
                mcpThenFinalGateway(secret, "should-not-finish"),
                List.of(tool.delegate()), redactor, historyRepository(workspace),
                new FileAgentCheckpointRepository(workspace, mapper, artifacts));
        String sessionId;
        String runId;
        try {
            first.runTurn("echo the secret");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }

        tool.crashBeforeAdapter.set(false);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService.CliOptions resumeOpts = options(workspace,
                mcpThenFinalGateway("safe-message", "done", modelCalls));
        resumeOpts.resumeSessionId = sessionId;
        resumeOpts.secretValues.add(secret);
        CliSessionService resumed = service(workspace, resumeOpts,
                mcpThenFinalGateway("safe-message", "done", modelCalls),
                List.of(tool.delegate()), redactor, historyRepository(workspace),
                new FileAgentCheckpointRepository(workspace, mapper, artifacts));
        String shown;
        try {
            shown = resumed.recover();
            assertTrue(shown.contains("Ambiguity Review"));
            assertFalse(shown.contains(secret));
            resumed.continueWithAmbiguity();
        } finally {
            resumed.close();
        }
        AgentCheckpoint window = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(runId).orElseThrow();
        List<ToolExecutionMarker> interrupted = window.getContextSnapshot().getInterruptedToolCalls();
        assertNotNull(interrupted);
        assertFalse(interrupted.isEmpty());
        if (interrupted.get(0).getSanitizedInput() != null) {
            assertFalse(interrupted.get(0).getSanitizedInput().contains(secret));
        }
        Path loom = workspace.resolve(".loom-code");
        try (Stream<Path> files = Files.walk(loom)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String body = Files.readString(file);
                assertFalse("secret leaked into " + file, body.contains(secret));
            }
        }
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, List<AgentTool> tools) {
        return service(workspace, options(workspace, gateway), gateway, tools);
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway,
                                     List<AgentTool> tools) {
        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.resumeSessionId = sessionId;
        return service(workspace, opts, gateway, tools);
    }

    private CliSessionService service(Path workspace, CliSessionService.CliOptions opts,
                                      ModelGateway gateway, List<AgentTool> tools) {
        return service(workspace, opts, gateway, tools, SecretRedactor.none(),
                historyRepository(workspace), new FileAgentCheckpointRepository(workspace, mapper));
    }

    private CliSessionService service(Path workspace, CliSessionService.CliOptions opts,
                                      ModelGateway gateway, List<AgentTool> tools,
                                      SecretRedactor redactor,
                                      ConversationHistoryRepository histories,
                                      AgentCheckpointRepository checkpoints) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        agent.setApprovalPolicy(opts.approvalPolicy);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                List.of(), tools, redactor, histories, checkpoints);
        ArtifactRedactor artifacts = new ArtifactRedactor(redactor);
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper, artifacts),
                new FileAgentRunRepository(workspace, mapper, artifacts),
                checkpoints instanceof FileAgentCheckpointRepository fileCheckpoints
                        ? fileCheckpoints : new FileAgentCheckpointRepository(workspace, mapper, artifacts),
                histories instanceof FileConversationHistoryRepository fileHistories
                        ? fileHistories : new FileConversationHistoryRepository(workspace, mapper, artifacts),
                new FileTraceRecorder(workspace, mapper, artifacts), loop);
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
        o.approvalPolicy = "auto";
        o.maxSteps = 6;
        o.maxNewTokens = 512;
        o.temperature = 0.2;
        o.topP = 0.9;
        o.timeoutSeconds = 30;
        o.modelGateway = gateway;
        return o;
    }

    private ModelGateway mcpThenFinalGateway(String message, String answer) {
        return mcpThenFinalGateway(message, answer, new AtomicInteger());
    }

    private ModelGateway mcpThenFinalGateway(String message, String answer, AtomicInteger modelCalls) {
        String payload = "<tool>{\"name\":\"" + MCP_TOOL + "\",\"args\":{\"message\":\""
                + message + "\"}}</tool>";
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (modelCalls.getAndIncrement() == 0) {
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

    private ModelGateway capturingFinalGateway(List<ChatPrompt> prompts, AtomicInteger modelCalls,
                                               String answer) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                prompts.add(prompt);
                modelCalls.incrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private static AgentTool mcpEcho(String observation) {
        return mcpTool("github", "echo", "echo a message",
                Map.of("message", Map.of("type", "string")), List.of("message"), observation);
    }

    private static AgentTool mcpTool(String server, String name, String description,
                                     Map<String, Object> properties, List<String> required,
                                     String observation) {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(observation)), false));
        return new McpAgentTool(client, server, new McpSchema.Tool(name, description,
                new McpSchema.JsonSchema("object", properties, required, false, Map.of(), Map.of())));
    }

    private static final class FailingCheckpointRepository implements AgentCheckpointRepository {
        private final FileAgentCheckpointRepository delegate;
        private final FileConversationHistoryRepository histories;
        private final String failReason;
        private final AtomicBoolean failing = new AtomicBoolean(true);

        private FailingCheckpointRepository(Path workspace, ObjectMapper mapper, String failReason,
                                            ArtifactRedactor artifacts) {
            this.delegate = new FileAgentCheckpointRepository(workspace, mapper, artifacts);
            this.histories = new FileConversationHistoryRepository(workspace, mapper, artifacts);
            this.failReason = failReason;
        }

        static FailingCheckpointRepository failOnReason(Path workspace, ObjectMapper mapper, String reason) {
            return new FailingCheckpointRepository(workspace, mapper, reason, new ArtifactRedactor());
        }

        static FailingCheckpointRepository failOnReason(Path workspace, ObjectMapper mapper, String reason,
                                                        ArtifactRedactor artifacts) {
            return new FailingCheckpointRepository(workspace, mapper, reason, artifacts);
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
}
