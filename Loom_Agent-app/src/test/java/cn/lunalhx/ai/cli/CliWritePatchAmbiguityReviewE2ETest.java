package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
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
import cn.lunalhx.ai.infrastructure.loom.PatchFileTool;
import cn.lunalhx.ai.infrastructure.loom.WriteFileTool;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileConversationHistoryRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ticket 07 seam: interrupted write_file/patch_file reconcile only when the
 * pre-stored safety target and expected digest match current Repository State;
 * otherwise Ambiguity Review. Never fabricate a Tool Result or replay the call.
 */
public class CliWritePatchAmbiguityReviewE2ETest {

    private static final String WRITE_CONTENT = "hello-write";
    private static final String EXPECTED_DIGEST = DigestUtils.sha256Hex(
            WRITE_CONTENT.getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void matchingWriteDigestIsReconciledWithoutFabricatingToolResult() throws Exception {
        Path workspace = Files.createTempDirectory("write-reconcile");
        CountingTool tool = CountingTool.of(new WriteFileTool(new LocalWorkspacePort()));
        tool.crashAfterAdapter.set(true);
        CliSessionService first = service(workspace,
                writeThenFinalGateway("notes.txt", WRITE_CONTENT, "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("write notes");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        assertEquals(1, tool.invocations());
        assertEquals(WRITE_CONTENT, Files.readString(workspace.resolve("notes.txt")));
        AgentCheckpoint window = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(runId).orElseThrow();
        assertEquals("execution_window", window.getReason());
        ToolExecutionMarker marker = window.getContextSnapshot().getExecutionWindow();
        assertNotNull(marker);
        assertEquals("write_file", marker.getToolName());
        assertEquals("notes.txt", marker.getSafetyTarget());
        assertEquals(EXPECTED_DIGEST, marker.getExpectedDigest());

        ConversationHistoryDocument before = historyRepository(workspace).find(sessionId).orElseThrow();
        assertTrue(before.getEntries().stream()
                .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_CALL
                        && "write_file".equals(entry.toolName())));
        assertFalse(before.getEntries().stream()
                .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));

        tool.crashAfterAdapter.set(false);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalAnswerGateway("replanned", modelCalls),
                List.of(tool.delegate()));
        try {
            assertTrue(resumed.recoveryRequired());
            assertEquals("replanned", resumed.recover());
            assertEquals(1, tool.invocations());
            assertEquals(1, modelCalls.get());
            assertFalse(resumed.recoveryRequired());

            AgentRun recovered = resumed.runRepository().find(runId).orElseThrow();
            assertEquals(AgentRunStatus.COMPLETED, recovered.getStatus());

            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow();
            List<ToolExecutionMarker> interrupted = latest.getContextSnapshot().getInterruptedToolCalls();
            assertNotNull(interrupted);
            assertEquals(1, interrupted.size());
            assertEquals("write_file", interrupted.get(0).getToolName());
            assertEquals("notes.txt", interrupted.get(0).getSafetyTarget());
            assertEquals(EXPECTED_DIGEST, interrupted.get(0).getExpectedDigest());
            assertTrue(Boolean.TRUE.equals(interrupted.get(0).getReconciled()));
            assertNull(latest.getContextSnapshot().getExecutionWindow());

            ConversationHistoryDocument after = historyRepository(workspace).find(sessionId).orElseThrow();
            assertEquals(1, after.getEntries().stream()
                    .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_CALL
                            && "write_file".equals(entry.toolName()))
                    .count());
            assertFalse(after.getEntries().stream()
                    .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));
        } finally {
            resumed.close();
        }
    }

    @Test
    public void unprovableWriteEntersAmbiguityReviewWithFactContinueAndAbandon() throws Exception {
        Path workspace = Files.createTempDirectory("write-ambiguity");
        CountingTool tool = CountingTool.of(new WriteFileTool(new LocalWorkspacePort()));
        tool.crashBeforeAdapter.set(true);
        CliSessionService first = service(workspace,
                writeThenFinalGateway("notes.txt", WRITE_CONTENT, "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("write notes");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        assertEquals(0, tool.invocations());
        assertFalse(Files.exists(workspace.resolve("notes.txt")));

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
            assertTrue(shown.contains("Interrupted Tool Call") || shown.contains("write_file"));
            assertTrue(shown.contains("/recovery fact"));
            assertTrue(shown.contains("/recovery continue"));
            assertTrue(shown.contains("/abandon"));
            assertEquals(0, modelCalls.get());
            assertEquals(0, tool.invocations());
            assertTrue(resumed.ambiguityReview());
            assertFalse(resumed.recoveryRequired());

            try {
                resumed.runTurn("start a different task");
                fail("ordinary request must be blocked during Ambiguity Review");
            } catch (CliSessionService.OptionsException e) {
                assertTrue(e.getMessage().contains("Ambiguity Review"));
                assertTrue(e.getMessage().contains("/recovery fact"));
                assertTrue(e.getMessage().contains("/recovery continue"));
                assertTrue(e.getMessage().contains("/abandon"));
            }

            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow();
            List<ToolExecutionMarker> interrupted = latest.getContextSnapshot().getInterruptedToolCalls();
            assertNotNull(interrupted);
            assertEquals(1, interrupted.size());
            assertEquals("write_file", interrupted.get(0).getToolName());
            assertFalse(Boolean.TRUE.equals(interrupted.get(0).getReconciled()));
            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertTrue(history.getEntries().stream().anyMatch(entry ->
                    entry.stableType() == ConversationEntryType.SYSTEM_NOTE
                            && entry.content().contains("Interrupted Tool Call")));
            assertFalse(history.getEntries().stream()
                    .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));

            String abandoned = resumed.abandon();
            assertTrue(abandoned.startsWith("abandoned:"));
            assertFalse(resumed.ambiguityReview());
            assertEquals(AgentRunStatus.ABANDONED,
                    resumed.runRepository().find(runId).orElseThrow().getStatus());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void continueWithAmbiguityReplansWithoutReplayingAndUsesPermissionPipeline()
            throws Exception {
        Path workspace = Files.createTempDirectory("write-continue");
        CountingTool tool = CountingTool.of(new WriteFileTool(new LocalWorkspacePort()));
        tool.crashBeforeAdapter.set(true);
        CliSessionService first = service(workspace,
                writeThenFinalGateway("notes.txt", WRITE_CONTENT, "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("write notes");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        tool.crashBeforeAdapter.set(false);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                writeThenFinalGateway("notes.txt", WRITE_CONTENT, "replanned", modelCalls),
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
            assertEquals(WRITE_CONTENT, Files.readString(workspace.resolve("notes.txt")));

            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow();
            List<ToolExecutionMarker> interrupted = latest.getContextSnapshot().getInterruptedToolCalls();
            assertTrue(interrupted.stream().anyMatch(marker ->
                    "write_file".equals(marker.getToolName())
                            && Boolean.TRUE.equals(marker.getAmbiguityAccepted())
                            && !Boolean.TRUE.equals(marker.getReconciled())));

            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertEquals(2, history.getEntries().stream()
                    .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_CALL
                            && "write_file".equals(entry.toolName()))
                    .count());
            assertEquals(1, history.getEntries().stream()
                    .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT)
                    .count());
            assertTrue(history.getEntries().stream().anyMatch(entry ->
                    entry.stableType() == ConversationEntryType.SYSTEM_NOTE
                            && entry.content().contains("Continue with Ambiguity")));
        } finally {
            resumed.close();
        }
    }

    @Test
    public void recoveryFactIsNotElevatedToToolResult() throws Exception {
        Path workspace = Files.createTempDirectory("write-fact");
        CountingTool tool = CountingTool.of(new WriteFileTool(new LocalWorkspacePort()));
        tool.crashBeforeAdapter.set(true);
        CliSessionService first = service(workspace,
                writeThenFinalGateway("notes.txt", WRITE_CONTENT, "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        try {
            first.runTurn("write notes");
            sessionId = first.sessionId();
        } finally {
            first.close();
        }
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalAnswerGateway("must-not-run", modelCalls),
                List.of(tool.delegate()));
        try {
            resumed.recover();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/recovery fact deploy already landed",
                    new PrintStream(output, true, StandardCharsets.UTF_8)));
            String shown = output.toString(StandardCharsets.UTF_8);
            assertTrue(shown.contains("Ambiguity Review"));
            assertEquals(0, modelCalls.get());
            assertEquals(0, tool.invocations());
            assertTrue(resumed.ambiguityReview());

            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertTrue(history.getEntries().stream().anyMatch(entry ->
                    entry.stableType() == ConversationEntryType.SYSTEM_NOTE
                            && entry.content().contains("Ambiguity Review fact")
                            && entry.content().contains("deploy already landed")));
            assertFalse(history.getEntries().stream()
                    .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));
        } finally {
            resumed.close();
        }
    }

    @Test
    public void matchingPatchDigestIsReconciledWithoutFabricatingToolResult() throws Exception {
        Path workspace = Files.createTempDirectory("patch-reconcile");
        Files.writeString(workspace.resolve("notes.txt"), "alpha");
        CountingTool tool = CountingTool.of(new PatchFileTool(new LocalWorkspacePort()));
        tool.crashAfterAdapter.set(true);
        CliSessionService first = service(workspace,
                patchThenFinalGateway("notes.txt", "alpha", "beta", "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("patch notes");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        assertEquals(1, tool.invocations());
        assertEquals("beta", Files.readString(workspace.resolve("notes.txt")));
        String expected = DigestUtils.sha256Hex("beta".getBytes(StandardCharsets.UTF_8));
        AgentCheckpoint window = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(runId).orElseThrow();
        assertEquals("execution_window", window.getReason());
        assertEquals("notes.txt", window.getContextSnapshot().getExecutionWindow().getSafetyTarget());
        assertEquals(expected, window.getContextSnapshot().getExecutionWindow().getExpectedDigest());

        tool.crashAfterAdapter.set(false);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalAnswerGateway("replanned", modelCalls),
                List.of(tool.delegate()));
        try {
            assertEquals("replanned", resumed.recover());
            assertEquals(1, tool.invocations());
            assertFalse(resumed.ambiguityReview());
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow();
            assertTrue(Boolean.TRUE.equals(
                    latest.getContextSnapshot().getInterruptedToolCalls().get(0).getReconciled()));
            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertFalse(history.getEntries().stream()
                    .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));
        } finally {
            resumed.close();
        }
    }

    @Test
    public void mismatchedWriteDigestEntersAmbiguityReviewWithoutReplay() throws Exception {
        Path workspace = Files.createTempDirectory("write-mismatch");
        CountingTool tool = CountingTool.of(new WriteFileTool(new LocalWorkspacePort()));
        tool.crashAfterAdapter.set(true);
        CliSessionService first = service(workspace,
                writeThenFinalGateway("notes.txt", WRITE_CONTENT, "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("write notes");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        assertEquals(WRITE_CONTENT, Files.readString(workspace.resolve("notes.txt")));
        Files.writeString(workspace.resolve("notes.txt"), "drifted-after-effect");

        tool.crashAfterAdapter.set(false);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalAnswerGateway("must-not-run", modelCalls),
                List.of(tool.delegate()));
        try {
            String shown = resumed.recover();
            assertTrue(shown.contains("Ambiguity Review"));
            assertTrue(shown.contains("Interrupted Tool Call"));
            assertEquals(1, tool.invocations());
            assertEquals(0, modelCalls.get());
            assertTrue(resumed.ambiguityReview());
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow();
            ToolExecutionMarker interrupted = latest.getContextSnapshot()
                    .getInterruptedToolCalls().get(0);
            assertEquals("write_file", interrupted.getToolName());
            assertEquals(EXPECTED_DIGEST, interrupted.getExpectedDigest());
            assertFalse(Boolean.TRUE.equals(interrupted.getReconciled()));
            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertFalse(history.getEntries().stream()
                    .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));
        } finally {
            resumed.close();
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
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        agent.setApprovalPolicy(opts.approvalPolicy);
        ConversationHistoryRepository histories = historyRepository(workspace);
        AgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
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

    private ModelGateway writeThenFinalGateway(String path, String content, String answer) {
        return writeThenFinalGateway(path, content, answer, new AtomicInteger());
    }

    private ModelGateway writeThenFinalGateway(String path, String content, String answer,
                                               AtomicInteger modelCalls) {
        AtomicInteger calls = modelCalls;
        String payload = "<tool>{\"name\":\"write_file\",\"args\":{\"path\":\""
                + path + "\",\"content\":\"" + content + "\"}}</tool>";
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

    private ModelGateway patchThenFinalGateway(String path, String oldText, String newText, String answer) {
        AtomicInteger calls = new AtomicInteger();
        String payload = "<tool>{\"name\":\"patch_file\",\"args\":{\"path\":\""
                + path + "\",\"old_text\":\"" + oldText + "\",\"new_text\":\"" + newText + "\"}}</tool>";
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
