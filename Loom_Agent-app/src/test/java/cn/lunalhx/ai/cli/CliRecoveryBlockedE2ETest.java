package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AttemptLease;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.infrastructure.loom.WriteFileTool;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileAttemptLeaseRepository;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Ticket 05 CLI Session seam: Recovery Blocked fail-closed gating, frozen
 * contract restore, capability shrink, and per-Attempt runtime audit.
 */
public class CliRecoveryBlockedE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void missingLatestCheckpointIsRecoveryBlockedAndAbandonable() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-blocked-missing-cp");
        InterruptedRun interrupted = interruptNoToolRun(workspace);
        deleteCheckpoints(workspace, interrupted.runId);
        assertTrue("latest checkpoint must be gone before resume; files="
                        + listCheckpointFiles(workspace, interrupted.runId),
                new FileAgentCheckpointRepository(workspace, mapper)
                        .latest(interrupted.runId).isEmpty());

        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                countingFinalAnswerGateway("must not recover", modelCalls));
        try {
            assertTrue("run=" + resumed.recoveryRequiredRun()
                            + " reason=" + resumed.recoveryBlockedReason(),
                    resumed.recoveryRequired());
            assertTrue("blocked reason=" + resumed.recoveryBlockedReason(),
                    resumed.recoveryBlocked());
            assertTrue("blocked reason=" + resumed.recoveryBlockedReason(),
                    resumed.recoveryBlockedReason().toLowerCase().contains("checkpoint"));

            ByteArrayOutputStream recoverOut = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/recover",
                    new PrintStream(recoverOut, true, StandardCharsets.UTF_8)));
            assertTrue(recoverOut.toString(StandardCharsets.UTF_8).contains("Recovery Blocked"));
            assertEquals(0, modelCalls.get());

            AgentRun stillOpen = resumed.runRepository().find(interrupted.runId).orElseThrow();
            assertEquals(AgentRunStatus.RUNNING, stillOpen.getStatus());
            assertEquals(interrupted.attemptId, stillOpen.getCurrentAttemptId());
            assertFalse(new FileAttemptLeaseRepository(workspace, mapper)
                    .isHealthy(interrupted.runId));

            ByteArrayOutputStream abandonOut = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/abandon",
                    new PrintStream(abandonOut, true, StandardCharsets.UTF_8)));
            assertTrue(abandonOut.toString(StandardCharsets.UTF_8)
                    .contains("abandoned: " + interrupted.runId));
            assertEquals(0, modelCalls.get());
            assertFalse(resumed.recoveryRequired());
            assertFalse(resumed.recoveryBlocked());
            assertEquals(AgentRunStatus.ABANDONED,
                    resumed.runRepository().find(interrupted.runId).orElseThrow().getStatus());
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void invalidHistoryAnchorIsRecoveryBlockedAndAbandonable() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-blocked-history");
        InterruptedRun interrupted = interruptNoToolRun(workspace);
        tamperLatestCheckpoint(workspace, interrupted.runId, root -> {
            ((com.fasterxml.jackson.databind.node.ObjectNode) root
                    .path("contextSnapshot").path("historyAnchor"))
                    .put("lastEntryId", "tampered-history-anchor");
        });

        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                countingFinalAnswerGateway("must not recover", modelCalls));
        try {
            assertBlockedRecoverRefusedAndAbandonable(resumed, interrupted, modelCalls, "history");
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void workspaceIdentityMismatchIsRecoveryBlockedAndAbandonable() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-blocked-workspace");
        InterruptedRun interrupted = interruptNoToolRun(workspace);
        tamperLatestCheckpoint(workspace, interrupted.runId, root -> {
            ((com.fasterxml.jackson.databind.node.ObjectNode) root
                    .path("contextSnapshot").path("workspace"))
                    .put("location", "/tmp/loom-other-workspace");
        });

        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                countingFinalAnswerGateway("must not recover", modelCalls));
        try {
            assertBlockedRecoverRefusedAndAbandonable(resumed, interrupted, modelCalls, "workspace");
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void corruptLatestCheckpointDoesNotFallBackAndIsAbandonable() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-blocked-corrupt-latest");
        InterruptedRun interrupted = interruptNoToolRun(workspace);
        Path older = latestCheckpointFile(workspace, interrupted.runId);
        assertTrue(Files.exists(older));
        writeNewerIncompatibleCheckpoint(workspace, interrupted.runId);

        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                countingFinalAnswerGateway("must not recover", modelCalls));
        try {
            assertTrue(Files.exists(older));
            assertBlockedRecoverRefusedAndAbandonable(resumed, interrupted, modelCalls, "schema");
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void missingFrozenToolContractsIsRecoveryBlocked() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-blocked-missing-tools");
        InterruptedRun interrupted = interruptNoToolRun(workspace);
        tamperLatestCheckpoint(workspace, interrupted.runId, root -> {
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("contextSnapshot"))
                    .remove("frozenToolContracts");
        });
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                countingFinalAnswerGateway("must not recover", modelCalls));
        try {
            assertBlockedRecoverRefusedAndAbandonable(resumed, interrupted, modelCalls, "tool");
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void missingSkillCatalogIsRecoveryBlocked() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-blocked-missing-skills");
        InterruptedRun interrupted = interruptNoToolRun(workspace);
        tamperLatestCheckpoint(workspace, interrupted.runId, root -> {
            ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("contextSnapshot"))
                    .remove("skillCatalogSnapshot");
        });
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                countingFinalAnswerGateway("must not recover", modelCalls));
        try {
            assertBlockedRecoverRefusedAndAbandonable(resumed, interrupted, modelCalls, "skill");
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void historyAnchorWithoutLastEntryIdIsRecoveryBlocked() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-blocked-null-anchor");
        InterruptedRun interrupted = interruptNoToolRun(workspace);
        tamperLatestCheckpoint(workspace, interrupted.runId, root -> {
            com.fasterxml.jackson.databind.node.ObjectNode anchor =
                    (com.fasterxml.jackson.databind.node.ObjectNode) root
                            .path("contextSnapshot").path("historyAnchor");
            anchor.putNull("lastEntryId");
            if (anchor.path("nextSequence").asLong() <= 0L) {
                anchor.put("nextSequence", 1L);
            }
        });
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                countingFinalAnswerGateway("must not recover", modelCalls));
        try {
            assertBlockedRecoverRefusedAndAbandonable(resumed, interrupted, modelCalls, "history");
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void recoverRestoresFrozenContractsAndAuditsAttemptRuntime() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-frozen-contracts");
        InterruptedRun interrupted = interruptNoToolRun(workspace);
        AgentCheckpoint before = new FileAgentCheckpointRepository(workspace, mapper)
                .latest(interrupted.runId).orElseThrow();
        assertEquals(CollaborationMode.BUILD, before.getContextSnapshot().getRunModeSnapshot());
        assertEquals(ExecutionProfileKind.BUILD_SANDBOX,
                before.getContextSnapshot().getExecutionProfileKind());
        assertNotNull(before.getContextSnapshot().getFrozenAuthorization());

        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService.CliOptions opts = options(workspace,
                countingFinalAnswerGateway("recovered under frozen contracts", modelCalls,
                        "deepseek-v4-pro"));
        opts.provider = "openai";
        opts.model = "deepseek-v4-pro";
        CliSessionService resumed = resume(workspace, interrupted.sessionId, opts.modelGateway, opts);
        try {
            resumed.setCollaborationMode(CollaborationMode.PLAN);
            assertEquals(CollaborationMode.PLAN, resumed.collaborationMode());

            ByteArrayOutputStream recoverOut = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(resumed, "/recover",
                    new PrintStream(recoverOut, true, StandardCharsets.UTF_8)));
            assertTrue(recoverOut.toString(StandardCharsets.UTF_8)
                    .contains("recovered under frozen contracts"));
            assertEquals(1, modelCalls.get());
            assertFalse(resumed.recoveryRequired());

            AgentRun recovered = resumed.runRepository().find(interrupted.runId).orElseThrow();
            assertEquals(interrupted.runId, recovered.getRunId());
            assertNotEquals(interrupted.attemptId, recovered.getCurrentAttemptId());
            assertEquals(AgentRunStatus.COMPLETED, recovered.getStatus());

            AgentCheckpoint after = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(interrupted.runId).orElseThrow();
            AgentContextSnapshot snapshot = after.getContextSnapshot();
            assertEquals(CollaborationMode.BUILD, snapshot.getRunModeSnapshot());
            assertEquals(ExecutionProfileKind.BUILD_SANDBOX, snapshot.getExecutionProfileKind());
            assertNotNull(snapshot.getFrozenAuthorization());
            assertEquals(before.getContextSnapshot().getFrozenAuthorization().snapshotDigest(),
                    snapshot.getFrozenAuthorization().snapshotDigest());
            assertEquals("never", snapshot.getFrozenAuthorization().approvalPolicy());
            assertNotNull(snapshot.getSkillCatalogSnapshot());
            assertEquals(recovered.getCurrentAttemptId(), after.getAttemptId());
            assertEquals("deepseek-v4-pro", after.getModel());
            assertEquals("openai", after.getProvider());
            assertEquals("cli", after.getRuntimeIdentity());
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    @Test
    public void recoverShrinksIncompatibleToolsAndExcludesNewSkills() throws Exception {
        Path workspace = Files.createTempDirectory("e2e-shrink-capability").toRealPath();
        String observeSchema = "{\"type\":\"object\",\"properties\":{\"note\":{\"type\":\"string\"}},"
                + "\"required\":[\"note\"]}";
        List<AgentTool> originalTools = List.of(
                new WriteFileTool(new LocalWorkspacePort()),
                namedTool("observe_note", observeSchema, ToolCapabilityEnvelope.repositoryRead()));
        InterruptedRun interrupted = interruptRun(workspace, originalTools);

        writeSkill(workspace.resolve(".agents/skills/new-skill"),
                "new-skill", "A skill added after interrupt", "NEW_SKILL_BODY");

        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        List<AgentTool> recoveredTools = List.of(
                driftedWriteFile(),
                namedTool("observe_note", observeSchema, ToolCapabilityEnvelope.repositoryRead()),
                namedTool("brand_new_tool",
                        "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}",
                        ToolCapabilityEnvelope.repositoryRead()));
        CliSessionService resumed = resume(workspace, interrupted.sessionId,
                capturingFinalGateway(prompts, modelCalls, "shrunk"),
                options(workspace, capturingFinalGateway(prompts, modelCalls, "shrunk")),
                recoveredTools);
        try {
            assertEquals("shrunk", resumed.recover());
            assertEquals(1, modelCalls.get());
            assertFalse(prompts.isEmpty());
            String system = prompts.get(0).getSystemPrompt();
            assertTrue(system.contains("- observe_note("));
            assertFalse(system.contains("- write_file("));
            assertFalse(system.contains("- brand_new_tool("));
            assertFalse(system.contains("NEW_SKILL_BODY"));
            assertFalse(system.contains("new-skill"));
        } finally {
            interrupted.close();
            resumed.close();
        }
    }

    private void assertBlockedRecoverRefusedAndAbandonable(CliSessionService resumed,
                                                          InterruptedRun interrupted,
                                                          AtomicInteger modelCalls,
                                                          String reasonNeedle) throws Exception {
        assertTrue("run=" + resumed.recoveryRequiredRun()
                        + " reason=" + resumed.recoveryBlockedReason(),
                resumed.recoveryRequired());
        assertTrue("blocked reason=" + resumed.recoveryBlockedReason(),
                resumed.recoveryBlocked());
        assertTrue("blocked reason=" + resumed.recoveryBlockedReason(),
                resumed.recoveryBlockedReason().toLowerCase().contains(reasonNeedle));

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
        assertFalse(new FileAttemptLeaseRepository(workspaceOf(resumed), mapper)
                .isHealthy(interrupted.runId));

        ByteArrayOutputStream abandonOut = new ByteArrayOutputStream();
        assertTrue(CliMain.handleControl(resumed, "/abandon",
                new PrintStream(abandonOut, true, StandardCharsets.UTF_8)));
        assertTrue(abandonOut.toString(StandardCharsets.UTF_8)
                .contains("abandoned: " + interrupted.runId));
        assertEquals(0, modelCalls.get());
        assertFalse(resumed.recoveryRequired());
        assertFalse(resumed.recoveryBlocked());
        assertEquals(AgentRunStatus.ABANDONED,
                resumed.runRepository().find(interrupted.runId).orElseThrow().getStatus());
    }

    private Path workspaceOf(CliSessionService session) {
        return Path.of(session.sessionState().getWorkspaceRoot());
    }

    private void tamperLatestCheckpoint(Path workspace, String runId,
                                        java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutator)
            throws Exception {
        Path file = latestCheckpointFile(workspace, runId);
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(file.toFile());
        mutator.accept(root);
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private void writeNewerIncompatibleCheckpoint(Path workspace, String runId) throws Exception {
        Path latest = latestCheckpointFile(workspace, runId);
        String name = latest.getFileName().toString();
        long version = Long.parseLong(name.substring(0, name.length() - ".json".length()));
        Path newer = latest.getParent().resolve((version + 1) + ".json");
        Files.writeString(newer, """
                {"runId":"%s","version":%d,"currentNode":"prompt_build",
                "contextSnapshot":{"schemaVersion":14,"runId":"%s","runModeSnapshot":"BUILD",
                "ledgerEntries":[],"ledgerNextSequence":0}}
                """.formatted(runId, version + 1, runId));
    }

    private Path latestCheckpointFile(Path workspace, String runId) throws Exception {
        Path dir = workspace.resolve(".loom-code").resolve("checkpoints").resolve(runId);
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparingLong(path -> {
                        String name = path.getFileName().toString();
                        return Long.parseLong(name.substring(0, name.length() - ".json".length()));
                    }))
                    .orElseThrow(() -> new IllegalStateException("no checkpoint for " + runId));
        }
    }

    private void deleteCheckpoints(Path workspace, String runId) throws Exception {
        Path dir = workspace.resolve(".loom-code").resolve("checkpoints").resolve(runId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new IllegalStateException("cannot delete " + path, e);
                }
            });
        }
    }

    private String listCheckpointFiles(Path workspace, String runId) {
        Path dir = workspace.resolve(".loom-code").resolve("checkpoints").resolve(runId);
        if (!Files.isDirectory(dir)) {
            return "(none)";
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(Path::toString).toList().toString();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private InterruptedRun interruptNoToolRun(Path workspace) throws Exception {
        return interruptRun(workspace, List.of());
    }

    private InterruptedRun interruptRun(Path workspace, List<AgentTool> tools) throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        CliSessionService first = service(workspace,
                blockingFinalGateway(modelStarted, releaseModel, "unused"), tools);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> turn = executor.submit(() -> first.runTurn("finish the original task"));
        assertTrue(modelStarted.await(10, TimeUnit.SECONDS));
        AgentRun running = first.runRepository().findLatestRootByConversationId(first.sessionId())
                .orElseThrow();
        assertEquals(AgentRunStatus.RUNNING, running.getStatus());
        assertNotNull(running.getCurrentAttemptId());
        FileAttemptLeaseRepository leases = new FileAttemptLeaseRepository(workspace, mapper);
        AttemptLease lease = leases.find(running.getRunId()).orElseThrow();
        assertTrue(leases.release(running.getRunId(), lease.getFence()));
        return new InterruptedRun(first, executor, turn, releaseModel,
                first.sessionId(), running.getRunId(), running.getCurrentAttemptId());
    }

    private CliSessionService service(Path workspace, ModelGateway gateway) {
        return service(workspace, gateway, List.of());
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, List<AgentTool> tools) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        return new CliSessionService(options(workspace, gateway), mapper, agent,
                new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                CliLoopTestFixture.historyRepository(workspace, mapper),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of(), tools));
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway) {
        return resume(workspace, sessionId, gateway, options(workspace, gateway), List.of());
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway,
                                     CliSessionService.CliOptions opts) {
        return resume(workspace, sessionId, gateway, opts, List.of());
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway,
                                     CliSessionService.CliOptions opts, List<AgentTool> tools) {
        opts.resumeSessionId = sessionId;
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                new FileAgentSessionRepository(workspace, mapper),
                new FileAgentRunRepository(workspace, mapper),
                new FileAgentCheckpointRepository(workspace, mapper),
                CliLoopTestFixture.historyRepository(workspace, mapper),
                new FileTraceRecorder(workspace, mapper),
                CliLoopTestFixture.build(workspace, mapper, gateway, agent, List.of(), tools));
    }

    private CliSessionService.CliOptions options(Path workspace, ModelGateway gateway) {
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
        return options;
    }

    private ModelGateway countingFinalAnswerGateway(String answer, AtomicInteger calls) {
        return countingFinalAnswerGateway(answer, calls, "deepseek-v4-flash");
    }

    private ModelGateway countingFinalAnswerGateway(String answer, AtomicInteger calls,
                                                    String actualModel) {
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
                        .actualModel(actualModel)
                        .build());
            }
        };
    }

    private ModelGateway capturingFinalGateway(List<ChatPrompt> prompts, AtomicInteger calls,
                                               String answer) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                prompts.add(prompt);
                calls.incrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private void writeSkill(Path dir, String name, String description, String body) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                %s
                """.formatted(name, description, body), StandardCharsets.UTF_8);
    }

    private static AgentTool namedTool(String name, String inputSchema,
                                       ToolCapabilityEnvelope envelope) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder()
                        .name(name)
                        .description(name + " tool")
                        .inputSchema(inputSchema)
                        .capabilityEnvelope(envelope)
                        .build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("ok", false, 1L);
            }
        };
    }

    private static AgentTool driftedWriteFile() {
        return namedTool("write_file",
                "{"
                        + "\"type\":\"object\","
                        + "\"properties\":{"
                        + "\"path\":{\"type\":\"string\"},"
                        + "\"content\":{\"type\":\"string\"},"
                        + "\"mode\":{\"type\":\"string\"}"
                        + "},"
                        + "\"required\":[\"path\",\"content\",\"mode\"]"
                        + "}",
                ToolCapabilityEnvelope.repositoryMutation());
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

        void stopProcess() {
            releaseModel.countDown();
            turn.cancel(true);
            executor.shutdownNow();
            try {
                first.close();
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public void close() {
            stopProcess();
        }
    }
}
