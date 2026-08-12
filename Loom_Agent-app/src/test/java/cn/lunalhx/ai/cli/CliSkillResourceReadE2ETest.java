package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.infrastructure.loom.ReadSkillResourceTool;
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

public class CliSkillResourceReadE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void explicitActivationExposesToolAndReadsIndexedResource() throws Exception {
        Path home = Files.createTempDirectory("cli-skill-resource-home");
        Path workspace = Files.createTempDirectory("cli-skill-resource-workspace").toRealPath();
        Path skillDir = workspace.resolve(".agents/skills/doc-skill");
        writeSkillWithResource(skillDir, "doc-skill", "Docs.", "Use references.", "Detailed guide.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = readThenFinalGateway(prompts, modelCalls);
        CliSessionService service = service(options(workspace, gateway), gateway);
        try {
            String answer = service.runTurn("$doc-skill read the guide");
            assertEquals("done", answer);
            assertEquals(2, modelCalls.get());
            assertTrue(prompts.get(0).getSystemPrompt().contains("read_skill_resource"));
            String visible = modelVisible(prompts.get(1));
            assertTrue(visible.contains("[tool:read_skill_resource]"));
            assertTrue(visible.contains("<untrusted_tool_output>"));
            assertEquals(1, (int) service.runRepository().findLatestRootByConversationId(service.sessionId())
                    .orElseThrow().getToolSteps());
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    @Test
    public void skillWithoutResourcesDoesNotExposeReadSkillResource() throws Exception {
        Path home = Files.createTempDirectory("cli-skill-no-resource-home");
        Path workspace = Files.createTempDirectory("cli-skill-no-resource-workspace").toRealPath();
        writeSkill(workspace.resolve(".agents/skills/plain"), "plain", "Plain.", "Body only.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = finalOnlyGateway(prompts, modelCalls);
        CliSessionService service = service(options(workspace, gateway), gateway);
        try {
            assertEquals("done", service.runTurn("$plain finish"));
            assertFalse(prompts.get(0).getSystemPrompt().contains("read_skill_resource"));
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    @Test
    public void planModeAllowsAnActiveSkillToReadItsIndexedProjectResource() throws Exception {
        Path home = Files.createTempDirectory("cli-plan-skill-resource-home");
        Path workspace = Files.createTempDirectory("cli-plan-skill-resource-workspace").toRealPath();
        Path skillDir = workspace.resolve(".agents/skills/doc-skill");
        writeSkillWithResource(skillDir, "doc-skill", "Docs.", "Use references.", "Detailed guide.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = readThenFinalGateway(prompts, modelCalls);
        CliSessionService service = service(options(workspace, gateway), gateway);
        try {
            service.setCollaborationMode(CollaborationMode.PLAN);

            assertEquals("done", service.runTurn("$doc-skill read the guide"));
            assertEquals(2, modelCalls.get());
            assertTrue(prompts.get(0).getSystemPrompt().contains("read_skill_resource"));
            assertTrue(modelVisible(prompts.get(1)).contains("[tool:read_skill_resource]"));
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    private ModelGateway readThenFinalGateway(CopyOnWriteArrayList<ChatPrompt> prompts,
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
                            .content("""
                                    <tool>{"name":"read_skill_resource","args":{"skill":"doc-skill","path":"references/guide.md"}}</tool>
                                    """)
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

    private ModelGateway finalOnlyGateway(CopyOnWriteArrayList<ChatPrompt> prompts,
                                          AtomicInteger calls) {
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

    private CliSessionService service(CliSessionService.CliOptions options, ModelGateway gateway) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(Path.of(options.workspaceRoot));
        AgentSessionRepository sessions = new FileAgentSessionRepository(Path.of(options.workspaceRoot), mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(Path.of(options.workspaceRoot), mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(Path.of(options.workspaceRoot), mapper);
        FileTraceRecorder traces = new FileTraceRecorder(Path.of(options.workspaceRoot), mapper);
        List<AgentTool> tools = List.of(new ReadSkillResourceTool());
        AgentLoopService loop = CliLoopTestFixture.build(Path.of(options.workspaceRoot), mapper,
                gateway, agent, List.of(), tools);
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

    private static void writeSkillWithResource(Path root, String name, String description,
                                               String body, String resourceBody) throws Exception {
        writeSkill(root, name, description, body);
        Path ref = root.resolve("references/guide.md");
        Files.createDirectories(ref.getParent());
        Files.writeString(ref, resourceBody, StandardCharsets.UTF_8);
    }
}
