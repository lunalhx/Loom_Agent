package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CliImplicitSkillActivationE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void implicitActivationLoadsFullBodyOnNextRoundWithoutToolStep() throws Exception {
        Path home = Files.createTempDirectory("cli-implicit-home");
        Path workspace = Files.createTempDirectory("cli-implicit-workspace").toRealPath();
        writeSkill(workspace.resolve(".agents/skills/review-pr"), "review-pr",
                "Review pull requests carefully.",
                "Always check tests first.\nNever skip edge cases.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = activateThenFinalGateway(prompts, modelCalls);
        CliSessionService service = service(options(workspace, gateway), gateway);
        try {
            String answer = service.runTurn("please review this change");
            assertEquals("done", answer);
            assertEquals(2, modelCalls.get());
            assertEquals(2, prompts.size());

            ChatPrompt first = prompts.get(0);
            assertTrue(first.getSystemPrompt().contains("Available skills:"));
            assertTrue(first.getSystemPrompt().contains("name: review-pr"));
            assertTrue(first.getSystemPrompt().contains("Review pull requests carefully."));
            assertFalse(first.getSystemPrompt().contains("Always check tests first."));

            ChatPrompt second = prompts.get(1);
            assertTrue(second.getSystemPrompt().contains("Active skills:"));
            assertTrue(second.getSystemPrompt().contains("Always check tests first."));
            assertTrue(second.getSystemPrompt().contains("Never skip edge cases."));
            assertNotEquals(first.getStablePrefixSignature(), second.getStablePrefixSignature());
            assertEquals(0, (int) service.runRepository().findLatestRootByConversationId(service.sessionId())
                    .orElseThrow().getToolSteps());
            assertActivationCheckpoint(workspace, service.runRepository()
                    .findLatestRootByConversationId(service.sessionId()).orElseThrow().getRunId());
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    @Test
    public void invalidImplicitActivationRetriesWithoutToolStep() throws Exception {
        Path home = Files.createTempDirectory("cli-implicit-retry-home");
        Path workspace = Files.createTempDirectory("cli-implicit-retry-workspace").toRealPath();
        writeRestrictedSkill(workspace.resolve(".agents/skills/manual-only"), """
                ---
                name: manual-only
                description: Manual only.
                disable-model-invocation: true
                ---
                Secret manual body.
                """);
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = invalidThenFinalGateway(prompts, modelCalls);
        CliSessionService service = service(options(workspace, gateway), gateway);
        try {
            assertEquals("done", service.runTurn("use the manual skill"));
            assertEquals(2, modelCalls.get());
            assertFalse(prompts.get(0).getSystemPrompt().contains("Secret manual body."));
            assertFalse(prompts.get(1).getSystemPrompt().contains("Active skills:"));
            assertEquals(0, (int) service.runRepository().findLatestRootByConversationId(service.sessionId())
                    .orElseThrow().getToolSteps());
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    @Test
    public void duplicateImplicitActivationDoesNotDuplicateContext() throws Exception {
        Path home = Files.createTempDirectory("cli-implicit-dedupe-home");
        Path workspace = Files.createTempDirectory("cli-implicit-dedupe-workspace").toRealPath();
        writeSkill(workspace.resolve(".agents/skills/review-pr"), "review-pr",
                "Review pull requests.", "Alpha body.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = activateTwiceThenFinalGateway(prompts, modelCalls);
        CliSessionService service = service(options(workspace, gateway), gateway);
        try {
            assertEquals("done", service.runTurn("review twice"));
            assertEquals(3, modelCalls.get());
            String activeSection = prompts.get(2).getSystemPrompt();
            assertEquals(1, countOccurrences(activeSection, "Alpha body."));
            assertEquals(0, (int) service.runRepository().findLatestRootByConversationId(service.sessionId())
                    .orElseThrow().getToolSteps());
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    private ModelGateway activateThenFinalGateway(CopyOnWriteArrayList<ChatPrompt> prompts,
                                                  AtomicInteger calls) {
        return gateway(prompts, calls, List.of(
                "<skill_activation>{\"name\":\"review-pr\"}</skill_activation>",
                "<final>done</final>"));
    }

    private ModelGateway invalidThenFinalGateway(CopyOnWriteArrayList<ChatPrompt> prompts,
                                                 AtomicInteger calls) {
        return gateway(prompts, calls, List.of(
                "<skill_activation>{\"name\":\"manual-only\"}</skill_activation>",
                "<final>done</final>"));
    }

    private ModelGateway activateTwiceThenFinalGateway(CopyOnWriteArrayList<ChatPrompt> prompts,
                                                       AtomicInteger calls) {
        return gateway(prompts, calls, List.of(
                "<skill_activation>{\"name\":\"review-pr\"}</skill_activation>",
                "<skill_activation>{\"name\":\"review-pr\"}</skill_activation>",
                "<final>done</final>"));
    }

    private ModelGateway gateway(CopyOnWriteArrayList<ChatPrompt> prompts,
                                 AtomicInteger calls,
                                 List<String> responses) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                prompts.add(prompt);
                int call = calls.getAndIncrement();
                String content = call < responses.size() ? responses.get(call) : "<final>done</final>";
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .actualModel("test")
                        .build());
            }
        };
    }

    private CliSessionService service(CliSessionService.CliOptions options, ModelGateway gateway) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(Path.of(options.workspaceRoot));
        AgentSessionRepository sessions = new FileAgentSessionRepository(Path.of(options.workspaceRoot), mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(Path.of(options.workspaceRoot), mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(Path.of(options.workspaceRoot), mapper);
        FileTraceRecorder traces = new FileTraceRecorder(Path.of(options.workspaceRoot), mapper);
        AgentLoopService loop = CliLoopTestFixture.build(Path.of(options.workspaceRoot), mapper,
                gateway, agent, List.of(), List.of());
        return new CliSessionService(options, mapper, agent, new cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties(),
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private void assertActivationCheckpoint(Path workspace, String runId) throws Exception {
        Path directory = workspace.resolve(".loom-code/checkpoints").resolve(runId);
        boolean found = false;
        try (Stream<Path> checkpoints = Files.list(directory)) {
            for (Path checkpoint : checkpoints.filter(path -> path.toString().endsWith(".json")).toList()) {
                var node = mapper.readTree(checkpoint.toFile());
                if ("after_skill_activation".equals(node.path("reason").asText())
                        && node.path("contextSnapshot").path("activeSkills").size() == 1) {
                    found = true;
                }
            }
        }
        assertTrue("implicit activation must be checkpointed before the next round", found);
    }

    private static void restoreHome(String previousHome) {
        if (previousHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousHome);
        }
    }

    private static void writeSkill(Path root, String name, String description, String body) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                %s
                """.formatted(name, description, body), StandardCharsets.UTF_8);
    }

    private static void writeRestrictedSkill(Path root, String content) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
    }
}
