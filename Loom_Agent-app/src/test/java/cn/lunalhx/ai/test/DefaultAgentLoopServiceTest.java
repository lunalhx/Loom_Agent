package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.agent.service.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryAgentRunRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryApprovalStore;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.infrastructure.tool.TodoWriteTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DefaultAgentLoopServiceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldCallToolsAndReturnFinalAnswer() {
        List<String> prompts = new java.util.ArrayList<>();
        ModelGateway modelGateway = completeGateway(
                prompts,
                "{\"type\":\"action\",\"thought\":\"搜索函数\",\"tool\":\"code_search\",\"input\":{\"query\":\"DefaultChatStreamService.stream\",\"limit\":10}}",
                "{\"type\":\"action\",\"thought\":\"读取文件\",\"tool\":\"read_file\",\"input\":{\"path\":\"Loom_Agent-domain/src/main/java/cn/lunalhx/ai/domain/conversation/service/DefaultChatStreamService.java\",\"startLine\":42,\"endLine\":80}}",
                "{\"type\":\"final\",\"answer\":\"DefaultChatStreamService.stream 定义在 DefaultChatStreamService.java，负责归一化请求、调用模型流并输出 SSE 事件。\",\"evidence\":[{\"file\":\"DefaultChatStreamService.java\",\"line\":42}]}"
        );

        DefaultAgentLoopService service = newService(modelGateway, List.of(
                fakeTool("code_search", "DefaultChatStreamService.java:42: public Flux<StreamEvent> stream(ChatPrompt rawPrompt)"),
                fakeTool("read_file", "42: public Flux<StreamEvent> stream(ChatPrompt rawPrompt) {")
        ));

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("DefaultChatStreamService.stream 在哪里定义？做什么用？")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());
        assertTrue(types.contains(AgentEventType.TOOL_CALL));
        assertTrue(types.contains(AgentEventType.OBSERVATION));
        assertTrue(types.contains(AgentEventType.ANSWER));
        assertTrue(events.stream()
                .anyMatch(event -> event.getType() == AgentEventType.NODE_START && "model_call".equals(event.getNode())));
        assertTrue(events.stream()
                .anyMatch(event -> event.getType() == AgentEventType.NODE_START && "decision".equals(event.getNode())));
        assertTrue(prompts.get(1).contains("动态上下文"));
        assertTrue(prompts.get(1).contains("assistant_action"));
        assertTrue(prompts.get(1).contains("tool_result"));
        assertTrue(prompts.get(1).contains("DefaultChatStreamService.java:42"));
        assertTrue(prompts.get(2).contains("Step 2 - assistant_action"));
        assertTrue(prompts.get(2).contains("Step 2 - tool_result"));
        assertEquals("DefaultChatStreamService.stream 定义在 DefaultChatStreamService.java，负责归一化请求、调用模型流并输出 SSE 事件。",
                events.stream().filter(event -> event.getType() == AgentEventType.ANSWER).findFirst().get().getAnswer());
    }

    @Test
    public void shouldStopAfterRepeatedParseErrors() {
        AgentRuntimeProperties properties = properties();
        properties.setParseErrorMaxAttempts(1);
        DefaultAgentLoopService service = new DefaultAgentLoopService(
                completeGateway(new java.util.ArrayList<>(), "not json", "still not json"),
                new ToolRegistry(List.of(fakeTool("code_search", "unused"))),
                properties,
                new ObjectMapper(),
                Runnable::run);

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("这个函数在哪")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        AgentEvent error = events.stream()
                .filter(event -> event.getType() == AgentEventType.ERROR)
                .findFirst()
                .orElseThrow();
        assertEquals("parse_error", error.getCode());
    }

    @Test
    public void shouldPauseWriteToolUntilApprovalAndResumeAfterApprove() {
        List<String> prompts = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AgentTool writeTool = fakeWriteTool("replace_in_file", "updated: Demo.java", calls);
        ApprovalStore approvalStore = new InMemoryApprovalStore();
        DefaultAgentLoopService service = new DefaultAgentLoopService(
                completeGateway(prompts,
                        "{\"type\":\"action\",\"thought\":\"修改文件\",\"tool\":\"replace_in_file\",\"input\":{\"path\":\"Demo.java\",\"oldText\":\"a\",\"newText\":\"b\"}}",
                        "{\"type\":\"final\",\"answer\":\"文件已修改并验证。\",\"evidence\":[{\"file\":\"Demo.java\",\"line\":1}]}"),
                new ToolRegistry(List.of(writeTool)),
                approvalStore,
                properties(),
                new ObjectMapper(),
                Runnable::run);

        List<AgentEvent> paused = service.ask(AgentQuestion.builder()
                        .question("把 Demo.java 里的 a 改成 b")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        AgentEvent approval = paused.stream()
                .filter(event -> event.getType() == AgentEventType.APPROVAL_REQUIRED)
                .findFirst()
                .orElseThrow();
        assertEquals("replace_in_file", approval.getTool());
        assertEquals("WRITE_CONFIRM", approval.getPermissionLevel());
        assertEquals(0, calls.get());
        assertTrue(approvalStore.find(approval.getApprovalId()).isPresent());

        List<AgentEvent> resumed = service.resume(approval.getApprovalId(), ApprovalDecision.APPROVE, "ok")
                .collectList()
                .block(Duration.ofSeconds(3));

        assertEquals(1, calls.get());
        assertTrue(approvalStore.find(approval.getApprovalId()).isEmpty());
        assertTrue(resumed.stream().anyMatch(event -> event.getType() == AgentEventType.TOOL_CALL));
        assertTrue(resumed.stream().anyMatch(event -> event.getType() == AgentEventType.OBSERVATION
                && event.getObservation().contains("updated: Demo.java")));
        assertEquals("文件已修改并验证。",
                resumed.stream().filter(event -> event.getType() == AgentEventType.ANSWER).findFirst().get().getAnswer());
    }

    @Test
    public void shouldContinueWithRejectedObservationAfterApprovalReject() {
        AtomicInteger calls = new AtomicInteger();
        AgentTool writeTool = fakeWriteTool("write_file", "written", calls);
        DefaultAgentLoopService service = new DefaultAgentLoopService(
                completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"写文件\",\"tool\":\"write_file\",\"input\":{\"path\":\"Demo.java\",\"content\":\"x\",\"mode\":\"create\"}}",
                        "{\"type\":\"final\",\"answer\":\"用户拒绝写入，未修改文件。\",\"evidence\":[]}"),
                new ToolRegistry(List.of(writeTool)),
                new InMemoryApprovalStore(),
                properties(),
                new ObjectMapper(),
                Runnable::run);

        List<AgentEvent> paused = service.ask(AgentQuestion.builder()
                        .question("创建 Demo.java")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));
        String approvalId = paused.stream()
                .filter(event -> event.getType() == AgentEventType.APPROVAL_REQUIRED)
                .findFirst()
                .orElseThrow()
                .getApprovalId();

        List<AgentEvent> resumed = service.resume(approvalId, ApprovalDecision.REJECT, "不允许写")
                .collectList()
                .block(Duration.ofSeconds(3));

        assertEquals(0, calls.get());
        assertTrue(resumed.stream().anyMatch(event -> event.getType() == AgentEventType.OBSERVATION
                && event.getObservation().contains("approval_rejected")));
        assertEquals("用户拒绝写入，未修改文件。",
                resumed.stream().filter(event -> event.getType() == AgentEventType.ANSWER).findFirst().get().getAnswer());
    }

    @Test
    public void shouldDenyHighRiskToolWithoutCallingIt() {
        AtomicInteger calls = new AtomicInteger();
        AgentTool dangerousTool = fakeDenyTool("run_shell", calls);
        DefaultAgentLoopService service = new DefaultAgentLoopService(
                completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"删除文件\",\"tool\":\"run_shell\",\"input\":{\"command\":\"rm -rf .\"}}",
                        "{\"type\":\"final\",\"answer\":\"高危命令已拦截。\",\"evidence\":[]}"),
                new ToolRegistry(List.of(dangerousTool)),
                properties(),
                new ObjectMapper(),
                Runnable::run);

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("删除所有文件")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertEquals(0, calls.get());
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.POLICY_DENIED
                && event.getObservation().contains("policy_denied")));
        assertEquals("高危命令已拦截。",
                events.stream().filter(event -> event.getType() == AgentEventType.ANSWER).findFirst().get().getAnswer());
    }

    @Test
    public void shouldResumeApprovalWithOriginalResolvedWorkspace() throws Exception {
        Path allowedRoot = temporaryFolder.newFolder("allowed-root").toPath();
        Path projectA = Files.createDirectories(allowedRoot.resolve("project-a"));
        Path projectB = Files.createDirectories(allowedRoot.resolve("project-b"));
        AgentRuntimeProperties properties = properties(projectA, allowedRoot);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Path> calledWorkspace = new AtomicReference<>();
        AgentTool writeTool = fakeWriteTool("replace_in_file", "updated in selected workspace", calls, calledWorkspace);
        ApprovalStore approvalStore = new InMemoryApprovalStore();
        DefaultAgentLoopService service = new DefaultAgentLoopService(
                completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"修改文件\",\"tool\":\"replace_in_file\",\"input\":{\"path\":\"Demo.java\",\"oldText\":\"a\",\"newText\":\"b\"}}",
                        "{\"type\":\"final\",\"answer\":\"完成。\",\"evidence\":[]}"),
                new ToolRegistry(List.of(writeTool)),
                approvalStore,
                properties,
                new ObjectMapper(),
                Runnable::run);

        List<AgentEvent> paused = service.ask(AgentQuestion.builder()
                        .question("修改 project-b")
                        .workspace("project-b")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));
        AgentEvent approval = paused.stream()
                .filter(event -> event.getType() == AgentEventType.APPROVAL_REQUIRED)
                .findFirst()
                .orElseThrow();

        assertEquals("project-b", approval.getWorkspace());
        assertEquals(projectB.toRealPath(), approvalStore.find(approval.getApprovalId()).orElseThrow().getResolvedWorkspace());

        service.resume(approval.getApprovalId(), ApprovalDecision.APPROVE, "ok")
                .collectList()
                .block(Duration.ofSeconds(3));

        assertEquals(1, calls.get());
        assertEquals(projectB.toRealPath(), calledWorkspace.get());
    }

    @Test
    public void shouldCreatePlanForCacheAndUnitTestTask() {
        List<String> prompts = new java.util.ArrayList<>();
        DefaultAgentLoopService service = newService(
                completeGateway(prompts, "{\"type\":\"final\",\"answer\":\"计划已创建。\",\"evidence\":[]}"),
                List.of(fakeTool("code_search", "unused")));

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("给订单模块加缓存并补单测")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        AgentEvent plan = events.stream()
                .filter(event -> event.getType() == AgentEventType.PLAN_UPDATED)
                .findFirst()
                .orElseThrow();
        assertTrue(String.valueOf(plan.getPlan()).contains("缓存"));
        assertTrue(String.valueOf(plan.getPlan()).contains("单元测试"));
        assertTrue(prompts.get(0).contains("当前计划"));
    }

    @Test
    public void shouldUpdatePlanWithTodoWriteTool() {
        DefaultAgentLoopService service = newService(
                completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"更新计划\",\"tool\":\"todo_write\",\"input\":{\"todos\":[{\"id\":\"task-1\",\"content\":\"理解代码\",\"status\":\"completed\",\"evidence\":\"已读实现\"},{\"id\":\"task-2\",\"content\":\"补测试\",\"status\":\"in_progress\"}]}}",
                        "{\"type\":\"final\",\"answer\":\"计划已更新。\",\"evidence\":[]}"),
                List.of(new TodoWriteTool()));

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("给某模块加缓存并补单测")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.PLAN_UPDATED
                && String.valueOf(event.getPlan()).contains("已读实现")));
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.OBSERVATION
                && event.getObservation().contains("Updated 2 planned tasks")));
    }

    @Test
    public void shouldRemindModelWhenPlanIsNotUpdatedForThreeRounds() {
        List<String> prompts = new java.util.ArrayList<>();
        DefaultAgentLoopService service = newService(
                completeGateway(prompts,
                        "{\"type\":\"action\",\"thought\":\"查1\",\"tool\":\"code_search\",\"input\":{\"query\":\"a\"}}",
                        "{\"type\":\"action\",\"thought\":\"查2\",\"tool\":\"code_search\",\"input\":{\"query\":\"b\"}}",
                        "{\"type\":\"action\",\"thought\":\"查3\",\"tool\":\"code_search\",\"input\":{\"query\":\"c\"}}",
                        "{\"type\":\"final\",\"answer\":\"完成。\",\"evidence\":[]}"),
                List.of(fakeTool("code_search", "ok")));

        service.ask(AgentQuestion.builder()
                        .question("给某模块加缓存并补单测")
                        .maxSteps(8)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertTrue(prompts.get(3).contains("<reminder>Update your todos with todo_write before continuing.</reminder>"));
    }

    @Test
    public void shouldReplanAfterToolFailure() {
        DefaultAgentLoopService service = newService(
                completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"执行失败工具\",\"tool\":\"code_search\",\"input\":{\"query\":\"x\"}}",
                        "{\"type\":\"final\",\"answer\":\"已根据失败调整计划。\",\"evidence\":[]}"),
                List.of(failingTool("code_search", "boom")));

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("给某模块加缓存并补单测")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.REPLAN_STARTED
                && String.valueOf(event.getPlan()).contains("检查失败原因")));
    }

    @Test
    public void shouldResumeApprovalFromCheckpointRepository() {
        AgentRunRepository runRepository = new InMemoryAgentRunRepository();
        AgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        ApprovalStore approvalStore = new InMemoryApprovalStore();
        AtomicInteger calls = new AtomicInteger();
        AgentTool writeTool = fakeWriteTool("replace_in_file", "updated after resume", calls);

        DefaultAgentLoopService firstService = newService(
                completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"修改文件\",\"tool\":\"replace_in_file\",\"input\":{\"path\":\"Demo.java\",\"oldText\":\"a\",\"newText\":\"b\"}}"),
                List.of(writeTool),
                approvalStore,
                runRepository,
                checkpointRepository);

        List<AgentEvent> paused = firstService.ask(AgentQuestion.builder()
                        .question("修改 Demo.java")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));
        String approvalId = paused.stream()
                .filter(event -> event.getType() == AgentEventType.APPROVAL_REQUIRED)
                .findFirst()
                .orElseThrow()
                .getApprovalId();

        DefaultAgentLoopService secondService = newService(
                completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"final\",\"answer\":\"恢复后完成。\",\"evidence\":[]}"),
                List.of(writeTool),
                approvalStore,
                runRepository,
                checkpointRepository);

        List<AgentEvent> resumed = secondService.resume(approvalId, ApprovalDecision.APPROVE, "ok")
                .collectList()
                .block(Duration.ofSeconds(3));

        assertEquals(1, calls.get());
        assertTrue(resumed.stream().anyMatch(event -> event.getType() == AgentEventType.RESUME_STARTED));
        assertEquals("恢复后完成。",
                resumed.stream().filter(event -> event.getType() == AgentEventType.ANSWER).findFirst().get().getAnswer());
    }

    @Test
    public void shouldReplanUnsafeResumeWithoutRepeatingTool() {
        AgentRunRepository runRepository = new InMemoryAgentRunRepository();
        AgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        AtomicInteger calls = new AtomicInteger();
        AgentContext context = new AgentContext();
        context.setRunId("run-unsafe");
        context.setRequestId("request-unsafe");
        context.setConversationId("conversation-unsafe");
        context.setQuestion("给某模块加缓存并补单测");
        context.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        context.setWorkspaceDisplayName(".");
        context.setMaxSteps(6);
        context.setStartedAt(java.time.Instant.now());
        context.setCurrentNode(AgentNodeNames.TOOL_DISPATCH);
        context.setUnsafeResumeRequired(true);
        context.setToolSpecs(List.of(ToolSpec.builder().name("replace_in_file").description("write").inputSchema("{}").build()));
        checkpointRepository.save(AgentCheckpoint.builder()
                .runId(context.getRunId())
                .currentNode(AgentNodeNames.TOOL_DISPATCH)
                .contextSnapshot(AgentContextSnapshot.from(context))
                .reason("before_tool:replace_in_file")
                .build());

        DefaultAgentLoopService service = newService(
                completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"final\",\"answer\":\"未重复写入，已改为检查状态。\",\"evidence\":[]}"),
                List.of(fakeWriteTool("replace_in_file", "should not execute", calls)),
                new InMemoryApprovalStore(),
                runRepository,
                checkpointRepository);

        List<AgentEvent> events = service.resumeRun("run-unsafe")
                .collectList()
                .block(Duration.ofSeconds(3));

        assertEquals(0, calls.get());
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.REPLAN_STARTED));
    }

    private DefaultAgentLoopService newService(ModelGateway modelGateway, List<AgentTool> tools) {
        return new DefaultAgentLoopService(
                modelGateway,
                new ToolRegistry(tools),
                properties(),
                new ObjectMapper(),
                Runnable::run);
    }

    private DefaultAgentLoopService newService(ModelGateway modelGateway,
                                               List<AgentTool> tools,
                                               ApprovalStore approvalStore,
                                               AgentRunRepository runRepository,
                                               AgentCheckpointRepository checkpointRepository) {
        return new DefaultAgentLoopService(
                modelGateway,
                new ToolRegistry(tools),
                approvalStore,
                new cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver(properties()),
                runRepository,
                checkpointRepository,
                properties(),
                new ObjectMapper(),
                Runnable::run);
    }

    private AgentRuntimeProperties properties() {
        return properties(Path.of(".").toAbsolutePath().normalize(), Path.of(".").toAbsolutePath().normalize());
    }

    private AgentRuntimeProperties properties(Path workspaceRoot, Path allowedRoot) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(workspaceRoot.toString());
        properties.setAllowedWorkspaceRoots(List.of(allowedRoot.toString()));
        properties.setStepTimeoutMs(1000L);
        properties.setTotalTimeoutMs(3000L);
        properties.setToolTimeoutMs(1000L);
        properties.setObservationMaxChars(8000);
        properties.setMaxSteps(6);
        return properties;
    }

    private ModelGateway completeGateway(List<String> prompts, String... outputs) {
        AtomicInteger index = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                prompts.add(prompt.getMessage());
                int current = Math.min(index.getAndIncrement(), outputs.length - 1);
                return Mono.just(ModelChatResult.builder().content(outputs[current]).finishReason("stop").build());
            }
        };
    }

    private AgentTool fakeTool(String name, String observation) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success(observation, false, 1L);
            }
        };
    }

    private AgentTool failingTool(String name, String message) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.failure("fake_failure", message, 1L);
            }
        };
    }

    private AgentTool fakeWriteTool(String name, String observation, AtomicInteger calls) {
        return fakeWriteTool(name, observation, calls, new AtomicReference<>());
    }

    private AgentTool fakeWriteTool(String name, String observation, AtomicInteger calls, AtomicReference<Path> calledWorkspace) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolPolicyDecision policy(ToolCall call) {
                return ToolPolicyDecision.writeConfirm("write", name);
            }

            @Override
            public ToolResult call(ToolCall call) {
                calls.incrementAndGet();
                calledWorkspace.set(call.getWorkspaceRoot());
                return ToolResult.success(observation, false, 1L);
            }
        };
    }

    private AgentTool fakeDenyTool(String name, AtomicInteger calls) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolPolicyDecision policy(ToolCall call) {
                return ToolPolicyDecision.highRiskDeny("高危命令已拦截", name);
            }

            @Override
            public ToolResult call(ToolCall call) {
                calls.incrementAndGet();
                return ToolResult.success("should not happen", false, 1L);
            }
        };
    }

}
