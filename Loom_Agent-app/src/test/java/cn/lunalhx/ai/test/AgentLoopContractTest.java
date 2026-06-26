package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentDecision;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.agent.service.AgentContextFactory;
import cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryApprovalStore;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Agent Loop 行为契约（Phase 1 §3）。
 *
 * <p>保护外部可观察行为，不绑定私有方法实现，不锁死完整内部节点顺序。
 * 覆盖：正常执行事件顺序与标识一致性、审批暂停/恢复、Checkpoint 恢复、
 * 用户输入恢复、错误/超时终态与 Flux 正常完成。
 *
 * <p>本类只断言事件类型/顺序/标识/终态等外部可观察不变量；内部节点图细节
 * 由 {@code DefaultAgentLoopServiceTest} 覆盖，这里不重复锁死，以便未来新增节点不致无意义失败。
 */
public class AgentLoopContractTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    // ===== 1. 正常执行 =====

    @Test
    public void normalRunShouldEmitAnswerBeforeDoneAndPreserveIdentifiers() {
        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"搜索\",\"tool\":\"code_search\",\"input\":{\"query\":\"x\"}}",
                        "{\"type\":\"final\",\"answer\":\"最终答案。\",\"evidence\":[]}"))
                .tools(List.of(fakeTool("code_search", "找到结果")));

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("contract-run")
                        .requestId("contract-request")
                        .conversationId("contract-conversation")
                        .question("正常执行")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());

        // ANSWER 出现在 DONE 之前
        assertTrue("ANSWER 必须出现", types.contains(AgentEventType.ANSWER));
        assertTrue("DONE 必须出现", types.contains(AgentEventType.DONE));
        assertTrue("ANSWER 必须在 DONE 之前",
                types.indexOf(AgentEventType.ANSWER) < types.indexOf(AgentEventType.DONE));

        // DONE 后不再产生业务事件（持久化副作用 CHECKPOINT_SAVED 除外）
        int doneIndex = types.indexOf(AgentEventType.DONE);
        List<AgentEventType> afterDone = types.subList(doneIndex + 1, types.size());
        assertTrue("DONE 后只允许 CHECKPOINT_SAVED 持久化副作用，实际：" + afterDone,
                afterDone.stream().allMatch(type -> type == AgentEventType.CHECKPOINT_SAVED));

        // run/request/conversation 标识在整个流中持续一致
        assertTrue(events.stream().allMatch(event -> "contract-run".equals(event.getRunId())));
        assertTrue(events.stream()
                .filter(event -> event.getRequestId() != null)
                .allMatch(event -> "contract-request".equals(event.getRequestId())));
        assertTrue(events.stream()
                .filter(event -> event.getConversationId() != null)
                .allMatch(event -> "contract-conversation".equals(event.getConversationId())));

        // 工具调用与 observation 出现
        assertTrue(types.contains(AgentEventType.TOOL_CALL));
        assertTrue(types.contains(AgentEventType.OBSERVATION));
    }

    @Test
    public void normalRunFluxMustCompleteNotHang() {
        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"final\",\"answer\":\"直接完成。\",\"evidence\":[]}"));

        // block() 正常返回即说明 Flux 已 complete()，否则会超时抛异常
        List<AgentEvent> events = fixture.buildAgentLoop().ask(AgentQuestion.builder()
                        .question("直接完成")
                        .maxSteps(3)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.DONE));
    }

    // ===== 2. 审批暂停与恢复 =====

    @Test
    public void writeToolMustNotExecuteBeforeApprovalAndPauseStreamEndsWithApprovalRequired() {
        AtomicInteger calls = new AtomicInteger();
        AgentTool writeTool = fakeWriteTool("replace_in_file", "updated", calls);
        ApprovalStore approvalStore = new InMemoryApprovalStore();
        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"写\",\"tool\":\"replace_in_file\",\"input\":{\"path\":\"Demo.java\",\"oldText\":\"a\",\"newText\":\"b\"}}"))
                .tools(List.of(writeTool))
                .approvalStore(approvalStore);

        List<AgentEvent> paused = fixture.buildAgentLoop().ask(AgentQuestion.builder()
                        .question("改 Demo.java")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(TIMEOUT);

        // 写操作在审批前不得执行
        assertEquals(0, calls.get());
        // 暂停流以 APPROVAL_REQUIRED 结束（其后只允许 CHECKPOINT_SAVED 持久化副作用）
        AgentEvent approval = paused.stream()
                .filter(event -> event.getType() == AgentEventType.APPROVAL_REQUIRED)
                .findFirst()
                .orElseThrow();
        assertEquals("replace_in_file", approval.getTool());
        int approvalIndex = paused.indexOf(approval);
        List<AgentEventType> afterApproval = paused.stream()
                .skip(approvalIndex + 1L)
                .map(AgentEvent::getType)
                .collect(Collectors.toList());
        assertTrue("APPROVAL_REQUIRED 后只允许 CHECKPOINT_SAVED，实际：" + afterApproval,
                afterApproval.stream().allMatch(type -> type == AgentEventType.CHECKPOINT_SAVED));
        // DONE 不应出现在暂停流中
        assertFalse(paused.stream().anyMatch(event -> event.getType() == AgentEventType.DONE));
    }

    @Test
    public void approveResumeShouldStartWithResumeStartedAndExecuteOriginalTool() {
        AtomicInteger calls = new AtomicInteger();
        AgentTool writeTool = fakeWriteTool("write_file", "written", calls);
        ApprovalStore approvalStore = new InMemoryApprovalStore();
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"写\",\"tool\":\"write_file\",\"input\":{\"path\":\"Demo.java\",\"content\":\"x\",\"mode\":\"create\"}}",
                        "{\"type\":\"final\",\"answer\":\"已写入。\",\"evidence\":[]}"))
                .tools(List.of(writeTool))
                .approvalStore(approvalStore)
                .properties(properties)
                .runRepository(new InMemoryAgentRunRepository())
                .checkpointRepository(new InMemoryAgentCheckpointRepository());

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        String approvalId = service.ask(AgentQuestion.builder()
                        .question("创建 Demo.java")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(TIMEOUT)
                .stream()
                .filter(event -> event.getType() == AgentEventType.APPROVAL_REQUIRED)
                .findFirst()
                .orElseThrow()
                .getApprovalId();

        List<AgentEvent> resumed = service.resume(approvalId, ApprovalDecision.APPROVE, "ok")
                .collectList()
                .block(TIMEOUT);

        // 恢复流首先包含 RESUME_STARTED
        assertEquals(AgentEventType.RESUME_STARTED, resumed.get(0).getType());
        // APPROVE 执行原工具
        assertEquals(1, calls.get());
        assertTrue(resumed.stream().anyMatch(event -> event.getType() == AgentEventType.TOOL_CALL));
        assertTrue(resumed.stream().anyMatch(event -> event.getType() == AgentEventType.OBSERVATION
                && event.getObservation().contains("written")));
        assertEquals("已写入。",
                resumed.stream().filter(event -> event.getType() == AgentEventType.ANSWER)
                        .findFirst().orElseThrow().getAnswer());
    }

    @Test
    public void rejectResumeShouldProduceRejectionObservationWithoutExecutingTool() {
        AtomicInteger calls = new AtomicInteger();
        AgentTool writeTool = fakeWriteTool("write_file", "should not run", calls);
        ApprovalStore approvalStore = new InMemoryApprovalStore();
        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"写\",\"tool\":\"write_file\",\"input\":{\"path\":\"Demo.java\",\"content\":\"x\",\"mode\":\"create\"}}",
                        "{\"type\":\"final\",\"answer\":\"已拒绝。\",\"evidence\":[]}"))
                .tools(List.of(writeTool))
                .approvalStore(approvalStore);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        String approvalId = service.ask(AgentQuestion.builder()
                        .question("创建 Demo.java")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(TIMEOUT)
                .stream()
                .filter(event -> event.getType() == AgentEventType.APPROVAL_REQUIRED)
                .findFirst()
                .orElseThrow()
                .getApprovalId();

        List<AgentEvent> resumed = service.resume(approvalId, ApprovalDecision.REJECT, "不允许")
                .collectList()
                .block(TIMEOUT);

        // REJECT 不执行原工具
        assertEquals(0, calls.get());
        // 生成拒绝 observation
        assertTrue(resumed.stream().anyMatch(event -> event.getType() == AgentEventType.OBSERVATION
                && event.getObservation().contains("approval_rejected")));
    }

    // ===== 3. Checkpoint 恢复 =====

    @Test
    public void resumeRunShouldNotRepeatUnsafeToolAndReplanInstead() {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        AtomicInteger calls = new AtomicInteger();

        // 构造一个停在 TOOL_DISPATCH 且 unsafeResumeRequired 的 checkpoint
        AgentContext context = new AgentContext();
        context.setRunId("unsafe-run");
        context.setRequestId("unsafe-request");
        context.setConversationId("unsafe-conversation");
        context.setQuestion("恢复不安全工具");
        context.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        context.setWorkspaceDisplayName(".");
        context.setMaxSteps(6);
        context.setStartedAt(Instant.now());
        context.setCurrentNode(AgentNodeNames.TOOL_DISPATCH);
        context.setUnsafeResumeRequired(true);
        context.setToolSpecs(List.of(ToolSpec.builder().name("replace_in_file").description("write").inputSchema("{}").build()));
        checkpointRepository.save(AgentCheckpoint.builder()
                .runId("unsafe-run")
                .currentNode(AgentNodeNames.TOOL_DISPATCH)
                .contextSnapshot(AgentContextSnapshot.from(context))
                .reason("before_tool:replace_in_file")
                .build());

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"final\",\"answer\":\"未重复写入，已检查状态。\",\"evidence\":[]}"))
                .tools(List.of(fakeWriteTool("replace_in_file", "should not execute", calls)))
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository);

        List<AgentEvent> events = fixture.buildAgentLoop().resumeRun("unsafe-run")
                .collectList()
                .block(TIMEOUT);

        // 不重复执行不安全工具
        assertEquals(0, calls.get());
        // 转为 REPLAN
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.REPLAN_STARTED));
    }

    @Test
    public void resumeRunWithMissingCheckpointShouldReturnStableErrorCodeAndComplete() {
        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(), "{\"type\":\"final\",\"answer\":\"x\",\"evidence\":[]}"));

        List<AgentEvent> events = fixture.buildAgentLoop().resumeRun("no-such-checkpoint-run")
                .collectList()
                .block(TIMEOUT);

        // 缺少 checkpoint 返回稳定错误码 checkpoint_not_found，且 Flux 正常完成
        AgentEvent error = events.stream()
                .filter(event -> event.getType() == AgentEventType.ERROR)
                .findFirst()
                .orElseThrow();
        assertEquals("checkpoint_not_found", error.getCode());
        assertEquals(events.size() - 1, events.indexOf(error));
    }

    // ===== 4. 用户输入恢复 =====

    @Test
    public void continueWithoutMessageShouldReturnInvalidUserInput() {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        AgentContext context = waitingUserInputContext("input-run");
        checkpointRepository.save(AgentCheckpoint.builder()
                .runId("input-run")
                .currentNode(AgentNodeNames.USER_INPUT_GATE)
                .contextSnapshot(AgentContextSnapshot.from(context))
                .reason("after_node:user_input_gate")
                .build());

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(), "{\"type\":\"final\",\"answer\":\"x\",\"evidence\":[]}"))
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository);

        List<AgentEvent> events = fixture.buildAgentLoop()
                .resumeWithUserInput("input-run", UserInputAction.CONTINUE, null)
                .collectList()
                .block(TIMEOUT);

        // CONTINUE 必须携带非空 message，否则 invalid_user_input
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.ERROR
                && "invalid_user_input".equals(event.getCode())));
    }

    @Test
    public void abortWhileWaitingShouldKeepContextOverflowTerminalSemantics() {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        AgentContext context = waitingUserInputContext("abort-run");
        checkpointRepository.save(AgentCheckpoint.builder()
                .runId("abort-run")
                .currentNode(AgentNodeNames.USER_INPUT_GATE)
                .contextSnapshot(AgentContextSnapshot.from(context))
                .reason("after_node:user_input_gate")
                .build());

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(), "{\"type\":\"final\",\"answer\":\"never\",\"evidence\":[]}"))
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository);

        List<AgentEvent> events = fixture.buildAgentLoop()
                .resumeWithUserInput("abort-run", UserInputAction.ABORT, null)
                .collectList()
                .block(TIMEOUT);

        // ABORT 保持 CONTEXT_OVERFLOW 终止语义：ERROR code=context_length_exceeded + DONE stopReason=CONTEXT_OVERFLOW
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.ERROR
                && cn.lunalhx.ai.domain.model.valobj.ModelErrorCode.CONTEXT_OVERFLOW.code().equals(event.getCode())));
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.DONE
                && event.getStopReason() == AgentStopReason.CONTEXT_OVERFLOW));
    }

    // ===== 5. 错误和超时 =====

    @Test
    public void unknownNodeShouldTerminateWithErrorAndComplete() {
        // 通过一个总是返回未知工具决策的 gateway，触发 decision 节点处理未知工具路径；
        // 这里直接断言：连续解析错误最终进入 ERROR/DONE 终态且 Flux 完成。
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setParseErrorMaxAttempts(1);
        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(), "not json", "still not json"))
                .properties(properties);

        List<AgentEvent> events = fixture.buildAgentLoop().ask(AgentQuestion.builder()
                        .question("触发错误")
                        .maxSteps(4)
                        .build())
                .collectList()
                .block(TIMEOUT);

        AgentEvent error = events.stream()
                .filter(event -> event.getType() == AgentEventType.ERROR)
                .findFirst()
                .orElseThrow();
        assertEquals("parse_error", error.getCode());
        // 进入终态且 Flux 完成
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.DONE));
    }

    @Test
    public void totalTimeoutShouldReachDoneTerminalAndComplete() {
        // totalTimeoutMs 设到极小值；gateway 始终返回 action（永不 final），
        // runLoop 在后续迭代命中 isTotalTimeout -> FAIL -> ERROR(agent_timeout) + DONE(TIMEOUT)
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setTotalTimeoutMs(-1L);
        properties.setMaxSteps(200);
        properties.getStepBudget().setMaxTotalSteps(10000);
        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"循环\",\"tool\":\"code_search\",\"input\":{\"query\":\"x\"}}"))
                .tools(List.of(fakeTool("code_search", "ok")))
                .properties(properties);

        List<AgentEvent> events = fixture.buildAgentLoop().ask(AgentQuestion.builder()
                        .question("触发总超时")
                        .maxSteps(200)
                        .maxSegments(1)
                        .build())
                .collectList()
                .block(TIMEOUT);

        // 总超时最终进入 ERROR + DONE 终态且 stopReason=TIMEOUT，Flux 正常完成
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.ERROR
                && "agent_timeout".equals(event.getCode())));
        assertTrue(events.stream().anyMatch(event -> event.getType() == AgentEventType.DONE
                && event.getStopReason() == AgentStopReason.TIMEOUT));
    }

    // ===== 6. Stop hook 行为契约 =====

    @Test
    public void incompletePlanStopHookShouldSuppressAnswerAndRouteToReplan() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getStopHooks().getIncompletePlan().setMaxContinuations(1);

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"设定计划\",\"tool\":\"todo_write\",\"input\":{\"todos\":[{\"id\":\"task-1\",\"content\":\"分析需求\",\"status\":\"completed\"},{\"id\":\"task-2\",\"content\":\"实现代码\",\"status\":\"in_progress\"},{\"id\":\"task-3\",\"content\":\"编写测试\",\"status\":\"pending\"}]}}",
                        "{\"type\":\"final\",\"answer\":\"完成了！\",\"evidence\":[]}",
                        "{\"type\":\"final\",\"answer\":\"现在真的完成了。\",\"evidence\":[]}"))
                .tools(List.of(new cn.lunalhx.ai.infrastructure.tool.TodoWriteTool()))
                .properties(properties);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("stop-hook-continue")
                        .requestId("stop-hook-continue")
                        .conversationId("stop-hook-continue")
                        .question("实现某功能")
                        .maxSteps(10)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());

        assertTrue("应包含 STOP_HOOK_RESULT", types.contains(AgentEventType.STOP_HOOK_RESULT));
        assertTrue("应包含 REPLAN_STARTED", types.contains(AgentEventType.REPLAN_STARTED));
        assertTrue("应只有一次 DONE", types.stream().filter(t -> t == AgentEventType.DONE).count() == 1);

        AgentEvent hookResult = events.stream()
                .filter(e -> e.getType() == AgentEventType.STOP_HOOK_RESULT)
                .findFirst().orElseThrow();
        assertEquals("continued", hookResult.getMetadata().get("decision"));
        assertEquals("replan", hookResult.getMetadata().get("nextNode"));

        // DONE 后只允许 CHECKPOINT_SAVED
        int doneIndex = types.lastIndexOf(AgentEventType.DONE);
        List<AgentEventType> afterDone = types.subList(doneIndex + 1, types.size());
        assertTrue("DONE 后只允许 CHECKPOINT_SAVED，实际：" + afterDone,
                afterDone.stream().allMatch(type -> type == AgentEventType.CHECKPOINT_SAVED));
    }

    @Test
    public void incompletePlanStopHookShouldBypassWhenMaxContinuationsExceeded() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getStopHooks().getIncompletePlan().setMaxContinuations(0);

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"设定计划\",\"tool\":\"todo_write\",\"input\":{\"todos\":[{\"id\":\"task-1\",\"content\":\"分析需求\",\"status\":\"completed\"},{\"id\":\"task-2\",\"content\":\"实现代码\",\"status\":\"pending\"}]}}",
                        "{\"type\":\"final\",\"answer\":\"完成。\",\"evidence\":[]}"))
                .tools(List.of(new cn.lunalhx.ai.infrastructure.tool.TodoWriteTool()))
                .properties(properties);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("stop-hook-bypass")
                        .requestId("stop-hook-bypass")
                        .conversationId("stop-hook-bypass")
                        .question("实现某功能")
                        .maxSteps(10)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());

        assertTrue("应包含 STOP_HOOK_RESULT", types.contains(AgentEventType.STOP_HOOK_RESULT));
        assertTrue("应包含 ANSWER", types.contains(AgentEventType.ANSWER));
        assertTrue("应包含 DONE", types.contains(AgentEventType.DONE));

        AgentEvent hookResult = events.stream()
                .filter(e -> e.getType() == AgentEventType.STOP_HOOK_RESULT)
                .findFirst().orElseThrow();
        assertEquals("bypassed", hookResult.getMetadata().get("decision"));
    }

    @Test
    public void noPlanShouldNotTriggerIncompletePlanStopHook() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"final\",\"answer\":\"直接完成。\",\"evidence\":[]}"))
                .properties(properties);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("stop-hook-no-plan")
                        .requestId("stop-hook-no-plan")
                        .conversationId("stop-hook-no-plan")
                        .question("简单问题")
                        .maxSteps(4)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());

        assertFalse("无计划时不应触发 STOP_HOOK_RESULT", types.contains(AgentEventType.STOP_HOOK_RESULT));
        assertTrue("应包含 ANSWER", types.contains(AgentEventType.ANSWER));
        assertTrue("应包含 DONE", types.contains(AgentEventType.DONE));
    }

    @Test
    public void completedPlanShouldNotTriggerIncompletePlanStopHook() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"设定并完成计划\",\"tool\":\"todo_write\",\"input\":{\"todos\":[{\"id\":\"task-1\",\"content\":\"分析需求\",\"status\":\"completed\"}]}}",
                        "{\"type\":\"final\",\"answer\":\"计划已全部完成。\",\"evidence\":[]}"))
                .tools(List.of(new cn.lunalhx.ai.infrastructure.tool.TodoWriteTool()))
                .properties(properties);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("stop-hook-completed")
                        .requestId("stop-hook-completed")
                        .conversationId("stop-hook-completed")
                        .question("简单问题")
                        .maxSteps(8)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());

        assertFalse("计划已完成时不应触发 STOP_HOOK_RESULT", types.contains(AgentEventType.STOP_HOOK_RESULT));
        assertTrue("应包含 ANSWER", types.contains(AgentEventType.ANSWER));
    }

    @Test
    public void childRunShouldNotTriggerIncompletePlanStopHookWhenRootOnly() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getStopHooks().getIncompletePlan().setRootOnly(true);
        properties.getStopHooks().getIncompletePlan().setMaxContinuations(1);

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"设定计划\",\"tool\":\"todo_write\",\"input\":{\"todos\":[{\"id\":\"task-1\",\"content\":\"分析\",\"status\":\"in_progress\"}]}}",
                        "{\"type\":\"final\",\"answer\":\"子Agent完成。\",\"evidence\":[]}"))
                .tools(List.of(new cn.lunalhx.ai.infrastructure.tool.TodoWriteTool()))
                .properties(properties);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("stop-hook-child")
                        .requestId("stop-hook-child")
                        .conversationId("stop-hook-child")
                        .parentRunId("parent-run")
                        .rootRunId("parent-run")
                        .agentRole(cn.lunalhx.ai.domain.agent.model.valobj.AgentRole.EXPLORER)
                        .agentDepth(1)
                        .question("子Agent搜索任务")
                        .maxSteps(8)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());

        assertFalse("子Agent不应触发 incomplete plan stop hook", types.contains(AgentEventType.STOP_HOOK_RESULT));
        assertTrue("应包含 ANSWER", types.contains(AgentEventType.ANSWER));
    }

    @Test
    public void pendingApprovalConsistencyStopHookShouldRouteToFailWhenApprovalMissing() {
        // 使用一个 "丢失写入" 的 ApprovalStore：save 正常但 find 永远查不到
        ApprovalStore forgetfulStore = new ApprovalStore() {
            private final java.util.Map<String, cn.lunalhx.ai.domain.agent.model.entity.PendingApproval> store =
                    new java.util.concurrent.ConcurrentHashMap<>();

            @Override
            public cn.lunalhx.ai.domain.agent.model.entity.PendingApproval save(
                    cn.lunalhx.ai.domain.agent.model.entity.PendingApproval approval) {
                store.put(approval.getApprovalId(), approval);
                return approval;
            }

            @Override
            public java.util.Optional<cn.lunalhx.ai.domain.agent.model.entity.PendingApproval> find(String approvalId) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<cn.lunalhx.ai.domain.agent.model.entity.PendingApproval> consume(String approvalId) {
                return java.util.Optional.ofNullable(store.remove(approvalId));
            }
        };

        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"写文件\",\"tool\":\"replace_in_file\",\"input\":{\"path\":\"Demo.java\",\"oldText\":\"a\",\"newText\":\"b\"}}"))
                .tools(List.of(fakeWriteTool("replace_in_file", "updated", new AtomicInteger())))
                .approvalStore(forgetfulStore)
                .properties(properties);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("stop-hook-approval-missing")
                        .requestId("stop-hook-approval-missing")
                        .conversationId("stop-hook-approval-missing")
                        .question("修改 Demo.java")
                        .maxSteps(6)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());

        assertTrue("应包含 STOP_HOOK_RESULT", types.contains(AgentEventType.STOP_HOOK_RESULT));
        AgentEvent hookResult = events.stream()
                .filter(e -> e.getType() == AgentEventType.STOP_HOOK_RESULT)
                .findFirst().orElseThrow();
        assertEquals("intercepted", hookResult.getMetadata().get("decision"));
        assertEquals("approval_state_missing", hookResult.getMetadata().get("reason"));

        assertTrue("应包含 ERROR", types.contains(AgentEventType.ERROR));
        AgentEvent error = events.stream()
                .filter(e -> e.getType() == AgentEventType.ERROR)
                .findFirst().orElseThrow();
        assertEquals(cn.lunalhx.ai.domain.model.valobj.ModelErrorCode.APPROVAL_STATE_MISSING.code(), error.getCode());

        assertTrue("应包含 DONE", types.contains(AgentEventType.DONE));
        assertFalse("不应包含 APPROVAL_REQUIRED", types.contains(AgentEventType.APPROVAL_REQUIRED));
    }

    @Test
    public void stopHookContinuationCountShouldSurviveSnapshotRoundTrip() {
        AgentContext context = new AgentContext();
        context.setRunId("snapshot-run");
        context.setStopHookContinuationCount(2);

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);
        AgentContext restored = snapshot.restore();

        assertEquals(2, restored.getStopHookContinuationCount());
    }

    // ===== 10. Step budget 行为契约 =====

    @Test
    public void segmentContinuationShouldAutoContinueToReplanAndReachAnswer() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setMaxSteps(2);
        properties.getStepBudget().setMaxSegments(3);
        properties.getStepBudget().setMaxTotalSteps(20);
        properties.getStepBudget().setSameActionMaxRepeats(10);
        properties.getStepBudget().setSameFailureMaxRepeats(10);

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"搜索\",\"tool\":\"code_search\",\"input\":{\"query\":\"a\"}}",
                        "{\"type\":\"action\",\"thought\":\"再搜\",\"tool\":\"code_search\",\"input\":{\"query\":\"b\"}}",
                        "{\"type\":\"final\",\"answer\":\"完成\",\"evidence\":[]}"))
                .tools(List.of(fakeTool("code_search", "ok")))
                .properties(properties);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("segment-continue")
                        .requestId("segment-continue")
                        .conversationId("segment-continue")
                        .question("分段续跑测试")
                        .maxSteps(2)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());

        assertTrue("应包含 STOP_HOOK_RESULT", types.contains(AgentEventType.STOP_HOOK_RESULT));
        assertTrue("应包含 ANSWER", types.contains(AgentEventType.ANSWER));
        assertTrue("应包含 DONE", types.contains(AgentEventType.DONE));
        assertFalse("不应以 ERROR 结束",
                events.stream().anyMatch(e -> e.getType() == AgentEventType.ERROR));
    }

    @Test
    public void singleSegmentShouldStopWithMaxStepsError() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setMaxSteps(3);
        properties.getStepBudget().setMaxSegments(1);
        properties.getStepBudget().setMaxTotalSteps(10);
        properties.getStepBudget().setSameActionMaxRepeats(10);
        properties.getStepBudget().setSameFailureMaxRepeats(10);

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"搜索\",\"tool\":\"code_search\",\"input\":{\"query\":\"a\"}}",
                        "{\"type\":\"action\",\"thought\":\"再搜\",\"tool\":\"code_search\",\"input\":{\"query\":\"b\"}}",
                        "{\"type\":\"action\",\"thought\":\"三搜\",\"tool\":\"code_search\",\"input\":{\"query\":\"c\"}}"))
                .tools(List.of(fakeTool("code_search", "ok")))
                .properties(properties);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("single-segment")
                        .requestId("single-segment")
                        .conversationId("single-segment")
                        .question("单段测试")
                        .maxSteps(3)
                        .maxSegments(1)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.ERROR));
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.DONE
                && e.getStopReason() == AgentStopReason.MAX_STEPS));
    }

    @Test
    public void maxTotalStepsShouldTerminateBeforeSegmentExhaustion() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setMaxSteps(10);
        properties.getStepBudget().setMaxSegments(5);
        properties.getStepBudget().setMaxTotalSteps(3);
        properties.getStepBudget().setSameActionMaxRepeats(10);
        properties.getStepBudget().setSameFailureMaxRepeats(10);

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"搜索\",\"tool\":\"code_search\",\"input\":{\"query\":\"a\"}}",
                        "{\"type\":\"action\",\"thought\":\"再搜\",\"tool\":\"code_search\",\"input\":{\"query\":\"b\"}}",
                        "{\"type\":\"action\",\"thought\":\"三搜\",\"tool\":\"code_search\",\"input\":{\"query\":\"c\"}}",
                        "{\"type\":\"action\",\"thought\":\"四搜\",\"tool\":\"code_search\",\"input\":{\"query\":\"d\"}}"))
                .tools(List.of(fakeTool("code_search", "ok")))
                .properties(properties);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("total-steps")
                        .requestId("total-steps")
                        .conversationId("total-steps")
                        .question("全局步数测试")
                        .maxSteps(10)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.ERROR
                && "max_steps_total".equals(e.getCode())));
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.DONE
                && e.getStopReason() == AgentStopReason.MAX_STEPS));
    }

    @Test
    public void repeatedActionShouldTriggerNoProgress() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.setMaxSteps(10);
        properties.getStepBudget().setMaxSegments(5);
        properties.getStepBudget().setMaxTotalSteps(100);
        properties.getStepBudget().setSameActionMaxRepeats(2);
        properties.getStepBudget().setSameFailureMaxRepeats(10);

        AgentRuntimeTestFixture fixture = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway(new ArrayList<>(),
                        "{\"type\":\"action\",\"thought\":\"搜索\",\"tool\":\"code_search\",\"input\":{\"query\":\"x\"}}",
                        "{\"type\":\"action\",\"thought\":\"搜索\",\"tool\":\"code_search\",\"input\":{\"query\":\"x\"}}",
                        "{\"type\":\"action\",\"thought\":\"搜索\",\"tool\":\"code_search\",\"input\":{\"query\":\"x\"}}"))
                .tools(List.of(fakeTool("code_search", "ok")))
                .properties(properties);

        DefaultAgentLoopService service = fixture.buildAgentLoop();
        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .runId("no-progress")
                        .requestId("no-progress")
                        .conversationId("no-progress")
                        .question("重复动作测试")
                        .maxSteps(10)
                        .build())
                .collectList()
                .block(TIMEOUT);

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.ERROR
                && "repeated_action".equals(e.getCode())));
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.DONE
                && e.getStopReason() == AgentStopReason.NO_PROGRESS));
    }

    @Test
    public void segmentFieldsShouldSurviveSnapshotRoundTrip() {
        AgentContext context = new AgentContext();
        context.setRunId("snapshot-segment");
        context.setSegmentIndex(2);
        context.setSegmentStartStep(10);
        context.setMaxSegments(5);
        context.setMaxTotalSteps(150);
        context.setLastActionFingerprint("abc123");
        context.setSameActionRepeats(1);
        context.setLastFailureFingerprint("def456");
        context.setSameFailureRepeats(0);
        context.setNoProgressRounds(2);

        AgentContextSnapshot snapshot = AgentContextSnapshot.from(context);
        AgentContext restored = snapshot.restore();

        assertEquals(2, restored.getSegmentIndex());
        assertEquals(10, restored.getSegmentStartStep());
        assertEquals(5, restored.getMaxSegments());
        assertEquals(150, restored.getMaxTotalSteps());
        assertEquals("abc123", restored.getLastActionFingerprint());
        assertEquals(1, restored.getSameActionRepeats());
        assertEquals("def456", restored.getLastFailureFingerprint());
        assertEquals(0, restored.getSameFailureRepeats());
        assertEquals(2, restored.getNoProgressRounds());
    }

    @Test
    public void childAgentShouldHaveLimitedSegments() {
        AgentRuntimeProperties properties = AgentRuntimeTestFixture.standardProperties();
        properties.getStepBudget().setMaxSegments(5);
        properties.getStepBudget().setChildMaxSegments(2);

        AgentContext context = new AgentContext();
        context.setRunId("child-run");
        context.setParentRunId("parent-run");
        context.setRootRunId("parent-run");
        context.setAgentRole(cn.lunalhx.ai.domain.agent.model.valobj.AgentRole.EXPLORER);
        context.setAgentDepth(1);

        AgentQuestion question = AgentQuestion.builder()
                .runId("child-run")
                .parentRunId("parent-run")
                .rootRunId("parent-run")
                .agentRole(cn.lunalhx.ai.domain.agent.model.valobj.AgentRole.EXPLORER)
                .agentDepth(1)
                .question("子 agent 任务")
                .maxSteps(6)
                .build();

        AgentContextFactory factory = new AgentContextFactory(
                properties,
                new AgentWorkspaceResolver(properties),
                List.of(),
                false);
        AgentContext created = factory.create(question);

        assertEquals("子 Agent maxSegments 应为 min(5,2)", 2, created.getMaxSegments());
    }

    // ===== 辅助 =====

    private AgentContext waitingUserInputContext(String runId) {
        AgentContext context = new AgentContext();
        context.setRunId(runId);
        context.setRootRunId(runId);
        context.setRequestId("request-" + runId);
        context.setConversationId("conversation-" + runId);
        context.setQuestion("等待用户输入");
        context.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        context.setWorkspaceDisplayName(".");
        context.setMaxSteps(6);
        context.setStartedAt(Instant.now());
        context.setCurrentNode(AgentNodeNames.USER_INPUT_GATE);
        context.setContextRecoveryStage(ContextRecoveryStage.WAITING_USER_INPUT);
        context.setToolSpecs(List.of());
        context.setDecision(AgentDecision.builder()
                .type("final")
                .input(new ObjectMapper().createObjectNode())
                .build());
        return context;
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
                if (prompts != null) {
                    prompts.add(prompt.getMessage());
                }
                int current = Math.min(index.getAndIncrement(), outputs.length - 1);
                return Mono.just(ModelChatResult.builder().content(outputs[current]).finishReason("stop").build());
            }
        };
    }

    private AgentTool fakeTool(String name, String observation) {
        return new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success(observation, false, 1L);
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
                return ToolSpec.builder().name(name).description(name).inputSchema("{}").build();
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
}
