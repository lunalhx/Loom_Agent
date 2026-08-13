package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CliSkillsE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void skillsControlListsCatalogWithoutStartingARun() throws Exception {
        Path home = Files.createTempDirectory("cli-skills-home");
        Path workspace = Files.createTempDirectory("cli-skills-workspace").toRealPath();
        writeSkill(workspace.resolve(".agents/skills/project-skill"), "project-skill", "Project skill.");
        writeSkill(home.resolve(".agents/skills/user-skill"), "user-skill", "User skill.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService service = service(options(workspace, finalGateway(modelCalls)), finalGateway(modelCalls));
        try {
            String sessionId = service.sessionId();
            AgentSession before = service.sessionRepository().find(sessionId).orElseThrow();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            assertTrue(CliMain.handleControl(service, "/skills",
                    new PrintStream(bytes, true, StandardCharsets.UTF_8)));

            String output = bytes.toString(StandardCharsets.UTF_8);
            assertEquals(0, modelCalls.get());
            assertEquals(sessionId, service.sessionId());
            AgentSession after = service.sessionRepository().find(sessionId).orElseThrow();
            assertEquals(before.getCollaborationMode(), after.getCollaborationMode());
            assertEquals(before.getPlanStateVersion(), after.getPlanStateVersion());
            assertEquals(before.getCurrentPlanId(), after.getCurrentPlanId());
            assertTrue(service.runRepository().findLatestRootByConversationId(sessionId).isEmpty());
            assertTrue(output.contains("name: project-skill"));
            assertTrue(output.contains("source: project .agents/skills/project-skill"));
            assertTrue(output.contains("name: user-skill"));
            assertTrue(output.contains("source: user .agents/skills/user-skill"));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            service.close();
        }
    }

    @Test
    public void skillsControlShowsInvocationRestrictions() throws Exception {
        Path home = Files.createTempDirectory("cli-skills-invocation-home");
        Path workspace = Files.createTempDirectory("cli-skills-invocation-workspace").toRealPath();
        writeRestrictedSkill(workspace.resolve(".agents/skills/manual-only"), """
                ---
                name: manual-only
                description: Manual workflow.
                disable-model-invocation: true
                user-invocable: false
                ---
                body
                """);
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService service = service(options(workspace, finalGateway(modelCalls)), finalGateway(modelCalls));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(service, "/skills",
                    new PrintStream(bytes, true, StandardCharsets.UTF_8)));

            String output = bytes.toString(StandardCharsets.UTF_8);
            assertEquals(0, modelCalls.get());
            assertTrue(output.contains("name: manual-only"));
            assertTrue(output.contains("invocation: none"));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            service.close();
        }
    }

    @Test
    public void skillsControlReportsShadowingAndCompatibilityDiagnostics() throws Exception {
        Path home = Files.createTempDirectory("cli-skills-shadow-home");
        Path workspace = Files.createTempDirectory("cli-skills-shadow-workspace").toRealPath();
        writeSkillWithExtraFrontmatter(home.resolve(".agents/skills/shared"), "shared", "User wins.",
                "allowed-tools: [run_shell]\n");
        writeSkill(workspace.resolve(".claude/skills/shared"), "shared", "Project claude loses.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService service = service(options(workspace, finalGateway(modelCalls)), finalGateway(modelCalls));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(CliMain.handleControl(service, "/skills",
                    new PrintStream(bytes, true, StandardCharsets.UTF_8)));

            String output = bytes.toString(StandardCharsets.UTF_8);
            assertEquals(0, modelCalls.get());
            assertTrue(output.contains("source: user .agents/skills/shared"));
            assertTrue(output.contains("shadowed_by: user .agents/skills/shared"));
            assertTrue(output.contains("source: project .claude/skills/shared"));
            assertTrue(output.contains("compatibility: unsupported Claude field ignored: allowed-tools"));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            service.close();
        }
    }

    private CliSessionService service(CliSessionService.CliOptions options, ModelGateway gateway) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(Path.of(options.workspaceRoot));
        AgentSessionRepository sessions = new FileAgentSessionRepository(Path.of(options.workspaceRoot), mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(Path.of(options.workspaceRoot), mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(Path.of(options.workspaceRoot), mapper);
        FileTraceRecorder traces = new FileTraceRecorder(Path.of(options.workspaceRoot), mapper);
        AgentLoopService loop = CliLoopTestFixture.build(Path.of(options.workspaceRoot), mapper,
                gateway, agent, java.util.List.of(), java.util.List.of());
        return new CliSessionService(options, mapper, agent, new ModelRuntimeProperties(),
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

    private ModelGateway finalGateway(AtomicInteger calls) {
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

    private static void writeSkill(Path root, String name, String description) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                Instruction body.
                """.formatted(name, description), StandardCharsets.UTF_8);
    }

    private static void writeRestrictedSkill(Path root, String content) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
    }

    private static void writeSkillWithExtraFrontmatter(Path root, String name, String description,
                                                       String extraFrontmatter) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                %s---
                Instruction body.
                """.formatted(name, description, extraFrontmatter), StandardCharsets.UTF_8);
    }
}
