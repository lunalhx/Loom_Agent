package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentDispatchResult;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextCompactResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRole;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.agent.service.DefaultBudgetGuard;
import cn.lunalhx.ai.domain.agent.service.DeepContextSummaryService;
import cn.lunalhx.ai.domain.agent.service.ContextRecallTool;
import cn.lunalhx.ai.domain.agent.service.ContextWindowManager;
import cn.lunalhx.ai.domain.agent.service.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryAgentRunRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryApprovalStore;
import cn.lunalhx.ai.domain.agent.service.InMemoryContextArtifactRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryContextBlobStore;
import cn.lunalhx.ai.domain.agent.service.InMemoryTraceRecorder;
import cn.lunalhx.ai.domain.agent.service.RoleToolRegistryFactory;
import cn.lunalhx.ai.domain.agent.service.SubAgentCoordinator;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelCapability;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new java.util.ArrayList<>(), "not json", "still not json"))
                .tools(List.of(fakeTool("code_search", "unused")))
                .properties(properties)
                .buildAgentLoop();

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
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(prompts,
                        "{\"type\":\"action\",\"thought\":\"修改文件\",\"tool\":\"replace_in_file\",\"input\":{\"path\":\"Demo.java\",\"oldText\":\"a\",\"newText\":\"b\"}}",
                        "{\"type\":\"final\",\"answer\":\"文件已修改并验证。\",\"evidence\":[{\"file\":\"Demo.java\",\"line\":1}]}"))
                .tools(List.of(writeTool))
                .approvalStore(approvalStore)
                .buildAgentLoop();

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
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"写文件\",\"tool\":\"write_file\",\"input\":{\"path\":\"Demo.java\",\"content\":\"x\",\"mode\":\"create\"}}",
                        "{\"type\":\"final\",\"answer\":\"用户拒绝写入，未修改文件。\",\"evidence\":[]}"))
                .tools(List.of(writeTool))
                .buildAgentLoop();

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
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"删除文件\",\"tool\":\"run_shell\",\"input\":{\"command\":\"rm -rf .\"}}",
                        "{\"type\":\"final\",\"answer\":\"高危命令已拦截。\",\"evidence\":[]}"))
                .tools(List.of(dangerousTool))
                .buildAgentLoop();

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
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"修改文件\",\"tool\":\"replace_in_file\",\"input\":{\"path\":\"Demo.java\",\"oldText\":\"a\",\"newText\":\"b\"}}",
                        "{\"type\":\"final\",\"answer\":\"完成。\",\"evidence\":[]}"))
                .tools(List.of(writeTool))
                .approvalStore(approvalStore)
                .properties(properties)
                .buildAgentLoop();

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
    public void shouldNotCreatePlanUntilTodoWriteIsCalled() {
        List<String> prompts = new java.util.ArrayList<>();
        DefaultAgentLoopService service = newService(
                completeGateway(prompts, "{\"type\":\"final\",\"answer\":\"直接完成。\",\"evidence\":[]}"),
                List.of(fakeTool("code_search", "unused")));

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("根目录下有多少个文件夹")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertFalse(events.stream().anyMatch(event -> event.getType() == AgentEventType.PLAN_UPDATED));
        assertFalse(prompts.get(0).contains("当前计划：\n"));
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
        AgentEvent plan = events.stream()
                .filter(event -> event.getType() == AgentEventType.PLAN_UPDATED)
                .findFirst()
                .orElseThrow();
        assertEquals(2, ((List<?>) plan.getPlan().get("items")).size());
        assertTrue(String.valueOf(plan.getPlan()).contains("title=理解代码"));
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.OBSERVATION
                && event.getObservation().contains("Updated 2 planned tasks")));
    }

    @Test
    public void shouldRemindModelWhenPlanIsNotUpdatedForThreeRounds() {
        List<String> prompts = new java.util.ArrayList<>();
        DefaultAgentLoopService service = newService(
                completeGateway(prompts,
                        "{\"type\":\"action\",\"thought\":\"建立计划\",\"tool\":\"todo_write\",\"input\":{\"todos\":[{\"id\":\"task-1\",\"content\":\"检查目标代码\",\"status\":\"in_progress\"},{\"id\":\"task-2\",\"content\":\"完成修改和验证\",\"status\":\"pending\"}]}}",
                        "{\"type\":\"action\",\"thought\":\"查1\",\"tool\":\"code_search\",\"input\":{\"query\":\"a\"}}",
                        "{\"type\":\"action\",\"thought\":\"查2\",\"tool\":\"code_search\",\"input\":{\"query\":\"b\"}}",
                        "{\"type\":\"action\",\"thought\":\"查3\",\"tool\":\"code_search\",\"input\":{\"query\":\"c\"}}",
                        "{\"type\":\"final\",\"answer\":\"完成。\",\"evidence\":[]}"),
                List.of(new TodoWriteTool(), fakeTool("code_search", "ok")));

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

    @Test
    public void shouldReplanWhenResumeFindsExpiredApprovalCheckpoint() {
        AgentRunRepository runRepository = new InMemoryAgentRunRepository();
        AgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        AtomicInteger calls = new AtomicInteger();
        AgentContext context = new AgentContext();
        context.setRunId("run-expired-approval");
        context.setRequestId("request-expired-approval");
        context.setConversationId("conversation-expired-approval");
        context.setQuestion("写入配置文件");
        context.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        context.setWorkspaceDisplayName(".");
        context.setMaxSteps(6);
        context.setStartedAt(java.time.Instant.now());
        context.setCurrentNode(AgentNodeNames.APPROVAL_GATE);
        context.setPendingApprovalId("expired-approval");
        context.setDecision(AgentDecision.builder()
                .tool("replace_in_file")
                .input(new ObjectMapper().createObjectNode())
                .build());
        context.setToolSpecs(List.of(ToolSpec.builder().name("replace_in_file").description("write").inputSchema("{}").build()));
        checkpointRepository.save(AgentCheckpoint.builder()
                .runId(context.getRunId())
                .currentNode(AgentNodeNames.APPROVAL_GATE)
                .contextSnapshot(AgentContextSnapshot.from(context))
                .reason("after_node:approval_gate")
                .build());

        DefaultAgentLoopService service = newService(
                completeGateway(new java.util.ArrayList<>(),
                        "{}",
                        "{\"type\":\"final\",\"answer\":\"先检查状态，不复用过期审批。\",\"evidence\":[]}"),
                List.of(fakeWriteTool("replace_in_file", "should not execute", calls)),
                new InMemoryApprovalStore(),
                runRepository,
                checkpointRepository);

        List<AgentEvent> events = service.resumeRun("run-expired-approval")
                .collectList()
                .block(Duration.ofSeconds(3));

        assertEquals(0, calls.get());
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.RESUME_STARTED));
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.REPLAN_STARTED));
        assertFalse(events.stream().anyMatch(event -> event.getType() == AgentEventType.APPROVAL_REQUIRED));
    }

    @Test
    public void shouldSpawnReadOnlySubAgentsAndReturnOnlyAggregatedSummary() {
        List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
        ModelGateway modelGateway = promptRoutingGateway(prompts);
        AgentRuntimeProperties properties = properties();
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        List<AgentTool> tools = List.of(fakeTool("code_search", "raw child search output"), new TodoWriteTool());
        SubAgentCoordinator coordinator = AgentRuntimeTestFixture.fixture()
                .modelGateway(modelGateway)
                .tools(tools)
                .approvalStore(approvalStore)
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository)
                .properties(properties)
                .subAgentEnabled()
                .buildSubAgentCoordinator();
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(modelGateway)
                .tools(tools)
                .approvalStore(approvalStore)
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository)
                .properties(properties)
                .subAgentCoordinator(coordinator)
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("搜集 DeprecatedApi 的使用点")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(5));

        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.SUB_AGENT_STARTED));
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.SUB_AGENT_COMPLETED
                && "domain".equals(event.getSubAgentTaskId())));
        AgentEvent summary = events.stream()
                .filter(event -> event.getType() == AgentEventType.SUB_AGENT_SUMMARY)
                .findFirst()
                .orElseThrow();
        assertTrue(summary.getObservation().contains("\"type\":\"sub_agent_summary\""));
        assertTrue(summary.getObservation().contains("DeprecatedApi"));
        assertEquals("汇总完成。",
                events.stream().filter(event -> event.getType() == AgentEventType.ANSWER).findFirst().get().getAnswer());
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("spawn_agents")));
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("\"type\":\"sub_agent_summary\"")));
    }

    @Test
    public void shouldPreventReadOnlySubAgentFromCallingWriteTool() {
        AtomicInteger calls = new AtomicInteger();
        RoleToolRegistryFactory factory = new RoleToolRegistryFactory(List.of(
                fakeWriteTool("run_shell", "should not write", calls),
                fakeTool("code_search", "ok")));

        ToolRegistry registry = factory.create(AgentRole.EXPLORER);
        ToolCall call = ToolCall.builder()
                .name("run_shell")
                .input(new ObjectMapper().createObjectNode().put("path", "Demo.java"))
                .workspaceRoot(Path.of(".").toAbsolutePath().normalize())
                .build();

        ToolPolicyDecision policy = registry.policy(call);
        ToolResult result = registry.call(call);

        assertEquals(ToolPermissionLevel.HIGH_RISK_DENY, policy.getPermissionLevel());
        assertEquals(0, calls.get());
        assertTrue(result.getObservation().contains("sub_agent_read_only_violation"));
    }

    @Test
    public void shouldRunReadOnlySubAgentsConcurrently() {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<AgentTool> tools = List.of(fakeTool("code_search", "unused"), new TodoWriteTool());
            AgentRuntimeProperties properties = properties();
            properties.setSubAgentMaxConcurrency(4);
            properties.setSubAgentTimeoutMs(3000L);
            ModelGateway modelGateway = delayedFinalGateway(180L);
            SubAgentCoordinator coordinator = AgentRuntimeTestFixture.fixture()
                    .modelGateway(modelGateway)
                    .tools(tools)
                    .properties(properties)
                    .executor(executor)
                    .subAgentEnabled()
                    .buildSubAgentCoordinator();

            AgentContext parent = parentContextWithSpawnDecision(4);
            long startedAt = System.currentTimeMillis();
            SubAgentDispatchResult result = coordinator.dispatch(parent);
            long elapsed = System.currentTimeMillis() - startedAt;

            assertTrue(result.isSuccess());
            assertEquals(4, result.getResults().size());
            assertTrue("expected concurrent execution but elapsed=" + elapsed, elapsed < 650L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldNotLeakParentDynamicTextIntoChildPrompt() {
        List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
        AgentRuntimeProperties properties = properties();
        SubAgentCoordinator coordinator = AgentRuntimeTestFixture.fixture()
                .modelGateway(promptRoutingGateway(prompts))
                .tools(List.of(fakeTool("code_search", "unused"), new TodoWriteTool()))
                .properties(properties)
                .subAgentEnabled()
                .buildSubAgentCoordinator();

        AgentContext parent = parentContextWithSpawnDecision(1);
        parent.getDynamicText().appendSystemNote(1, "test", "Parent Secret", "SHOULD_NOT_LEAK_TO_CHILD");
        coordinator.dispatch(parent);

        assertTrue(prompts.stream()
                .filter(prompt -> prompt.contains("隔离子 Agent"))
                .noneMatch(prompt -> prompt.contains("SHOULD_NOT_LEAK_TO_CHILD")));
    }

    @Test
    public void shouldRecordTraceTimelineForNormalRun() {
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        AgentRuntimeProperties properties = properties();
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new java.util.ArrayList<>(),
                        "{\"type\":\"final\",\"answer\":\"完成。\",\"evidence\":[]}"))
                .tools(List.of(fakeTool("code_search", "unused")))
                .properties(properties)
                .traceRecorder(traceRecorder)
                .budgetGuard(new DefaultBudgetGuard(properties))
                .buildAgentLoop();

        service.ask(AgentQuestion.builder()
                        .runId("trace-run")
                        .question("记录 trace")
                        .maxSteps(3)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        var timeline = traceRecorder.timeline("trace-run");
        assertFalse(timeline.isEmpty());
        assertTrue(timeline.stream().anyMatch(event -> "node_start".equals(event.getEventType())));
        assertTrue(timeline.stream().anyMatch(event -> "node_end".equals(event.getEventType())));
        assertTrue(timeline.stream().anyMatch(event -> "stop".equals(event.getEventType())));
        for (int i = 1; i < timeline.size(); i++) {
            assertTrue(timeline.get(i).getSequenceNo() > timeline.get(i - 1).getSequenceNo());
        }
    }

    @Test
    public void shouldBlockModelCallWhenBudgetExceeded() {
        AgentRuntimeProperties properties = properties();
        properties.getBudget().setEnabled(true);
        properties.getBudget().setMaxTotalTokens(4);
        properties.getBudget().setReservedOutputTokens(4);
        AtomicInteger calls = new AtomicInteger();
        ModelGateway modelGateway = countingGateway(calls);
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(modelGateway)
                .tools(List.of(fakeTool("code_search", "unused")))
                .properties(properties)
                .runRepository(runRepository)
                .budgetGuard(new DefaultBudgetGuard(properties))
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("budget-run")
                        .question("预算不足时不要调用模型")
                        .maxSteps(3)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertEquals(0, calls.get());
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.ERROR
                && "budget_exceeded".equals(event.getCode())));
        assertEquals(AgentRunStatus.BUDGET_EXCEEDED,
                runRepository.find("budget-run").orElseThrow().getStatus());
    }

    @Test
    public void shouldAccumulateUsageCostAndRecordModelUsageTrace() {
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        AgentRuntimeProperties properties = properties();
        properties.getBudget().setInputPricePer1k(new java.math.BigDecimal("0.001"));
        properties.getBudget().setOutputPricePer1k(new java.math.BigDecimal("0.002"));
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(usageGateway("{\"type\":\"final\",\"answer\":\"完成。\",\"evidence\":[]}", 10, 5, 15))
                .tools(List.of(fakeTool("code_search", "unused")))
                .properties(properties)
                .runRepository(runRepository)
                .traceRecorder(traceRecorder)
                .budgetGuard(new DefaultBudgetGuard(properties))
                .buildAgentLoop();

        service.ask(AgentQuestion.builder()
                        .runId("usage-run")
                        .question("累计 usage")
                        .maxSteps(3)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertEquals(Long.valueOf(15L), runRepository.find("usage-run").orElseThrow().getUsedTokens());
        assertTrue(traceRecorder.timeline("usage-run").stream()
                .anyMatch(event -> "model_usage".equals(event.getEventType())
                        && event.getTokenUsage() != null
                        && Integer.valueOf(15).equals(event.getTokenUsage().getTotalTokens())));
    }

    @Test
    public void shouldPersistLargeToolObservationAsContextArtifact() {
        List<String> prompts = new java.util.ArrayList<>();
        AgentRuntimeProperties properties = properties();
        properties.getContext().setPersistToolResultChars(80);
        properties.getContext().setToolPreviewChars(30);
        ContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = new InMemoryContextBlobStore();
        String largeObservation = "alpha-" + "x".repeat(160) + "-omega-tail";

        DefaultAgentLoopService service = newServiceWithContext(
                completeGateway(prompts,
                        "{\"type\":\"action\",\"thought\":\"查大输出\",\"tool\":\"code_search\",\"input\":{\"query\":\"large\"}}",
                        "{\"type\":\"final\",\"answer\":\"已读取大输出。\",\"evidence\":[]}"),
                List.of(fakeTool("code_search", largeObservation), new ContextRecallTool(artifactRepository, blobStore)),
                properties,
                artifactRepository,
                blobStore);

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("artifact-run")
                        .question("制造一个大 observation")
                        .maxSteps(4)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertNotNull(events);
        assertEquals(1, artifactRepository.listByRootRunId("artifact-run").size());
        String artifactId = artifactRepository.listByRootRunId("artifact-run").getFirst().getArtifactId();
        assertTrue(events.stream()
                .filter(event -> event.getType() == AgentEventType.OBSERVATION)
                .anyMatch(event -> event.getObservation().contains(artifactId)
                        && event.getObservation().contains("context_recall")
                        && !event.getObservation().contains("omega-tail")));
        assertTrue(blobStore.read(artifactRepository.listByRootRunId("artifact-run").getFirst().getStorageUri())
                .contains("omega-tail"));
        assertTrue(prompts.get(1).contains("context_artifact"));
        assertTrue(prompts.get(1).contains("需要完整细节时先调用 context_recall"));
    }

    @Test
    public void contextRecallShouldRejectArtifactsOutsideRootRun() {
        AgentRuntimeProperties properties = properties();
        properties.getContext().setPersistToolResultChars(10);
        ContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);
        AgentContext runA = contextForRoot("root-a");
        AgentContext runB = contextForRoot("root-b");
        manager.prepareToolResult(runA, ToolResult.success("A-" + "a".repeat(40), false, 1L));
        ToolResult bResult = manager.prepareToolResult(runB, ToolResult.success("B-" + "b".repeat(40), false, 1L));
        ContextRecallTool recallTool = new ContextRecallTool(artifactRepository, blobStore);
        com.fasterxml.jackson.databind.node.ObjectNode input = new ObjectMapper().createObjectNode()
                .put("action", "get")
                .put("artifactId", bResult.getArtifactId());

        ToolResult denied = recallTool.call(ToolCall.builder()
                .name(ContextRecallTool.NAME)
                .input(input)
                .runId("root-a")
                .rootRunId("root-a")
                .conversationId("conversation-a")
                .build());

        assertFalse(denied.isSuccess());
        assertEquals("context_recall_not_found", denied.getErrorCode());
    }

    @Test
    public void shouldReactiveCompactAndRetryOnProviderContextOverflow() {
        AgentRuntimeProperties properties = properties();
        properties.getContext().setReactiveCompactMaxAttempts(1);
        ContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = new InMemoryContextBlobStore();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.getAndIncrement() == 0) {
                    return Mono.error(new RuntimeException("context_length_exceeded"));
                }
                return Mono.just(ModelChatResult.builder()
                        .content("{\"type\":\"final\",\"answer\":\"压缩后成功。\",\"evidence\":[]}")
                        .finishReason("stop")
                        .build());
            }
        };

        DefaultAgentLoopService service = newServiceWithContext(
                gateway,
                List.of(new ContextRecallTool(artifactRepository, blobStore)),
                properties,
                artifactRepository,
                blobStore);

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("reactive-run")
                        .question("触发上下文超限后重试")
                        .maxSteps(3)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertNotNull(events);
        assertEquals(2, calls.get());
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.CONTEXT_COMPACTED
                && event.getMetadata() != null
                && event.getMetadata().get("strategies").toString().contains("reactive_summary")));
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.ANSWER
                && "压缩后成功。".equals(event.getAnswer())));
        assertEquals(1, artifactRepository.listByRootRunId("reactive-run").size());
    }

    @Test
    public void reactiveCompactShouldRetainLatestFiveEntriesAndReportOversize() {
        AgentRuntimeProperties properties = properties();
        properties.getBudget().setEstimatedCharsPerToken(1);
        properties.getContext().setReactiveKeepRecentEntries(5);
        ContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);
        AgentContext context = contextForRoot("reactive-size-run");
        context.getDynamicText().appendUserTask("original task");
        for (int i = 1; i <= 7; i++) {
            context.getDynamicText().appendSystemNote(i, "test", "entry-" + i, "entry-" + i);
        }

        ContextCompactResult result = manager.reactiveCompact(context, 10);

        assertFalse(result.isFitsTarget());
        assertEquals(5, result.getRetainedEntryCount());
        assertNotNull(result.getTranscriptArtifactId());
        assertEquals(7, context.getDynamicText().entries().size());
        assertEquals("entry-3", context.getDynamicText().entries().get(2).getContent());
        assertEquals("entry-7", context.getDynamicText().entries().get(6).getContent());
    }

    @Test
    public void deepSummaryShouldSummarizeChunksAndMergeThem() {
        AgentRuntimeProperties properties = properties();
        properties.getBudget().setEstimatedCharsPerToken(1);
        properties.getContext().setDeepSummaryChunkTokenLimit(10);
        properties.getContext().setDeepSummaryMaxCalls(8);
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int call = calls.incrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content(call == 3 ? "merged" : "s" + call)
                        .actualModel("summary-model")
                        .finishReason("stop")
                        .build());
            }
        };
        DeepContextSummaryService service = new DeepContextSummaryService(
                gateway, properties, new DefaultBudgetGuard(properties), new InMemoryTraceRecorder());
        AgentContext context = contextForRoot("deep-summary-run");
        context.setRequestId("request-deep-summary");

        DeepContextSummaryService.DeepSummaryResult result = service.summarize(
                context, List.of("12345678", "abcdefgh"), System.currentTimeMillis() + 1000L);

        assertEquals(3, calls.get());
        assertEquals(3, result.getCalls());
        assertEquals("merged", result.getSummary());
    }

    @Test
    public void shouldBoundRecoveryWaitForUserAndContinueFromCheckpoint() {
        AgentRuntimeProperties properties = properties();
        properties.getContext().setReactiveCompactMaxAttempts(1);
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        ContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = overflowThenFinalGateway(calls, 3, "用户补充后成功。");
        DefaultAgentLoopService service = statefulService(
                gateway, properties, runRepository, checkpointRepository, manager);

        List<AgentEvent> waiting = service.ask(AgentQuestion.builder()
                        .runId("waiting-input-run")
                        .question("触发完整上下文恢复链")
                        .maxSteps(4)
                        .build())
                .collectList()
                .block(Duration.ofSeconds(3));

        assertEquals(3, calls.get());
        assertTrue(waiting.stream().anyMatch(event -> event.getType() == AgentEventType.USER_INPUT_REQUIRED));
        assertEquals(AgentRunStatus.WAITING_USER_INPUT,
                runRepository.find("waiting-input-run").orElseThrow().getStatus());
        AgentContext waitingContext = checkpointRepository.latest("waiting-input-run")
                .orElseThrow().getContextSnapshot().restore();
        assertEquals(ContextRecoveryStage.WAITING_USER_INPUT, waitingContext.getContextRecoveryStage());
        assertEquals(1, waitingContext.getReactiveCompactAttempts());

        List<AgentEvent> replayed = service.resumeRun("waiting-input-run")
                .collectList().block(Duration.ofSeconds(3));
        assertTrue(replayed.stream().anyMatch(event -> event.getType() == AgentEventType.USER_INPUT_REQUIRED));
        assertEquals(3, calls.get());

        List<AgentEvent> resumed = service.resumeWithUserInput(
                        "waiting-input-run", UserInputAction.CONTINUE, "只处理当前最小范围")
                .collectList().block(Duration.ofSeconds(3));

        assertEquals(4, calls.get());
        assertTrue(resumed.stream().anyMatch(event -> event.getType() == AgentEventType.ANSWER
                && "用户补充后成功。".equals(event.getAnswer())));
        assertEquals(AgentRunStatus.COMPLETED,
                runRepository.find("waiting-input-run").orElseThrow().getStatus());
        AgentContext completed = checkpointRepository.latest("waiting-input-run")
                .orElseThrow().getContextSnapshot().restore();
        assertEquals(ContextRecoveryStage.NONE, completed.getContextRecoveryStage());
        assertEquals(0, completed.getReactiveCompactAttempts());
    }

    @Test
    public void shouldCompactBeforeSwitchingToLargerContextModel() {
        AgentRuntimeProperties properties = properties();
        properties.getModelRecovery().setContextFallbackModel("large-context");
        ContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = new InMemoryContextBlobStore();
        List<String> models = new java.util.ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                models.add(prompt.getModel());
                if (calls.incrementAndGet() <= 2) {
                    return Mono.error(new ModelGatewayException(
                            ModelErrorCode.CONTEXT_OVERFLOW,
                            "context_length_exceeded",
                            false,
                            422,
                            null));
                }
                return Mono.just(ModelChatResult.builder()
                        .content("{\"type\":\"final\",\"answer\":\"大窗口恢复成功。\",\"evidence\":[]}")
                        .actualModel("large-context")
                        .finishReason("stop")
                        .build());
            }

            @Override
            public ModelCapability capability(String model) {
                return "large-context".equals(model)
                        ? new ModelCapability(model, 200000L, 64000, true, true)
                        : new ModelCapability("small-context", 100000L, 64000, true, true);
            }
        };

        List<AgentEvent> events = newServiceWithContext(
                gateway,
                List.of(),
                properties,
                artifactRepository,
                blobStore)
                .ask(AgentQuestion.builder()
                        .runId("fallback-order-run")
                        .question("先压缩再切大模型")
                        .maxSteps(4)
                        .build())
                .collectList().block(Duration.ofSeconds(3));

        assertEquals(3, calls.get());
        assertEquals(java.util.Arrays.asList(null, null, "large-context"), models);
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.CONTEXT_COMPACTED
                && event.getMetadata().get("strategies").toString().contains("reactive_summary")));
        assertFalse(events.stream().anyMatch(event -> event.getType() == AgentEventType.CONTEXT_COMPACTED
                && event.getMetadata().get("strategies").toString().contains("deep_summary")));
    }

    @Test
    public void shouldAbortWhileWaitingForUserInputWithContextOverflowReason() {
        AgentRuntimeProperties properties = properties();
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        ContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = new InMemoryContextBlobStore();
        ContextWindowManager manager = new ContextWindowManager(properties, artifactRepository, blobStore);
        AtomicInteger calls = new AtomicInteger();
        DefaultAgentLoopService service = statefulService(
                overflowThenFinalGateway(calls, Integer.MAX_VALUE, "never"),
                properties,
                runRepository,
                checkpointRepository,
                manager);

        service.ask(AgentQuestion.builder()
                        .runId("abort-input-run")
                        .question("触发等待后终止")
                        .maxSteps(4)
                        .build())
                .collectList().block(Duration.ofSeconds(3));
        List<AgentEvent> aborted = service.resumeWithUserInput(
                        "abort-input-run", UserInputAction.ABORT, null)
                .collectList().block(Duration.ofSeconds(3));

        assertTrue(aborted.stream().anyMatch(event -> event.getType() == AgentEventType.ERROR
                && ModelErrorCode.CONTEXT_OVERFLOW.code().equals(event.getCode())));
        assertTrue(aborted.stream().anyMatch(event -> event.getType() == AgentEventType.DONE
                && event.getStopReason() == AgentStopReason.CONTEXT_OVERFLOW));
        assertEquals(3, calls.get());
    }

    @Test
    public void childAgentShouldFailInsteadOfWaitingForUserInput() {
        AgentRuntimeProperties properties = properties();
        ContextArtifactRepository artifactRepository = new InMemoryContextArtifactRepository();
        ContextBlobStore blobStore = new InMemoryContextBlobStore();
        AtomicInteger calls = new AtomicInteger();
        List<AgentEvent> events = newServiceWithContext(
                overflowThenFinalGateway(calls, Integer.MAX_VALUE, "never"),
                List.of(),
                properties,
                artifactRepository,
                blobStore)
                .ask(AgentQuestion.builder()
                        .runId("child-overflow-run")
                        .rootRunId("root-overflow-run")
                        .parentRunId("root-overflow-run")
                        .agentRole(AgentRole.EXPLORER)
                        .agentDepth(1)
                        .question("子 Agent 上下文超限")
                        .maxSteps(4)
                        .build())
                .collectList().block(Duration.ofSeconds(3));

        assertEquals(3, calls.get());
        assertFalse(events.stream().anyMatch(event -> event.getType() == AgentEventType.USER_INPUT_REQUIRED));
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.ERROR
                && ModelErrorCode.CONTEXT_OVERFLOW.code().equals(event.getCode())));
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.DONE
                && event.getStopReason() == AgentStopReason.CONTEXT_OVERFLOW));
    }

    @Test
    public void controlJsonShouldFailWhenStillTruncatedAfter64k() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Integer> secondMaxTokens = new AtomicReference<>();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.incrementAndGet() == 2) {
                    secondMaxTokens.set(prompt.getMaxTokens());
                }
                return Mono.just(ModelChatResult.builder()
                        .content("{\"type\":\"final\"")
                        .finishReason("length")
                        .build());
            }
        };

        List<AgentEvent> events = newService(gateway, List.of())
                .ask(AgentQuestion.builder().question("生成一个很长的控制决策").maxSteps(2).build())
                .collectList().block(Duration.ofSeconds(3));

        assertEquals(2, calls.get());
        assertEquals(Integer.valueOf(64000), secondMaxTokens.get());
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.ERROR
                && ModelErrorCode.MODEL_DECISION_TRUNCATED.code().equals(event.getCode())));
        assertFalse(events.stream().anyMatch(event -> event.getType() == AgentEventType.NODE_START
                && AgentNodeNames.DECISION.equals(event.getNode())));
    }

    @Test
    public void nonContextBadRequestShouldNotTriggerCompact() {
        AtomicInteger calls = new AtomicInteger();
        ModelGateway gateway = new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                calls.incrementAndGet();
                return Mono.error(new ModelGatewayException(ModelErrorCode.BAD_REQUEST,
                        "temperature is invalid", false, 400, null));
            }
        };

        List<AgentEvent> events = newService(gateway, List.of())
                .ask(AgentQuestion.builder().question("不要压缩普通 400").maxSteps(2).build())
                .collectList().block(Duration.ofSeconds(3));

        assertEquals(1, calls.get());
        assertFalse(events.stream().anyMatch(event -> event.getType() == AgentEventType.CONTEXT_COMPACTED));
    }

    @Test
    public void checkpointShouldRestoreRecoveryState() {
        AgentContext context = contextForRoot("recovery-checkpoint");
        context.setCurrentModel("deepseek-v4-pro");
        context.setReactiveCompactAttempts(1);
        context.setFallbackReason("provider_overloaded");
        context.setContextRecoveryStage(ContextRecoveryStage.FALLBACK_MODEL_SELECTED);
        context.setRecoveryModelOverride("deepseek-v4-pro");
        context.setContextTranscriptArtifactId("ctx-transcript");

        AgentContext restored = AgentContextSnapshot.from(context).restore();

        assertEquals("deepseek-v4-pro", restored.getCurrentModel());
        assertEquals(1, restored.getReactiveCompactAttempts());
        assertEquals("provider_overloaded", restored.getFallbackReason());
        assertEquals(ContextRecoveryStage.FALLBACK_MODEL_SELECTED, restored.getContextRecoveryStage());
        assertEquals("deepseek-v4-pro", restored.getRecoveryModelOverride());
        assertEquals("ctx-transcript", restored.getContextTranscriptArtifactId());
    }

    private DefaultAgentLoopService newService(ModelGateway modelGateway, List<AgentTool> tools) {
        return AgentRuntimeTestFixture.fixture()
                .modelGateway(modelGateway)
                .tools(tools)
                .buildAgentLoop();
    }

    private DefaultAgentLoopService newServiceWithContext(ModelGateway modelGateway,
                                                          List<AgentTool> tools,
                                                          AgentRuntimeProperties properties,
                                                          ContextArtifactRepository artifactRepository,
                                                          ContextBlobStore blobStore) {
        ContextWindowManager contextWindowManager = new ContextWindowManager(properties, artifactRepository, blobStore);
        return AgentRuntimeTestFixture.fixture()
                .modelGateway(modelGateway)
                .tools(tools)
                .properties(properties)
                .contextWindowManager(contextWindowManager)
                .buildAgentLoop();
    }

    private DefaultAgentLoopService statefulService(ModelGateway modelGateway,
                                                    AgentRuntimeProperties properties,
                                                    AgentRunRepository runRepository,
                                                    AgentCheckpointRepository checkpointRepository,
                                                    ContextWindowManager contextWindowManager) {
        return AgentRuntimeTestFixture.fixture()
                .modelGateway(modelGateway)
                .properties(properties)
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository)
                .contextWindowManager(contextWindowManager)
                .buildAgentLoop();
    }

    private AgentContext contextForRoot(String rootRunId) {
        AgentContext context = new AgentContext();
        context.setRunId(rootRunId);
        context.setRootRunId(rootRunId);
        context.setConversationId("conversation-" + rootRunId);
        context.setQuestion("question-" + rootRunId);
        context.setMaxSteps(3);
        context.setStartedAt(java.time.Instant.now());
        return context;
    }

    private DefaultAgentLoopService newService(ModelGateway modelGateway,
                                               List<AgentTool> tools,
                                               ApprovalStore approvalStore,
                                               AgentRunRepository runRepository,
                                               AgentCheckpointRepository checkpointRepository) {
        return AgentRuntimeTestFixture.fixture()
                .modelGateway(modelGateway)
                .tools(tools)
                .approvalStore(approvalStore)
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository)
                .buildAgentLoop();
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

    private ModelGateway usageGateway(String output, int promptTokens, int completionTokens, int totalTokens) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder()
                        .content(output)
                        .finishReason("stop")
                        .usage(TokenUsage.builder()
                                .promptTokens(promptTokens)
                                .completionTokens(completionTokens)
                                .totalTokens(totalTokens)
                                .build())
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
                        .content("{\"type\":\"final\",\"answer\":\"不应调用。\",\"evidence\":[]}")
                        .finishReason("stop")
                        .build());
            }
        };
    }

    private ModelGateway overflowThenFinalGateway(AtomicInteger calls, int overflowCalls, String answer) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                if (calls.incrementAndGet() <= overflowCalls) {
                    return Mono.error(new ModelGatewayException(
                            ModelErrorCode.CONTEXT_OVERFLOW,
                            "context_length_exceeded",
                            false,
                            422,
                            null));
                }
                return Mono.just(ModelChatResult.builder()
                        .content("{\"type\":\"final\",\"answer\":\"" + answer + "\",\"evidence\":[]}")
                        .finishReason("stop")
                        .build());
            }
        };
    }

    private ModelGateway promptRoutingGateway(List<String> prompts) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                prompts.add(prompt.getMessage());
                String message = prompt.getMessage();
                if (message.contains("你是主 Agent 派生出的隔离子 Agent")) {
                    String taskId = message.contains("Loom_Agent-domain") ? "domain" : "app";
                    return Mono.just(ModelChatResult.builder()
                            .content("{\"type\":\"final\",\"answer\":\"{\\\"summary\\\":\\\""
                                    + taskId + " found DeprecatedApi\\\",\\\"findings\\\":[{\\\"file\\\":\\\""
                                    + taskId + "/Demo.java\\\",\\\"line\\\":7,\\\"symbol\\\":\\\"DeprecatedApi\\\",\\\"reason\\\":\\\"direct use\\\"}],\\\"confidence\\\":\\\"high\\\",\\\"truncated\\\":false,\\\"followUp\\\":\\\"\\\"}\",\"evidence\":[{\"file\":\""
                                    + taskId + "/Demo.java\",\"line\":7}]}")
                            .finishReason("stop")
                            .build());
                }
                if (message.contains("sub_agent_summary")) {
                    return Mono.just(ModelChatResult.builder()
                            .content("{\"type\":\"final\",\"answer\":\"汇总完成。\",\"evidence\":[]}")
                            .finishReason("stop")
                            .build());
                }
                return Mono.just(ModelChatResult.builder()
                        .content("{\"type\":\"action\",\"thought\":\"按模块并行搜索\",\"tool\":\"spawn_agents\",\"input\":{\"reason\":\"搜索 DeprecatedApi\",\"maxConcurrency\":2,\"returnMode\":\"summary_only\",\"tasks\":[{\"taskId\":\"domain\",\"role\":\"explorer\",\"question\":\"在 Loom_Agent-domain 下搜索 DeprecatedApi 的使用点\",\"pathScope\":\"Loom_Agent-domain\"},{\"taskId\":\"app\",\"role\":\"explorer\",\"question\":\"在 Loom_Agent-app 下搜索 DeprecatedApi 的使用点\",\"pathScope\":\"Loom_Agent-app\"}]}}")
                        .finishReason("stop")
                        .build());
            }
        };
    }

    private ModelGateway delayedFinalGateway(long delayMs) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.fromCallable(() -> {
                    Thread.sleep(delayMs);
                    return ModelChatResult.builder()
                            .content("{\"type\":\"final\",\"answer\":\"{\\\"summary\\\":\\\"ok\\\",\\\"findings\\\":[],\\\"confidence\\\":\\\"high\\\",\\\"truncated\\\":false,\\\"followUp\\\":\\\"\\\"}\",\"evidence\":[]}")
                            .finishReason("stop")
                            .build();
                });
            }
        };
    }

    private AgentContext parentContextWithSpawnDecision(int taskCount) {
        AgentContext parent = new AgentContext();
        parent.setRunId("parent-run");
        parent.setRootRunId("parent-run");
        parent.setRequestId("parent-request");
        parent.setConversationId("parent-conversation");
        parent.setQuestion("搜集 DeprecatedApi 的使用点");
        parent.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        parent.setWorkspaceDisplayName(".");
        parent.setMaxSteps(3);
        parent.setStartedAt(java.time.Instant.now());
        com.fasterxml.jackson.databind.node.ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("reason", "搜索 DeprecatedApi");
        input.put("maxConcurrency", taskCount);
        com.fasterxml.jackson.databind.node.ArrayNode tasks = input.putArray("tasks");
        for (int i = 1; i <= taskCount; i++) {
            tasks.addObject()
                    .put("taskId", "task-" + i)
                    .put("role", "explorer")
                    .put("question", "搜索 DeprecatedApi 使用点 " + i)
                    .put("pathScope", ".");
        }
        parent.setDecision(AgentDecision.builder()
                .type("action")
                .tool("spawn_agents")
                .input(input)
                .inputView(new java.util.LinkedHashMap<>())
                .build());
        return parent;
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
