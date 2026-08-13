package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
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
import cn.lunalhx.ai.infrastructure.loom.ListFilesTool;
import cn.lunalhx.ai.infrastructure.loom.ReadFileTool;
import cn.lunalhx.ai.infrastructure.loom.SearchTool;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Ticket 06 seam: observation tools persist History tool call → execution-window
 * checkpoint → adapter → History result → closing checkpoint, and recovery
 * never replays a durable result or an Interrupted Tool Call.
 */
public class CliObservationToolRecoveryE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void readFilePersistsHistoryToolCallWindowAdapterResultAndClosingCheckpoint()
            throws Exception {
        Path workspace = Files.createTempDirectory("obs-order-read");
        Files.writeString(workspace.resolve("notes.txt"), "alpha-content");
        CountingTool tool = CountingTool.of(new ReadFileTool(new LocalWorkspacePort()));
        CliSessionService session = service(workspace,
                toolThenFinalGateway("read_file", "notes.txt", "read done"),
                List.of(tool.delegate()));
        try {
            assertEquals("read done", session.runTurn("read notes"));
            assertEquals(1, tool.invocations());
            assertDurabilityOrder(workspace, session.sessionId(), "read_file");
        } finally {
            session.close();
        }
    }

    @Test
    public void listFilesAndSearchFollowTheSameDurabilityOrder() throws Exception {
        Path workspace = Files.createTempDirectory("obs-order-list-search");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/A.java"), "class A {}");
        CountingTool list = CountingTool.of(new ListFilesTool(new LocalWorkspacePort()));
        CliSessionService listed = service(workspace,
                toolThenFinalGateway("list_files", "src", "listed"),
                List.of(list.delegate()));
        try {
            assertEquals("listed", listed.runTurn("list src"));
            assertEquals(1, list.invocations());
            assertDurabilityOrder(workspace, listed.sessionId(), "list_files");
        } finally {
            listed.close();
        }

        Path searchWs = Files.createTempDirectory("obs-order-search");
        Files.writeString(searchWs.resolve("hit.txt"), "find-me-token");
        CountingTool search = CountingTool.of(new SearchTool(new LocalWorkspacePort()));
        CliSessionService searched = service(searchWs,
                searchThenFinalGateway("find-me-token", "searched"),
                List.of(search.delegate()));
        try {
            assertEquals("searched", searched.runTurn("search token"));
            assertEquals(1, search.invocations());
            assertDurabilityOrder(searchWs, searched.sessionId(), "search");
        } finally {
            searched.close();
        }
    }

    @Test
    public void matchingHistoryResultIsReprojectedAndAdapterIsNotReinvoked() throws Exception {
        Path workspace = Files.createTempDirectory("obs-history-lag");
        Files.writeString(workspace.resolve("notes.txt"), "alpha-content");
        CountingTool tool = CountingTool.of(new ReadFileTool(new LocalWorkspacePort()));
        FailingCheckpointRepository checkpoints = FailingCheckpointRepository
                .failOnReason(workspace, mapper, "after_tool");
        CliSessionService first = service(workspace,
                toolThenFinalGateway("read_file", "notes.txt", "should-not-finish"),
                List.of(tool.delegate()), checkpoints.histories(), checkpoints);
        String sessionId;
        String runId;
        try {
            first.runTurn("read notes");
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
        CliSessionService resumed = resume(workspace, sessionId,
                promptAwareFinalGateway("alpha-content", "reprojected"),
                List.of(tool.delegate()));
        try {
            assertTrue(resumed.recoveryRequired());
            assertEquals("reprojected", resumed.recover());
            assertEquals(1, tool.invocations());
            AgentRun recovered = resumed.runRepository().find(runId).orElseThrow();
            assertEquals(AgentRunStatus.COMPLETED, recovered.getStatus());
            assertEquals("reprojected", recovered.getFinalAnswer());
            AgentCheckpoint closed = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow();
            assertNull(closed.getContextSnapshot().getExecutionWindow());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void missingResultBecomesInterruptedToolCallAndContinuesWithNewObservation()
            throws Exception {
        Path workspace = Files.createTempDirectory("obs-interrupted");
        Files.writeString(workspace.resolve("notes.txt"), "alpha-content");
        CountingTool tool = CountingTool.of(new ReadFileTool(new LocalWorkspacePort()));
        tool.crashBeforeAdapter.set(true);
        CliSessionService first = service(workspace,
                toolThenFinalGateway("read_file", "notes.txt", "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("read notes");
            sessionId = first.sessionId();
            AgentRun run = first.runRepository().findLatestRootByConversationId(sessionId).orElseThrow();
            runId = run.getRunId();
        } finally {
            first.close();
        }
        assertEquals(0, tool.invocations());
        AgentCheckpoint window = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(runId).orElseThrow();
        assertEquals("execution_window", window.getReason());
        assertNotNull(window.getContextSnapshot().getExecutionWindow());
        assertEquals("read_file", window.getContextSnapshot().getExecutionWindow().getToolName());

        tool.crashBeforeAdapter.set(false);
        CliSessionService resumed = resume(workspace, sessionId,
                toolThenFinalGateway("read_file", "notes.txt", "observed-again"),
                List.of(tool.delegate()));
        try {
            assertTrue(resumed.recoveryRequired());
            assertEquals("observed-again", resumed.recover());
            assertEquals(1, tool.invocations());
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow();
            List<ToolExecutionMarker> interrupted = latest.getContextSnapshot().getInterruptedToolCalls();
            assertNotNull(interrupted);
            assertFalse(interrupted.isEmpty());
            assertEquals("read_file", interrupted.get(0).getToolName());
            assertNotNull(interrupted.get(0).getToolCallId());
            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertTrue(history.getEntries().stream().anyMatch(entry ->
                    entry.stableType() == ConversationEntryType.SYSTEM_NOTE
                            && entry.content().contains("Interrupted Tool Call")));
            assertEquals(2, history.getEntries().stream()
                    .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_CALL)
                    .count());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void crashAfterAdapterWithoutHistoryResultDoesNotReplay() throws Exception {
        Path workspace = Files.createTempDirectory("obs-after-adapter");
        Files.writeString(workspace.resolve("notes.txt"), "alpha-content");
        CountingTool tool = CountingTool.of(new ReadFileTool(new LocalWorkspacePort()));
        tool.crashAfterAdapter.set(true);
        CliSessionService first = service(workspace,
                toolThenFinalGateway("read_file", "notes.txt", "should-not-finish"),
                List.of(tool.delegate()));
        String sessionId;
        String runId;
        try {
            first.runTurn("read notes");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        assertEquals(1, tool.invocations());
        ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
        assertFalse(history.getEntries().stream()
                .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT));

        tool.crashAfterAdapter.set(false);
        CliSessionService resumed = resume(workspace, sessionId,
                toolThenFinalGateway("read_file", "notes.txt", "new-observation"),
                List.of(tool.delegate()));
        try {
            assertEquals("new-observation", resumed.recover());
            assertEquals(2, tool.invocations());
            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow();
            assertFalse(latest.getContextSnapshot().getInterruptedToolCalls() == null
                    || latest.getContextSnapshot().getInterruptedToolCalls().isEmpty());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void crashBeforeExecutionWindowNeverStartsAdapter() throws Exception {
        Path workspace = Files.createTempDirectory("obs-before-window");
        Files.writeString(workspace.resolve("notes.txt"), "alpha-content");
        CountingTool tool = CountingTool.of(new ReadFileTool(new LocalWorkspacePort()));
        FailingCheckpointRepository checkpoints = FailingCheckpointRepository
                .failOnReason(workspace, mapper, "execution_window");
        CliSessionService first = service(workspace,
                toolThenFinalGateway("read_file", "notes.txt", "should-not-finish"),
                List.of(tool.delegate()), checkpoints.histories(), checkpoints);
        String sessionId;
        String runId;
        try {
            first.runTurn("read notes");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        assertEquals(0, tool.invocations());
        AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(runId).orElseThrow();
        assertFalse("execution_window".equals(latest.getReason()));
        assertNull(latest.getContextSnapshot().getExecutionWindow());

        checkpoints.stopFailing();
        CliSessionService resumed = resume(workspace, sessionId,
                toolThenFinalGateway("read_file", "notes.txt", "started-fresh"),
                List.of(tool.delegate()));
        try {
            assertEquals("started-fresh", resumed.recover());
            assertEquals(1, tool.invocations());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void recoveryArtifactsAreRedacted() throws Exception {
        Path workspace = Files.createTempDirectory("obs-redact");
        String secret = "TOPSECRETVALUE_123";
        Files.writeString(workspace.resolve("secret.txt"), "payload " + secret);
        CountingTool tool = CountingTool.of(new ReadFileTool(new LocalWorkspacePort()));
        tool.crashBeforeAdapter.set(true);
        SecretRedactor redactor = SecretRedactor.of(Set.of(), Set.of(secret), Set.of());
        CliSessionService.CliOptions opts = options(workspace,
                toolThenFinalGateway("read_file", "secret.txt", "unused"));
        opts.secretValues.add(secret);
        CliSessionService first = service(workspace, opts,
                toolThenFinalGateway("read_file", "secret.txt", "unused"),
                List.of(tool.delegate()), redactor, historyRepository(workspace),
                new FileAgentCheckpointRepository(workspace, mapper,
                        new cn.lunalhx.ai.infrastructure.store.ArtifactRedactor(redactor)));
        String sessionId;
        String runId;
        try {
            first.runTurn("read the secret file");
            sessionId = first.sessionId();
            runId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
        } finally {
            first.close();
        }
        tool.crashBeforeAdapter.set(false);
        CliSessionService.CliOptions resumeOpts = options(workspace,
                toolThenFinalGateway("read_file", "secret.txt", "done"));
        resumeOpts.resumeSessionId = sessionId;
        resumeOpts.secretValues.add(secret);
        CliSessionService resumed = service(workspace, resumeOpts,
                toolThenFinalGateway("read_file", "secret.txt", "done"),
                List.of(tool.delegate()), redactor, historyRepository(workspace),
                new FileAgentCheckpointRepository(workspace, mapper,
                        new cn.lunalhx.ai.infrastructure.store.ArtifactRedactor(redactor)));
        try {
            resumed.recover();
        } finally {
            resumed.close();
        }
        Path loom = workspace.resolve(".loom-code");
        try (Stream<Path> files = Files.walk(loom)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String body = Files.readString(file);
                assertFalse("secret leaked into " + file, body.contains(secret));
            }
        }
        AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(runId).orElseThrow();
        List<ToolExecutionMarker> interrupted = latest.getContextSnapshot().getInterruptedToolCalls();
        assertNotNull(interrupted);
        assertFalse(interrupted.isEmpty());
    }

    private void assertDurabilityOrder(Path workspace, String sessionId, String toolName)
            throws Exception {
        ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
        List<ConversationHistoryEntry> entries = history.getEntries();
        int toolCallAt = indexOf(entries, ConversationEntryType.TOOL_CALL, toolName);
        int toolResultAt = indexOf(entries, ConversationEntryType.TOOL_RESULT, toolName);
        assertTrue(toolCallAt >= 0);
        assertTrue(toolResultAt > toolCallAt);
        assertTrue(entries.get(toolCallAt).content() != null
                && !entries.get(toolCallAt).content().isBlank());

        AgentRun run = new FileAgentRunRepository(workspace, mapper)
                .findLatestRootByConversationId(sessionId).orElseThrow();
        List<AgentCheckpoint> checkpoints = checkpointsInOrder(workspace, run.getRunId());
        int windowAt = -1;
        int afterToolAt = -1;
        for (int i = 0; i < checkpoints.size(); i++) {
            if ("execution_window".equals(checkpoints.get(i).getReason())) {
                windowAt = i;
            }
            if ("after_tool".equals(checkpoints.get(i).getReason())) {
                afterToolAt = i;
            }
        }
        assertTrue(windowAt >= 0);
        assertTrue(afterToolAt > windowAt);
        ToolExecutionMarker marker = checkpoints.get(windowAt).getContextSnapshot().getExecutionWindow();
        assertNotNull(marker);
        assertEquals(toolName, marker.getToolName());
        assertNotNull(marker.getToolCallId());
        assertNotNull(marker.getSanitizedInput());
        assertNull(checkpoints.get(afterToolAt).getContextSnapshot().getExecutionWindow());
    }

    private int indexOf(List<ConversationHistoryEntry> entries, ConversationEntryType type, String toolName) {
        for (int i = 0; i < entries.size(); i++) {
            ConversationHistoryEntry entry = entries.get(i);
            if (entry.stableType() == type && toolName.equals(entry.toolName())) {
                return i;
            }
        }
        return -1;
    }

    private List<AgentCheckpoint> checkpointsInOrder(Path workspace, String runId) throws Exception {
        Path dir = workspace.resolve(".loom-code/checkpoints").resolve(runId);
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> json = files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> Long.parseLong(
                            p.getFileName().toString().replace(".json", ""))))
                    .toList();
            List<AgentCheckpoint> checkpoints = new ArrayList<>();
            for (Path file : json) {
                checkpoints.add(mapper.readValue(file.toFile(), AgentCheckpoint.class));
            }
            return checkpoints;
        }
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, List<AgentTool> tools) {
        return service(workspace, options(workspace, gateway), gateway, tools,
                SecretRedactor.none(), historyRepository(workspace),
                new FileAgentCheckpointRepository(workspace, mapper));
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, List<AgentTool> tools,
                                      ConversationHistoryRepository histories,
                                      AgentCheckpointRepository checkpoints) {
        return service(workspace, options(workspace, gateway), gateway, tools,
                SecretRedactor.none(), histories, checkpoints);
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway,
                                     List<AgentTool> tools) {
        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.resumeSessionId = sessionId;
        return service(workspace, opts, gateway, tools, SecretRedactor.none(),
                historyRepository(workspace), new FileAgentCheckpointRepository(workspace, mapper));
    }

    private CliSessionService service(Path workspace, CliSessionService.CliOptions opts,
                                      ModelGateway gateway, List<AgentTool> tools,
                                      SecretRedactor redactor,
                                      ConversationHistoryRepository histories,
                                      AgentCheckpointRepository checkpoints) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                List.of(), tools, redactor, histories, checkpoints);
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
        o.approvalPolicy = "never";
        o.maxSteps = 6;
        o.maxNewTokens = 512;
        o.temperature = 0.2;
        o.topP = 0.9;
        o.timeoutSeconds = 30;
        o.modelGateway = gateway;
        return o;
    }

    private ModelGateway toolThenFinalGateway(String tool, String path, String answer) {
        AtomicInteger calls = new AtomicInteger();
        String args = "read_file".equals(tool)
                ? "{\"path\":\"" + path + "\"}"
                : "{\"path\":\"" + path + "\"}";
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"" + tool + "\",\"args\":" + args + "}</tool>")
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

    private ModelGateway searchThenFinalGateway(String pattern, String answer) {
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
                            .content("<tool>{\"name\":\"search\",\"args\":{\"pattern\":\""
                                    + pattern + "\"}}</tool>")
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

    private ModelGateway promptAwareFinalGateway(String needle, String answer) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                String visible = (prompt.getSystemPrompt() == null ? "" : prompt.getSystemPrompt())
                        + "\n" + (prompt.getMessage() == null ? "" : prompt.getMessage());
                if (prompt.getMessages() != null) {
                    visible += prompt.getMessages().stream()
                            .map(m -> m.getContent() == null ? "" : m.getContent())
                            .reduce("", (a, b) -> a + "\n" + b);
                }
                if (!visible.contains(needle)) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"read_file\",\"args\":{\"path\":\"notes.txt\"}}</tool>")
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
