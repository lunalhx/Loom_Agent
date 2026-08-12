package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CliExplicitSkillActivationE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void explicitSelectorActivatesFullInstructionsBeforeFirstModelCall() throws Exception {
        Path home = Files.createTempDirectory("cli-skill-act-home");
        Path workspace = Files.createTempDirectory("cli-skill-act-workspace").toRealPath();
        writeSkill(workspace.resolve(".agents/skills/review-pr"), "review-pr",
                "Review pull requests carefully.",
                "Always check tests first.\nNever skip edge cases.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = recordingGateway(prompts, modelCalls);
        CliSessionService service = service(options(workspace, gateway), gateway);
        try {
            AgentSession before = service.sessionRepository().find(service.sessionId()).orElseThrow();
            String answer = service.runTurn("$review-pr please review this change");

            assertEquals("done", answer);
            assertEquals(1, modelCalls.get());
            assertEquals(1, prompts.size());
            ChatPrompt prompt = prompts.get(0);
            assertTrue(prompt.getSystemPrompt().contains("Active skills:"));
            assertTrue(prompt.getSystemPrompt().contains("name: review-pr"));
            assertTrue(prompt.getSystemPrompt().contains("source: project .agents/skills/review-pr"));
            assertTrue(prompt.getSystemPrompt().contains("Always check tests first."));
            assertTrue(prompt.getSystemPrompt().contains("Never skip edge cases."));
            assertTrue(prompt.getSystemPrompt().indexOf("Active skills:")
                    > prompt.getSystemPrompt().indexOf("Collaboration mode:"));
            String visible = modelVisible(prompt);
            assertTrue(visible.contains("please review this change"));
            assertFalse(visible.contains("$review-pr"));
            assertEquals(0, (int) service.runRepository().findLatestRootByConversationId(service.sessionId())
                    .orElseThrow().getToolSteps());

            AgentSession after = service.sessionRepository().find(service.sessionId()).orElseThrow();
            assertEquals(before.getCollaborationMode(), after.getCollaborationMode());
            assertEquals(before.getPlanStateVersion(), after.getPlanStateVersion());
            assertEquals(before.getCurrentPlanId(), after.getCurrentPlanId());
            assertEquals(before.getExecutionGrants(), after.getExecutionGrants());

            String next = service.runTurn("follow up without a skill");
            assertEquals("done", next);
            assertEquals(2, prompts.size());
            assertFalse(prompts.get(1).getSystemPrompt().contains("Active skills:"));
            assertFalse(prompts.get(1).getSystemPrompt().contains("Always check tests first."));
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    @Test
    public void unknownForbiddenOrEmptyTaskFailsBeforeModelCall() throws Exception {
        Path home = Files.createTempDirectory("cli-skill-fail-home");
        Path workspace = Files.createTempDirectory("cli-skill-fail-workspace").toRealPath();
        writeRestrictedSkill(workspace.resolve(".agents/skills/manual-only"), """
                ---
                name: manual-only
                description: Manual only.
                user-invocable: false
                ---
                Secret manual body.
                """);
        writeSkill(workspace.resolve(".agents/skills/ok-skill"), "ok-skill", "Ok.", "Body.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = countingGateway(modelCalls);
        CliSessionService service = service(options(workspace, gateway), gateway);
        try {
            String unknown = service.runTurn("$missing-skill do work");
            assertTrue(unknown.startsWith("error:"));
            assertTrue(unknown.contains("missing-skill"));
            assertEquals(0, modelCalls.get());

            String forbidden = service.runTurn("$manual-only do work");
            assertTrue(forbidden.startsWith("error:"));
            assertTrue(forbidden.toLowerCase().contains("user") || forbidden.contains("manual-only"));
            assertEquals(0, modelCalls.get());

            String empty = service.runTurn("$ok-skill");
            assertTrue(empty.startsWith("error:"));
            assertEquals(0, modelCalls.get());
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    @Test
    public void catalogAndActiveBodyStayFrozenAfterDiskChange() throws Exception {
        Path home = Files.createTempDirectory("cli-skill-freeze-home");
        Path workspace = Files.createTempDirectory("cli-skill-freeze-workspace").toRealPath();
        Path skillDir = workspace.resolve(".agents/skills/freeze-skill");
        writeSkill(skillDir, "freeze-skill", "Frozen skill.", "Original frozen body.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = formatRetryThenFinalGateway(prompts, modelCalls, skillDir);
        CliSessionService service = service(options(workspace, gateway), gateway);
        try {
            String answer = service.runTurn("$freeze-skill inspect then finish");
            assertEquals("done", answer);
            assertEquals(2, modelCalls.get());
            assertEquals(2, prompts.size());
            assertTrue(prompts.get(0).getSystemPrompt().contains("Original frozen body."));
            assertTrue(prompts.get(1).getSystemPrompt().contains("Original frozen body."));
            assertFalse(prompts.get(1).getSystemPrompt().contains("Mutated body after start."));
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    @Test
    public void duplicateSelectorsActivateOnceInAppearanceOrder() throws Exception {
        Path home = Files.createTempDirectory("cli-skill-dedupe-home");
        Path workspace = Files.createTempDirectory("cli-skill-dedupe-workspace").toRealPath();
        writeSkill(workspace.resolve(".agents/skills/alpha"), "alpha", "A.", "Alpha body.");
        writeSkill(workspace.resolve(".agents/skills/beta"), "beta", "B.", "Beta body.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = recordingGateway(prompts, modelCalls);
        CliSessionService service = service(options(workspace, gateway), gateway);
        try {
            assertEquals("done", service.runTurn("$beta $alpha $beta finish both"));
            String system = prompts.get(0).getSystemPrompt();
            String activeSection = system.substring(system.indexOf("Active skills:"));
            int betaAt = activeSection.indexOf("name: beta");
            int alphaAt = activeSection.indexOf("name: alpha");
            assertTrue(betaAt >= 0);
            assertTrue(alphaAt >= 0);
            assertTrue(betaAt < alphaAt);
            assertEquals(1, countOccurrences(activeSection, "Alpha body."));
            assertEquals(1, countOccurrences(activeSection, "Beta body."));
            assertFalse(modelVisible(prompts.get(0)).contains("$"));
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    private ModelGateway formatRetryThenFinalGateway(CopyOnWriteArrayList<ChatPrompt> prompts,
                                                     AtomicInteger calls,
                                                     Path skillDir) {
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
                    try {
                        writeSkill(skillDir, "freeze-skill", "Frozen skill.", "Mutated body after start.");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return Mono.just(ModelChatResult.builder()
                            .content("<tool>{not-json}</tool>")
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

    private ModelGateway recordingGateway(CopyOnWriteArrayList<ChatPrompt> prompts, AtomicInteger calls) {
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
                        .content("<final>done</final>")
                        .finishReason("stop")
                        .actualModel("test")
                        .build());
            }
        };
    }

    private ModelGateway countingGateway(AtomicInteger calls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content("<final>done</final>")
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
        return new CliSessionService(options, mapper, agent, new ModelRuntimeProperties(),
                sessions, runs, checkpoints, traces, loop);
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

    private static String modelVisible(ChatPrompt prompt) {
        StringBuilder sb = new StringBuilder();
        if (prompt.getSystemPrompt() != null) {
            sb.append(prompt.getSystemPrompt()).append('\n');
        }
        if (prompt.getMessages() != null) {
            for (var message : prompt.getMessages()) {
                if (message.getContent() != null) {
                    sb.append(message.getContent()).append('\n');
                }
            }
        }
        return sb.toString();
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
