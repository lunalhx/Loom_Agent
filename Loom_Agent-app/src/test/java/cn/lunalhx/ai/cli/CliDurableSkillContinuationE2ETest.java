package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.infrastructure.loom.WriteFileTool;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Ticket 07 seam: process-restart continuation keeps frozen Skill Catalog /
 * active bodies and authorization; host Skill file drift must not change the
 * restored Run prompt.
 */
public class CliDurableSkillContinuationE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void processRestartContinuesFrozenSkillsFromPromptBuild() throws Exception {
        Path home = Files.createTempDirectory("cli-durable-home");
        Path workspace = Files.createTempDirectory("cli-durable-ws").toRealPath();
        Path skillDir = workspace.resolve(".agents/skills/review-pr");
        writeSkill(skillDir, "review-pr", "Review carefully.", "FROZEN_BODY");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());

        CopyOnWriteArrayList<ChatPrompt> firstPrompts = new CopyOnWriteArrayList<>();
        AtomicInteger firstCalls = new AtomicInteger();
        ModelGateway firstGateway = toolThenFinalGateway(firstPrompts, firstCalls);
        List<AgentTool> tools = List.of(new WriteFileTool(new LocalWorkspacePort()));
        CliSessionService first = service(options(workspace, firstGateway), firstGateway, tools);
        String sessionId;
        String runId;
        try {
            assertEquals("done", first.runTurn("$review-pr write note.txt then finish"));
            sessionId = first.sessionId();
            AgentRun completed = first.runRepository().findLatestRootByConversationId(sessionId).orElseThrow();
            runId = completed.getRunId();
            assertTrue(firstPrompts.get(0).getSystemPrompt().contains("FROZEN_BODY"));
        } finally {
            first.close();
        }

        prepareInterruptedRun(workspace, runId);
        writeSkill(skillDir, "review-pr", "Review carefully.", "DRIFTED_BODY");

        CopyOnWriteArrayList<ChatPrompt> resumePrompts = new CopyOnWriteArrayList<>();
        AtomicInteger resumeCalls = new AtomicInteger();
        ModelGateway resumeGateway = finalOnlyGateway(resumePrompts, resumeCalls);
        CliSessionService.CliOptions resumeOpts = options(workspace, resumeGateway);
        resumeOpts.resumeSessionId = sessionId;
        CliSessionService resumed = service(resumeOpts, resumeGateway, tools);
        try {
            AgentSession session = resumed.sessionRepository().find(sessionId).orElseThrow();
            assertEquals(AgentSession.CURRENT_SCHEMA_VERSION, (int) session.getSchemaVersion());

            String answer = resumed.continueInterruptedRun();
            assertEquals("continued", answer);
            assertEquals(1, resumeCalls.get());
            assertFalse(resumePrompts.isEmpty());
            String system = resumePrompts.get(0).getSystemPrompt();
            assertTrue(system.contains("Active skills:"));
            assertTrue(system.contains("FROZEN_BODY"));
            assertFalse(system.contains("DRIFTED_BODY"));
            assertEquals(firstPrompts.get(0).getStablePrefixSignature(),
                    resumePrompts.get(0).getStablePrefixSignature());

            AgentContextSnapshot latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(runId).orElseThrow().getContextSnapshot();
            assertEquals(15, (int) latest.getSchemaVersion());
            assertNotNull(latest.getFrozenAuthorization());
            assertNotNull(latest.getSkillCatalogSnapshot());
            String json = mapper.writeValueAsString(latest);
            assertFalse(json.contains(home.toString()));
            assertFalse(json.contains(skillDir.toString()));
        } finally {
            restoreHome(previousHome);
            resumed.close();
        }
    }

    @Test
    public void newRootRunRediscoverCatalogAfterSessionResume() throws Exception {
        Path home = Files.createTempDirectory("cli-durable-newroot-home");
        Path workspace = Files.createTempDirectory("cli-durable-newroot-ws").toRealPath();
        Path skillDir = workspace.resolve(".agents/skills/review-pr");
        writeSkill(skillDir, "review-pr", "Review carefully.", "ORIGINAL");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());

        CopyOnWriteArrayList<ChatPrompt> firstPrompts = new CopyOnWriteArrayList<>();
        ModelGateway firstGateway = finalOnlyGateway(firstPrompts, new AtomicInteger());
        CliSessionService first = service(options(workspace, firstGateway), firstGateway, List.of());
        String sessionId;
        try {
            first.runTurn("$review-pr first");
            sessionId = first.sessionId();
            assertTrue(firstPrompts.get(0).getSystemPrompt().contains("ORIGINAL"));
        } finally {
            first.close();
        }

        writeSkill(skillDir, "review-pr", "Review carefully.", "UPDATED_FOR_NEW_ROOT");
        CopyOnWriteArrayList<ChatPrompt> secondPrompts = new CopyOnWriteArrayList<>();
        ModelGateway secondGateway = finalOnlyGateway(secondPrompts, new AtomicInteger());
        CliSessionService.CliOptions resumeOpts = options(workspace, secondGateway);
        resumeOpts.resumeSessionId = sessionId;
        CliSessionService resumed = service(resumeOpts, secondGateway, List.of());
        try {
            resumed.runTurn("$review-pr second");
            assertTrue(secondPrompts.get(0).getSystemPrompt().contains("UPDATED_FOR_NEW_ROOT"));
            assertFalse(secondPrompts.get(0).getSystemPrompt().contains("ORIGINAL"));
        } finally {
            restoreHome(previousHome);
            resumed.close();
        }
    }

    private void prepareInterruptedRun(Path workspace, String runId) throws Exception {
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(workspace, mapper);
        Path dir = workspace.resolve(".loom-code/checkpoints").resolve(runId);
        AgentCheckpoint afterTool = null;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> jsonFiles = files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path file : jsonFiles) {
                JsonNode node = mapper.readTree(file.toFile());
                if ("after_tool".equals(node.path("reason").asText())) {
                    afterTool = mapper.treeToValue(node, AgentCheckpoint.class);
                }
            }
        }
        assertNotNull("expected after_tool checkpoint", afterTool);
        assertEquals(15, (int) afterTool.getContextSnapshot().getSchemaVersion());
        assertNotNull(afterTool.getContextSnapshot().getActiveSkills());
        assertFalse(afterTool.getContextSnapshot().getActiveSkills().isEmpty());

        // Make after_tool the latest recoverable checkpoint and reopen the Run.
        checkpoints.save(AgentCheckpoint.builder()
                .runId(runId)
                .currentNode("prompt_build")
                .contextSnapshot(afterTool.getContextSnapshot())
                .reason("after_tool")
                .build());

        FileAgentRunRepository runs = new FileAgentRunRepository(workspace, mapper);
        AgentRun run = runs.find(runId).orElseThrow();
        run.setStatus(AgentRunStatus.RUNNING);
        run.setStopReason(null);
        run.setFinalAnswer(null);
        runs.save(run);
    }

    private ModelGateway toolThenFinalGateway(CopyOnWriteArrayList<ChatPrompt> prompts,
                                              AtomicInteger calls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                prompts.add(prompt);
                int call = calls.getAndIncrement();
                if (call == 0) {
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{\"name\":\"write_file\",\"args\":{\"path\":\"note.txt\",\"content\":\"hi\"}}</tool>")
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

    private ModelGateway finalOnlyGateway(CopyOnWriteArrayList<ChatPrompt> prompts, AtomicInteger calls) {
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
                        .content("<final>continued</final>")
                        .finishReason("stop")
                        .actualModel("test")
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

    private CliSessionService.CliOptions options(Path workspace, ModelGateway gateway) {
        CliSessionService.CliOptions options = new CliSessionService.CliOptions();
        options.provider = "deepseek";
        options.model = "deepseek-v4-flash";
        options.baseUrl = "http://unused";
        options.apiKey = "";
        options.workspaceRoot = workspace.toString();
        options.approvalPolicy = "never";
        options.maxSteps = 8;
        options.maxNewTokens = 256;
        options.timeoutSeconds = 30;
        options.modelGateway = gateway;
        return options;
    }

    private CliSessionService service(CliSessionService.CliOptions options, ModelGateway gateway,
                                      List<AgentTool> tools) {
        Path root = Path.of(options.workspaceRoot);
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(root);
        agent.setApprovalPolicy(options.approvalPolicy);
        FileAgentSessionRepository sessions = new FileAgentSessionRepository(root, mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(root, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(root, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(root, mapper);
        AgentLoopService loop = CliLoopTestFixture.build(root, mapper, gateway, agent, List.of(), tools);
        return new CliSessionService(options, mapper, agent, new ModelRuntimeProperties(),
                sessions, runs, checkpoints, CliLoopTestFixture.historyRepository(root, mapper), traces, loop);
    }

    private static void restoreHome(String previousHome) {
        if (previousHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousHome);
        }
    }
}
