package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.config.DelegateService;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.infrastructure.loom.DelegateTool;
import cn.lunalhx.ai.infrastructure.loom.ReadFileTool;
import cn.lunalhx.ai.infrastructure.loom.ReadSkillResourceTool;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import cn.lunalhx.ai.test.AgentRuntimeTestFixture;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * CLI E2E: Delegate inherits frozen catalog + active skills, may activate locally,
 * can read resources introduced by local activation, and never mutates the parent prompt/state.
 */
public class CliDelegateSkillInheritanceE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void delegateInheritsFrozenSkillsAllowsLocalActivationAndKeepsParentUnchanged()
            throws Exception {
        Path home = Files.createTempDirectory("cli-delegate-skill-home");
        Path workspace = Files.createTempDirectory("cli-delegate-skill-workspace").toRealPath();
        Path parentSkill = workspace.resolve(".agents/skills/parent-method");
        Path childSkill = workspace.resolve(".agents/skills/child-extra");
        writeSkill(parentSkill, "parent-method",
                "Parent working method.",
                "PARENT_BODY_V1: follow the parent checklist.");
        writeSkillWithResource(childSkill, "child-extra",
                "Child specialization.",
                "CHILD_BODY: specialized subtask guidance.",
                "CHILD_REF_V1");

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());

        CopyOnWriteArrayList<ChatPrompt> prompts = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<Path> parentSkillRef = new AtomicReference<>(parentSkill);
        ModelGateway gateway = sequencedGateway(prompts, modelCalls, parentSkillRef, childSkill);

        CliSessionService service = serviceWithDelegate(workspace, gateway);
        try {
            assertEquals("done", service.runTurn("$parent-method investigate via delegate"));
            assertEquals(5, modelCalls.get());
            assertEquals(5, prompts.size());

            ChatPrompt parentBefore = prompts.get(0);
            assertTrue(parentBefore.getSystemPrompt().contains("PARENT_BODY_V1"));
            assertTrue(parentBefore.getSystemPrompt().contains("name: child-extra"));
            assertFalse(parentBefore.getSystemPrompt().contains("CHILD_BODY"));

            ChatPrompt childFirst = prompts.get(1);
            assertTrue(childFirst.getSystemPrompt().contains("PARENT_BODY_V1"));
            assertFalse(childFirst.getSystemPrompt().contains("PARENT_BODY_DRIFTED"));
            assertTrue(childFirst.getSystemPrompt().contains("name: child-extra"));
            assertFalse(childFirst.getSystemPrompt().contains("CHILD_BODY"));
            assertFalse(childFirst.getSystemPrompt().contains("ghost-skill"));
            String childTools = toolsCatalog(childFirst.getSystemPrompt());
            assertTrue(childTools.contains("- read_file("));
            assertFalse(childTools.contains("- read_skill_resource("));
            assertFalse(childTools.contains("- write_file("));
            assertFalse(childTools.contains("- patch_file("));
            assertFalse(childTools.contains("- run_shell("));
            assertFalse(childTools.contains("- delegate("));

            ChatPrompt childAfterActivation = prompts.get(2);
            assertTrue(childAfterActivation.getSystemPrompt().contains("PARENT_BODY_V1"));
            assertTrue(childAfterActivation.getSystemPrompt().contains("CHILD_BODY"));
            assertTrue(toolsCatalog(childAfterActivation.getSystemPrompt()).contains("- read_skill_resource("));

            String childVisible = modelVisible(prompts.get(3));
            assertTrue(childVisible.contains("[tool:read_skill_resource]"));
            assertTrue(childVisible.contains("CHILD_REF_V1"));
            assertTrue(childVisible.contains("<untrusted_tool_output>"));
            assertFalse(childVisible.contains("no_active_skill"));

            ChatPrompt parentAfter = prompts.get(4);
            assertTrue(parentAfter.getSystemPrompt().contains("PARENT_BODY_V1"));
            assertFalse(parentAfter.getSystemPrompt().contains("CHILD_BODY"));
            assertFalse(parentAfter.getSystemPrompt().contains("PARENT_BODY_DRIFTED"));
        } finally {
            restoreHome(previousHome);
            service.close();
        }
    }

    private static String toolsCatalog(String systemPrompt) {
        int start = systemPrompt.indexOf("\nTools:\n");
        if (start < 0) {
            return "";
        }
        int end = systemPrompt.indexOf("\nAvailable skills:", start);
        if (end < 0) {
            end = systemPrompt.indexOf("\nActive skills:", start);
        }
        if (end < 0) {
            end = systemPrompt.length();
        }
        return systemPrompt.substring(start, end);
    }

    private ModelGateway sequencedGateway(CopyOnWriteArrayList<ChatPrompt> prompts,
                                          AtomicInteger calls,
                                          AtomicReference<Path> parentSkill,
                                          Path childSkill) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                prompts.add(prompt);
                int call = calls.getAndIncrement();
                String content = switch (call) {
                    case 0 -> {
                        mutateAfterParentSeesFrozenSnapshot(parentSkill.get(), childSkill);
                        yield "<tool>{\"name\":\"delegate\",\"args\":{\"task\":\"specialize and read parent ref\",\"max_steps\":3}}</tool>";
                    }
                    case 1 -> "<skill_activation>{\"name\":\"child-extra\"}</skill_activation>";
                    case 2 -> "<tool>{\"name\":\"read_skill_resource\",\"args\":{\"skill\":\"child-extra\",\"path\":\"references/guide.md\"}}</tool>";
                    case 3 -> "<final>child done</final>";
                    default -> "<final>done</final>";
                };
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .actualModel("test")
                        .build());
            }
        };
    }

    private static void mutateAfterParentSeesFrozenSnapshot(Path parentSkill, Path childSkill) {
        try {
            // Drift instruction bodies and add a mid-run package; leave indexed
            // resources intact so inherited resource reads still resolve by identity.
            Files.writeString(parentSkill.resolve("SKILL.md"), """
                    ---
                    name: parent-method
                    description: Parent working method.
                    ---
                    PARENT_BODY_DRIFTED: should not appear in any branch.
                    """, StandardCharsets.UTF_8);
            Path ghost = childSkill.getParent().resolve("ghost-skill");
            writeSkill(ghost, "ghost-skill", "Appeared mid-run.", "GHOST_BODY");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CliSessionService serviceWithDelegate(Path workspace, ModelGateway gateway) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        Path root = workspace.toAbsolutePath().normalize();
        FileAgentRunRepository runs = new FileAgentRunRepository(root, mapper);
        FileAgentSessionRepository sessions = new FileAgentSessionRepository(root, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(root, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(root, mapper);

        AgentLoopStateDependencies state = new AgentLoopStateDependencies(
                new cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver(agent),
                runs, checkpoints,
                new cn.lunalhx.ai.infrastructure.store.FileConversationHistoryRepository(root, mapper),
                new cn.lunalhx.ai.infrastructure.store.FileAttemptLeaseRepository(root, mapper),
                mapper);
        AgentLoopRuntimeDependencies runtime = new AgentLoopRuntimeDependencies(
                agent, traces,
                new cn.lunalhx.ai.domain.agent.service.budget.DefaultBudgetGuard(agent),
                new cn.lunalhx.ai.domain.agent.service.observability.NoopAgentMetrics(),
                new cn.lunalhx.ai.infrastructure.tool.NoopToolOutputSanitizer(),
                AgentRuntimeTestFixture.testModelRuntimeProperties());
        AgentLoopFactory factory = new AgentLoopFactory(
                gateway, state, runtime, new ConversationHistoryAppendService(),
                new ContextManager(agent), new ConversationExecutionGuard(), null,
                new FilePlanSubmissionHandler(sessions, runs, mapper));

        AtomicReference<ToolRegistry> registryRef = new AtomicReference<>();
        ObjectProvider<ToolRegistry> provider = new ObjectProvider<>() {
            @Override
            public ToolRegistry getObject() {
                return registryRef.get();
            }

            @Override
            public ToolRegistry getObject(Object... args) {
                return getObject();
            }

            @Override
            public Stream<ToolRegistry> stream() {
                return Stream.of(getObject());
            }

            @Override
            public Stream<ToolRegistry> orderedStream() {
                return stream();
            }
        };
        DelegateService delegateService = new DelegateService(factory, provider, runs);
        List<AgentTool> tools = List.of(
                new ReadFileTool(new LocalWorkspacePort()),
                new ReadSkillResourceTool(),
                new DelegateTool(delegateService));
        ToolRegistry registry = new ToolRegistry(tools, new ToolSchemaValidator(mapper));
        registryRef.set(registry);
        AgentLoopService loop = factory.createStandalone(registry, Runnable::run);

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
        return new CliSessionService(options, mapper, agent, new ModelRuntimeProperties(),
                sessions, runs, checkpoints, CliLoopTestFixture.historyRepository(root, mapper), traces, loop);
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

    private static void writeSkill(Path root, String name, String description, String body)
            throws Exception {
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
