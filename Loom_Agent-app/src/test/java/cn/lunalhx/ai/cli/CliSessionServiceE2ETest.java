package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import cn.lunalhx.ai.domain.agent.model.entity.ResumeResult;
import cn.lunalhx.ai.domain.agent.model.entity.TaskCheckpoint;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatMessage;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.Assert.*;

/**
 * CLI-level offline E2E: runs the real loop through the CliSessionService
 * against file-backed stores with a deterministic fake gateway.
 */
public class CliSessionServiceE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private ModelGateway finalAnswerGateway(String answer) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
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

    private CliSessionService service(Path workspace, ModelGateway gateway) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        ModelRuntimeProperties model = new ModelRuntimeProperties();
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent, java.util.List.of());
        return new CliSessionService(options(workspace, gateway), mapper, agent, model,
                sessions, runs, checkpoints, traces, loop);
    }

    // ---- first turn: answer, run artifacts, session persisted ----

    @Test
    public void firstTurnPersistsRunAndSession() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-first");
        CliSessionService session = service(workspace, finalAnswerGateway("done."));
        String answer = session.runTurn("hello");
        assertEquals("done.", answer);

        Optional<AgentRun> run = session.runRepository().findLatestRootByConversationId(session.sessionId());
        assertTrue(run.isPresent());
        assertEquals("COMPLETED", run.get().getStatus().name());
        assertEquals("FINAL_ANSWER_RETURNED", run.get().getStopReason());
        assertEquals(0, (int) run.get().getToolSteps());

        AgentSession persisted = session.sessionRepository().find(session.sessionId()).orElseThrow();
        assertEquals(AgentSession.CURRENT_SCHEMA_VERSION, (int) persisted.getSchemaVersion());
        assertFalse(persisted.getHistory().isEmpty());
        assertNotNull(persisted.getCheckpoint());
        assertNotNull(persisted.getWorkingMemory());
        session.close();
    }

    @Test
    public void newCommandCreatesIndependentSessionAndOriginalRemainsResumable() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-new-session");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService first = service(workspace,
                countingFinalAnswerGateway("before new", modelCalls));
        String originalId = first.sessionId();
        first.runTurn("preserve this conversation");

        first.close();
        CliSessionService.CliOptions reopenedOptions = options(workspace,
                countingFinalAnswerGateway("unused", modelCalls));
        reopenedOptions.resumeSessionId = originalId;
        AgentRuntimeProperties reopenedAgent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService reopened = new CliSessionService(reopenedOptions, mapper, reopenedAgent,
                new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper,
                        countingFinalAnswerGateway("unused", modelCalls),
                        reopenedAgent, java.util.List.of()));
        assertEquals(originalId, reopened.sessionId());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(CliMain.handleControl(reopened, "/new",
                new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)));
        String newId = reopened.sessionId();

        assertNotEquals(originalId, newId);
        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("new session: " + newId));
        assertEquals(1, modelCalls.get());
        AgentSession original = reopened.sessionRepository().find(originalId).orElseThrow();
        AgentSession fresh = reopened.sessionRepository().find(newId).orElseThrow();
        assertFalse(original.getHistory().isEmpty());
        assertNotNull(original.getCheckpoint());
        assertTrue(fresh.getHistory().isEmpty());
        assertTrue(fresh.getWorkingMemory().isEmpty());
        assertNull(fresh.getCheckpoint());
        assertTrue(fresh.getKeyFiles().isEmpty());
        assertEquals(1, reopened.runRepository().findByConversationId(originalId).size());
        assertTrue(reopened.runRepository().findByConversationId(newId).isEmpty());
        reopened.close();

        CliSessionService.CliOptions resumeOptions = options(workspace,
                countingFinalAnswerGateway("after resume", modelCalls));
        resumeOptions.resumeSessionId = originalId;
        AgentRuntimeProperties resumeAgent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService resumed = new CliSessionService(resumeOptions, mapper, resumeAgent,
                new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper,
                        countingFinalAnswerGateway("after resume", modelCalls),
                        resumeAgent, java.util.List.of()));
        assertEquals(originalId, resumed.sessionId());
        assertEquals("after resume", resumed.runTurn("continue original conversation"));
        assertEquals(2, resumed.runRepository().findByConversationId(originalId).size());
        AgentSession freshAfterResume = resumed.sessionRepository().find(newId).orElseThrow();
        assertTrue(freshAfterResume.getHistory().isEmpty());
        resumed.close();
    }

    @Test
    public void resetCommandIsUnavailableWithoutStartingARun() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-reset-removed");
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService session = service(workspace,
                countingFinalAnswerGateway("must not be called", modelCalls));
        String sessionId = session.sessionId();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertTrue(CliMain.handleControl(session, "/reset",
                new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)));

        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("/reset is unavailable"));
        assertEquals(sessionId, session.sessionId());
        assertEquals(0, modelCalls.get());
        assertTrue(session.runRepository().findByConversationId(sessionId).isEmpty());
        AgentSession persisted = session.sessionRepository().find(sessionId).orElseThrow();
        assertTrue(persisted.getHistory().isEmpty());
        session.close();
    }

    @Test
    public void staleSessionCannotOverwriteNewerPersistedState() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-stale-session");
        CliSessionService session = service(workspace, finalAnswerGateway("unused"));
        String sessionId = session.sessionId();
        session.runTurn("old state");

        FileAgentSessionRepository externalStore = new FileAgentSessionRepository(workspace, mapper);
        AgentSession newer = externalStore.find(sessionId).orElseThrow();
        newer.setRuntimeIdentity("authoritative-newer-state");
        newer.getHistory().add(ConversationHistoryEntry.builder()
                .entryId("external-entry")
                .sequence(newer.getLedgerNextSequence())
                .role("user")
                .content("newer persisted state")
                .stableType(ConversationEntryType.USER_INPUT)
                .build());
        newer.setLedgerNextSequence(newer.getLedgerNextSequence() + 1);
        externalStore.save(newer);

        session.persistSession();

        AgentSession persisted = externalStore.find(sessionId).orElseThrow();
        assertEquals("authoritative-newer-state", persisted.getRuntimeIdentity());
        assertTrue(persisted.getHistory().stream()
                .anyMatch(entry -> "newer persisted state".equals(entry.content())));
        session.close();
    }

    // ---- resume: new root run, history carried over, old run untouched ----

    @Test
    public void resumeCreatesNewRootRunAndKeepsOldRunUntouched() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-resume");
        CliSessionService first = service(workspace, finalAnswerGateway("first answer"));
        String sessionId = first.sessionId();
        first.runTurn("first question");
        String oldRunId = first.runRepository().findLatestRootByConversationId(sessionId)
                .orElseThrow().getRunId();
        first.close();

        CliSessionService.CliOptions opts = options(workspace, finalAnswerGateway("second answer"));
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                finalAnswerGateway("second answer"), agent, java.util.List.of());
        CliSessionService resumed = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints, traces, loop);

        String answer = resumed.runTurn("second question");
        assertEquals("second answer", answer);

        AgentRun newRoot = resumed.runRepository().findLatestRootByConversationId(sessionId)
                .orElseThrow();
        assertNotEquals(oldRunId, newRoot.getRunId());
        assertEquals("ROOT", newRoot.getRunKind().name());

        // old run untouched: same runId, still completed, final answer unchanged
        AgentRun oldRun = resumed.runRepository().find(oldRunId).orElseThrow();
        assertEquals("first answer", oldRun.getFinalAnswer());
        assertEquals("COMPLETED", oldRun.getStatus().name());

        // session history grew (2 questions)
        AgentSession persisted = resumed.sessionRepository().find(sessionId).orElseThrow();
        assertTrue(persisted.getHistory().size() >= 2);
        assertTrue(persisted.getHistory().stream()
                .anyMatch(e -> "second question".equals(e.content())));
        resumed.close();
    }

    // ---- checkpoint + run + trace + report consistency ----

    @Test
    public void runTraceReportConsistentAfterTerminal() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-consistency");
        CliSessionService session = service(workspace, finalAnswerGateway("consistent"));
        session.runTurn("task");

        AgentRun run = session.runRepository().findLatestRootByConversationId(session.sessionId())
                .orElseThrow();
        java.nio.file.Path runDir = workspace.resolve(".loom-code").resolve("runs")
                .resolve(run.getRunId());
        assertTrue(Files.isRegularFile(runDir.resolve("run.json")));
        assertTrue(Files.isRegularFile(runDir.resolve("trace.jsonl")));
        assertTrue(Files.isRegularFile(runDir.resolve("report.json")));
        assertTrue(Files.isRegularFile(runDir.resolve("task_state.json")));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> taskState = mapper.readValue(
                runDir.resolve("task_state.json").toFile(), java.util.Map.class);
        assertEquals("completed", taskState.get("status"));
        assertEquals("FINAL_ANSWER_RETURNED", taskState.get("stop_reason"));
        assertEquals(run.getRunId(), taskState.get("run_id"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> report = mapper.readValue(
                runDir.resolve("report.json").toFile(), java.util.Map.class);
        assertEquals(run.getStatus().name().toLowerCase(), report.get("status"));
        assertEquals(run.getStopReason(), report.get("stop_reason"));
        assertEquals(run.getToolSteps(), report.get("tool_steps"));
        session.close();
    }

    // ---- workspace mismatch on resume is rejected ----

    @Test
    public void resumeWithDifferentWorkspaceIsRejected() throws Exception {
        Path workspaceA = Files.createTempDirectory("e2e-ws-a");
        Path workspaceB = Files.createTempDirectory("e2e-ws-b");
        CliSessionService first = service(workspaceA, finalAnswerGateway("ok"));
        String sessionId = first.sessionId();
        first.runTurn("q");
        first.close();

        // copy the session file into workspace B's store so the loader sees it
        Path targetDir = Files.createDirectories(
                workspaceB.resolve(".loom-code").resolve("sessions"));
        Files.copy(first.sessionPath(), targetDir.resolve(sessionId + ".json"));

        CliSessionService.CliOptions opts = options(workspaceB, finalAnswerGateway("ok"));
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspaceB);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspaceB, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspaceB, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspaceB, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspaceB, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspaceB, mapper,
                finalAnswerGateway("ok"), agent, java.util.List.of());
        try {
            new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                    sessions, runs, checkpoints, traces, loop);
            fail("expected workspace mismatch rejection");
        } catch (CliSessionService.OptionsException e) {
            assertTrue(e.getMessage().contains("workspace"));
        }
    }

    // ---- old schema sessions are rejected without overwrite ----

    @Test
    public void legacySchemaSessionIsRejected() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-schema");
        Path sessionsDir = Files.createDirectories(workspace.resolve(".loom-code").resolve("sessions"));
        Path legacy = sessionsDir.resolve("legacy.json");
        Files.writeString(legacy, "{\"id\":\"legacy\",\"schema_version\":0,\"workspace_root\":\""
                + workspace.toString() + "\",\"history\":[]}");

        CliSessionService.CliOptions opts = options(workspace, finalAnswerGateway("x"));
        opts.resumeSessionId = "legacy";
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                finalAnswerGateway("x"), agent, java.util.List.of());
        try {
            new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                    sessions, runs, checkpoints, traces, loop);
            fail("expected schema rejection");
        } catch (CliSessionService.OptionsException e) {
            assertTrue(e.getMessage().contains("schema"));
        }
        // original file untouched
        assertTrue(Files.readString(legacy).contains("schema_version"));
    }

    @Test
    public void currentSchemaSessionWithoutModeIsRejected() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-schema-missing-mode");
        Path sessionsDir = Files.createDirectories(workspace.resolve(".loom-code").resolve("sessions"));
        Path invalid = sessionsDir.resolve("missing-mode.json");
        Files.writeString(invalid, "{\"id\":\"missing-mode\",\"schemaVersion\":"
                + AgentSession.CURRENT_SCHEMA_VERSION + ",\"workspaceRoot\":\""
                + workspace + "\",\"history\":[]}");

        try {
            new FileAgentSessionRepository(workspace, mapper).find("missing-mode");
            fail("expected missing mode rejection");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("collaboration mode"));
        }
        assertTrue(Files.readString(invalid).contains("schemaVersion"));
    }

    // ---- key file change invalidates checkpoint summary on resume ----

    @Test
    public void changedKeyFileInvalidatesCheckpointSummaryOnResume() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-keyfile");
        Path keyFile = workspace.resolve("A.java");
        Files.writeString(keyFile, "class A {}");

        CliSessionService first = serviceWithReadTool(workspace,
                readThenFinalGateway("A.java", "first"));
        String sessionId = first.sessionId();
        first.runTurn("work on A.java");
        first.close();

        // mutate the key file → resume must invalidate the stale summary
        Files.writeString(keyFile, "class A { void changed() {} }");

        CliSessionService.CliOptions opts = options(workspace, finalAnswerGateway("second"));
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                finalAnswerGateway("second"), agent, java.util.List.of());
        CliSessionService resumed = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints, traces, loop);

        // checkpoint summary was discarded because the key file changed
        AgentSession loaded = resumed.sessionRepository().find(sessionId).orElseThrow();
        TaskCheckpoint checkpoint = loaded.getCheckpoint();
        assertNotNull(checkpoint);
        assertNull("stale summary must be discarded after key file change",
                checkpoint.getSummary());
        resumed.close();
    }

    private CliSessionService serviceWithReadTool(Path workspace, ModelGateway gateway) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper, gateway, agent,
                java.util.List.of(),
                java.util.List.of(new cn.lunalhx.ai.infrastructure.loom.ReadFileTool(
                        new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort())));
        return new CliSessionService(options(workspace, gateway), mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints, traces, loop);
    }

    /** Gateway: first call asks for a read_file of the path, then returns the final answer. */
    private ModelGateway readThenFinalGateway(String path, String answer) {
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
                            .content("<tool>{\"name\":\"read_file\",\"args\":{\"path\":\""
                                    + path + "\"}}</tool>")
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

    // ---- approval: ask in interactive terminal; non-interactive rejects ----

    @Test
    public void approvalDeniedInNonInteractiveModeReachesObservation() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-approval");
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService.CliOptions opts = options(workspace,
                runShellGateway("write_file", "done"));
        opts.approvalPolicy = "ask";
        opts.approvalPrompt = new CliSessionService.InteractiveApprovalPrompt(false);

        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                runShellGateway("write_file", "done"), agent, java.util.List.of(),
                java.util.List.of(new cn.lunalhx.ai.infrastructure.loom.WriteFileTool(
                        new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort())));
        CliSessionService session = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints, traces, loop);

        String answer = session.runTurn("write a file");
        assertEquals("done", answer);
        session.close();

        // the approval denial must be visible in the session history
        AgentSession persisted = sessions.find(session.sessionId()).orElseThrow();
        assertTrue("approval denial must reach the model as an observation",
                persisted.getHistory().stream()
                        .anyMatch(e -> e.content() != null && e.content().contains("approval denied")));
    }

    /** Gateway: first call invokes the given approval-controlled tool, then returns the final answer. */
    private ModelGateway runShellGateway(String tool, String answer) {
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
                            .content("<tool>{\"name\":\"" + tool
                                    + "\",\"args\":{\"path\":\"x.txt\",\"content\":\"hi\"}}</tool>")
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

    // ---- delegate: real child run with parent/root lineage, read-only, capped ----

    @Test
    public void delegateCreatesRealChildRunWithLineage() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-delegate");
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        CliSessionService.CliOptions opts = options(workspace, delegateGateway("done"));
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                delegateGateway("done"), agent, java.util.List.of(),
                java.util.List.of(new cn.lunalhx.ai.infrastructure.loom.DelegateTool(
                        new TestDelegateRunner(workspace, mapper, agent))));
        CliSessionService session = new CliSessionService(opts, mapper, agent,
                new ModelRuntimeProperties(), sessions, runs, checkpoints, traces, loop);

        String answer = session.runTurn("delegate to investigate");
        assertEquals("done", answer);
        session.close();

        AgentRun root = runs.findLatestRootByConversationId(session.sessionId()).orElseThrow();
        assertNull(root.getParentRunId());
        assertEquals("ROOT", root.getRunKind().name());

        // the delegate child must exist as a real run with parent lineage
        java.util.List<AgentRun> children = runs.findChildren(root.getRunId());
        assertEquals(1, children.size());
        AgentRun child = children.get(0);
        assertEquals(root.getRunId(), child.getParentRunId());
        assertEquals(root.getRunId(), child.getRootRunId());
        assertEquals(session.sessionId(), child.getConversationId());
        assertEquals("CHILD", child.getRunKind().name());
        assertEquals(3, (int) child.getMaxSteps());
    }

    /** Test-local DelegateRunner: spawns a real read-only child loop with true lineage. */
    private static final class TestDelegateRunner implements cn.lunalhx.ai.domain.agent.adapter.port.DelegateRunner {
        private final Path workspace;
        private final ObjectMapper mapper;
        private final AgentRuntimeProperties agent;

        TestDelegateRunner(Path workspace, ObjectMapper mapper, AgentRuntimeProperties agent) {
            this.workspace = workspace;
            this.mapper = mapper;
            this.agent = agent;
        }

        @Override
        public String delegate(String task, int maxSteps, String parentRunId, String rootRunId,
                               String sessionId, String workspacePath, String parentSummary,
                               CollaborationMode collaborationMode) {
            ModelGateway childGateway = new ModelGateway() {
                @Override
                public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                    return Flux.empty();
                }

                @Override
                public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<final>child found nothing</final>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
            };
            AgentLoopService childLoop = CliLoopTestFixture.build(workspace, mapper,
                    childGateway, agent, java.util.List.of(),
                    java.util.List.of(new cn.lunalhx.ai.infrastructure.loom.ReadFileTool(
                            new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort())));
            AtomicReference<String> answer = new AtomicReference<>("");
            childLoop.ask(cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion.builder()
                            .question(task)
                            .parentRunId(parentRunId)
                            .rootRunId(rootRunId)
                            .sessionId(sessionId)
                            .conversationId(sessionId)
                            .workspace(workspacePath)
                            .agentDepth(1)
                            .maxSteps(Math.min(3, maxSteps))
                            .approvalPolicy("never")
                            .collaborationMode(collaborationMode)
                            .allowedTools(java.util.List.of("list_files", "read_file", "search"))
                            .build())
                    .doOnNext(e -> {
                        if (e.getAnswer() != null && !e.getAnswer().isBlank()) {
                            answer.set(e.getAnswer());
                        }
                    })
                    .blockLast();
            return "delegate_result:\n" + (answer.get().isBlank() ? "(empty)" : answer.get());
        }
    }

    /** Gateway: parent calls delegate once, then finalizes; the child runs in
     *  a separate loop via the test DelegateRunner. */
    private ModelGateway delegateGateway(String answer) {
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
                    // parent: delegate
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"delegate\",\"args\":{\"task\":\"inspect\",\"max_steps\":3}}</tool>")
                            .finishReason("stop")
                            .actualModel("deepseek-v4-flash")
                            .build());
                }
                // parent: final (child already returned via delegate tool result)
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    // ---- secret values never appear in persisted artifacts ----

    @Test
    public void secretEnvValuesRedactedInPersistedArtifacts() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-secret");
        String secret = "TOPSECRETVALUE_123";
        CliSessionService.CliOptions opts = options(workspace, finalAnswerGateway("done"));
        opts.secretValues.add(secret);
        CliSessionService session = serviceWithSecret(workspace, opts);
        String answer = session.runTurn("remember " + secret + " in your answer");
        assertEquals("done", answer);
        session.close();

        // scan everything under .loom-code for the secret
        Path loomDir = workspace.resolve(".loom-code");
        assertFalse("secret leaked into persisted artifacts",
                containsSecret(loomDir, secret));
    }

    private boolean containsSecret(Path dir, String secret) throws Exception {
        if (!Files.exists(dir)) {
            return false;
        }
        try (var stream = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (Files.readString(file).contains(secret)) {
                    return true;
                }
            }
        }
        return false;
    }

    private CliSessionService serviceWithSecret(Path workspace,
                                                CliSessionService.CliOptions opts) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        AgentSessionRepository sessions = new FileAgentSessionRepository(workspace, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(workspace, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(workspace, mapper,
                finalAnswerGateway("done"), agent, java.util.List.of());
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                sessions, runs, checkpoints, traces, loop);
    }
}
