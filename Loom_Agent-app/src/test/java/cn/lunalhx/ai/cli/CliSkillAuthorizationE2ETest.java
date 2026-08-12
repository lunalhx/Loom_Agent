package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * CLI E2E seam: Skill guidance must not bypass ordinary Tool / Shell authorization.
 */
public class CliSkillAuthorizationE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void activationDoesNotExecuteScriptsOrExpandCatalogFromAllowedTools() throws Exception {
        Path home = Files.createTempDirectory("cli-skill-auth-home");
        Path workspace = Files.createTempDirectory("cli-skill-auth-workspace").toRealPath();
        Path marker = home.resolve("boom.marker");
        Path skillDir = workspace.resolve(".agents/skills/meta-skill");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: meta-skill
                description: Metadata must not authorize tools.
                allowed-tools: [run_shell, write_file]
                disallowed-tools: [read_file]
                ---
                Prefer write_file when needed.
                """, StandardCharsets.UTF_8);
        Path script = skillDir.resolve("scripts/boom.sh");
        Files.writeString(script, "#!/bin/sh\ntouch '" + marker + "'\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
        }

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelGateway gateway = finalGateway(prompts, modelCalls);
        CliSessionService service = service(options(workspace, gateway, "never"), gateway, List.of());
        try {
            AgentSession before = service.sessionRepository().find(service.sessionId()).orElseThrow();
            assertEquals("done", service.runTurn("$meta-skill finish"));
            assertFalse(Files.exists(marker));
            assertTrue(prompts.get(0).getSystemPrompt().contains("Active skills:"));
            assertFalse(prompts.get(0).getSystemPrompt().contains("name: run_shell"));
            assertFalse(prompts.get(0).getSystemPrompt().contains("name: write_file"));
            AgentSession after = service.sessionRepository().find(service.sessionId()).orElseThrow();
            assertEquals(before.getExecutionGrants(), after.getExecutionGrants());
            assertTrue(after.getExecutionGrants() == null || after.getExecutionGrants().isEmpty());
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    @Test
    public void skillGuidedWriteFollowsAllowAskDenyLikeOrdinaryCalls() throws Exception {
        Path home = Files.createTempDirectory("cli-skill-auth-policy-home");
        Path workspace = Files.createTempDirectory("cli-skill-auth-policy-ws").toRealPath();
        writeSkill(workspace.resolve(".agents/skills/writer"), "writer", "Writes files.",
                "Use write_file for notes.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        List<AgentTool> tools = List.of(new WriteFileTool(new LocalWorkspacePort()));

        // DENY default (never): skill-guided write rejected
        {
            CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            ModelGateway gateway = writeThenFinalGateway(prompts, calls);
            CliSessionService service = service(options(workspace, gateway, "never"), gateway, tools);
            try {
                assertEquals("done", service.runTurn("$writer jot a note"));
                assertFalse(Files.exists(workspace.resolve("note.txt")));
                assertTrue(modelVisible(prompts.get(1)).contains("permission_denied"));
            } finally {
                service.close();
            }
        }

        // ALLOW default (auto): skill-guided write executes
        {
            CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            ModelGateway gateway = writeThenFinalGateway(prompts, calls);
            CliSessionService service = service(options(workspace, gateway, "auto"), gateway, tools);
            try {
                assertEquals("done", service.runTurn("$writer jot a note"));
                assertTrue(Files.exists(workspace.resolve("note.txt")));
                assertTrue(modelVisible(prompts.get(1)).contains("<untrusted_tool_output>"));
                assertTrue(prompts.get(1).getSystemPrompt().contains("UNTRUSTED"));
            } finally {
                service.close();
            }
        }

        // ASK default: non-interactive approval denial (same as ordinary tools)
        {
            Files.deleteIfExists(workspace.resolve("note.txt"));
            CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            ModelGateway gateway = writeThenFinalGateway(prompts, calls);
            CliSessionService.CliOptions opts = options(workspace, gateway, "ask");
            opts.approvalPrompt = new CliSessionService.InteractiveApprovalPrompt(false);
            CliSessionService service = service(opts, gateway, tools);
            try {
                assertEquals("done", service.runTurn("$writer jot a note"));
                assertFalse(Files.exists(workspace.resolve("note.txt")));
                assertTrue(modelVisible(prompts.get(1)).contains("approval denied"));
            } finally {
                service.close();
            }
        }

        restoreHome(previousHome);
    }

    @Test
    public void skillDoesNotRelaxPlanModeMutationBoundary() throws Exception {
        Path home = Files.createTempDirectory("cli-skill-auth-plan-home");
        Path workspace = Files.createTempDirectory("cli-skill-auth-plan-ws").toRealPath();
        writeSkill(workspace.resolve(".agents/skills/planner-write"), "planner-write",
                "Plan skill.", "Still cannot mutate.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = writeThenFinalGateway(prompts, calls);
        CliSessionService.CliOptions opts = options(workspace, gateway, "auto");
        opts.startupMode = CollaborationMode.PLAN;
        CliSessionService service = service(opts, gateway,
                List.of(new WriteFileTool(new LocalWorkspacePort())));
        try {
            assertEquals("done", service.runTurn("$planner-write jot a note"));
            assertFalse(Files.exists(workspace.resolve("note.txt")));
            assertTrue(modelVisible(prompts.get(1)).contains("plan_mode_denied"));
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    @Test
    public void fullAccessWithSkillUsesOrdinaryLaunchProfileNotSkillHostMode() throws Exception {
        Path home = Files.createTempDirectory("cli-skill-auth-full-home");
        Path workspace = Files.createTempDirectory("cli-skill-auth-full-ws").toRealPath();
        writeSkill(workspace.resolve(".agents/skills/full-guide"), "full-guide",
                "Full access skill.", "Body.");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = finalGateway(prompts, calls);
        CliSessionService.CliOptions opts = options(workspace, gateway, "ask");
        opts.fullAccess = true;
        FileAgentCheckpointRepository checkpoints =
                new FileAgentCheckpointRepository(workspace, mapper);
        CliSessionService service = service(opts, gateway, List.of(), checkpoints);
        try {
            AgentSession before = service.sessionRepository().find(service.sessionId()).orElseThrow();
            assertEquals("done", service.runTurn("$full-guide finish"));
            assertTrue(prompts.get(0).getSystemPrompt().contains("Active skills:"));
            var snapshot = checkpoints.latest(
                            service.runRepository().findLatestRootByConversationId(service.sessionId())
                                    .orElseThrow().getRunId())
                    .orElseThrow().getContextSnapshot();
            assertEquals(Boolean.TRUE, snapshot.getFullAccess());
            AgentSession after = service.sessionRepository().find(service.sessionId()).orElseThrow();
            assertEquals(before.getExecutionGrants(), after.getExecutionGrants());
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    private ModelGateway writeThenFinalGateway(CopyOnWriteArrayList<ChatPrompt> prompts,
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
                                    <tool>{"name":"write_file","args":{"path":"note.txt","content":"hi"}}</tool>
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

    private ModelGateway finalGateway(CopyOnWriteArrayList<ChatPrompt> prompts, AtomicInteger calls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                prompts.add(prompt);
                calls.getAndIncrement();
                return Mono.just(ModelChatResult.builder()
                        .content("<final>done</final>")
                        .finishReason("stop")
                        .actualModel("test")
                        .build());
            }
        };
    }

    private CliSessionService service(CliSessionService.CliOptions options, ModelGateway gateway,
                                      List<AgentTool> tools) {
        return service(options, gateway, tools,
                new FileAgentCheckpointRepository(Path.of(options.workspaceRoot), mapper));
    }

    private CliSessionService service(CliSessionService.CliOptions options, ModelGateway gateway,
                                      List<AgentTool> tools,
                                      FileAgentCheckpointRepository checkpoints) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(Path.of(options.workspaceRoot));
        agent.setApprovalPolicy(options.approvalPolicy);
        AgentSessionRepository sessions = new FileAgentSessionRepository(Path.of(options.workspaceRoot), mapper);
        FileAgentRunRepository runs = new FileAgentRunRepository(Path.of(options.workspaceRoot), mapper);
        FileTraceRecorder traces = new FileTraceRecorder(Path.of(options.workspaceRoot), mapper);
        AgentLoopService loop = CliLoopTestFixture.build(Path.of(options.workspaceRoot), mapper,
                gateway, agent, List.of(), tools);
        return new CliSessionService(options, mapper, agent, new ModelRuntimeProperties(),
                sessions, runs, checkpoints, traces, loop);
    }

    private CliSessionService.CliOptions options(Path workspace, ModelGateway gateway, String approval) {
        CliSessionService.CliOptions options = new CliSessionService.CliOptions();
        options.provider = "deepseek";
        options.model = "deepseek-v4-flash";
        options.baseUrl = "http://unused";
        options.apiKey = "";
        options.workspaceRoot = workspace.toString();
        options.approvalPolicy = approval;
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
}
