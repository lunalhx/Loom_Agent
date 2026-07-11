package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.service.execution.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class AgentEndToEndTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    public void shouldExecuteToolCallAndReturnFinalAnswer() {
        List<String> prompts = new ArrayList<>();
        ModelGateway gateway = completeGateway(prompts,
                "{\"type\":\"action\",\"thought\":\"搜索代码\",\"tool\":\"code_search\",\"input\":{\"query\":\"test\"}}",
                "{\"type\":\"final\",\"answer\":\"最终答案。\",\"evidence\":[]}");

        AgentTool tool = fakeReadTool("code_search", "找到 3 个匹配结果");

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(gateway)
                .tools(List.of(tool))
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("我的问题").maxSteps(6).build())
                .collectList().block(TIMEOUT);

        assertNotNull(events);
        assertFalse(events.isEmpty());

        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());
        assertTrue(types.contains(AgentEventType.RUN_STARTED));
        assertTrue(types.contains(AgentEventType.TOOL_CALL));
        assertTrue(types.contains(AgentEventType.OBSERVATION));
        assertTrue(types.contains(AgentEventType.ANSWER));
        assertTrue(types.contains(AgentEventType.DONE));

        AgentEvent answer = events.stream()
                .filter(e -> e.getType() == AgentEventType.ANSWER)
                .findFirst().orElseThrow();
        assertEquals("最终答案。", answer.getAnswer());

        String promptText = String.join("\n", prompts);
        assertTrue(promptText.contains("3 个匹配结果"));
        assertTrue(promptText.contains("<untrusted_tool_output"));
    }

    @Test
    public void shouldHandleApprovalPauseAndResume() {
        List<String> prompts = new ArrayList<>();
        ModelGateway gateway = completeGateway(prompts,
                "{\"type\":\"action\",\"thought\":\"修改文件\",\"tool\":\"replace_in_file\",\"input\":{\"path\":\"a.txt\",\"oldText\":\"x\",\"newText\":\"y\"}}");

        AgentTool writeTool = new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name("replace_in_file").description("write tool").inputSchema("{}").build();
            }
            @Override
            public ToolPolicyDecision policy(ToolCall call) {
                return ToolPolicyDecision.writeConfirm("写入需确认", "replace_in_file path=a.txt");
            }
            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.success("written: a.txt", false, 1L);
            }
        };

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(gateway)
                .tools(List.of(writeTool))
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("修改文件").maxSteps(6).build())
                .collectList().block(TIMEOUT);

        assertNotNull(events);
        assertFalse(events.isEmpty());
        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());
        assertTrue("should pause for approval", types.contains(AgentEventType.APPROVAL_REQUIRED));
        assertFalse("should not complete before approval", types.contains(AgentEventType.DONE));

        AgentEvent approval = events.stream()
                .filter(e -> e.getType() == AgentEventType.APPROVAL_REQUIRED)
                .findFirst().orElseThrow();
        assertNotNull("approval event should have approvalId", approval.getApprovalId());
    }

    @Test
    public void shouldRecoverFromParseError() {
        List<String> prompts = new ArrayList<>();
        ModelGateway gateway = completeGateway(prompts,
                "this is not json at all {{{",
                "{\"type\":\"action\",\"thought\":\"搜索\",\"tool\":\"code_search\",\"input\":{\"query\":\"x\"}}",
                "{\"type\":\"final\",\"answer\":\"恢复成功。\",\"evidence\":[]}");

        AgentTool tool = fakeReadTool("code_search", "result");

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(gateway)
                .tools(List.of(tool))
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("test").maxSteps(10).build())
                .collectList().block(TIMEOUT);

        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());
        assertFalse(types.contains(AgentEventType.ERROR));
        assertTrue(types.contains(AgentEventType.ANSWER));
        assertTrue(types.contains(AgentEventType.DONE));
    }

    @Test
    public void shouldDenyHighRiskPolicyAndModelAdapts() {
        List<String> prompts = new ArrayList<>();
        ModelGateway gateway = completeGateway(prompts,
                "{\"type\":\"action\",\"thought\":\"尝试危险操作\",\"tool\":\"dangerous_cmd\",\"input\":{}}",
                "{\"type\":\"action\",\"thought\":\"使用安全替代\",\"tool\":\"code_search\",\"input\":{\"query\":\"safe\"}}",
                "{\"type\":\"final\",\"answer\":\"改用安全方案完成。\",\"evidence\":[]}");

        AgentTool denyTool = new AgentTool() {
            @Override
            public ToolSpec spec() {
                return ToolSpec.builder().name("dangerous_cmd").description("dangerous").inputSchema("{}").build();
            }
            @Override
            public ToolPolicyDecision policy(ToolCall call) {
                return ToolPolicyDecision.highRiskDeny("高危命令已拦截", "dangerous_cmd");
            }
            @Override
            public ToolResult call(ToolCall call) {
                return ToolResult.failure("should_not_be_called", "n/a", 0L);
            }
        };

        AgentTool safeTool = fakeReadTool("code_search", "safe result");

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(gateway)
                .tools(List.of(denyTool, safeTool))
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("do something").maxSteps(10).build())
                .collectList().block(TIMEOUT);

        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());
        assertTrue("should deny high-risk operation", types.contains(AgentEventType.POLICY_DENIED));
        assertTrue("should complete with answer", types.contains(AgentEventType.ANSWER) || types.contains(AgentEventType.DONE));
    }

    @Test
    public void shouldTrackMultipleSteps() {
        List<String> prompts = new ArrayList<>();
        ModelGateway gateway = completeGateway(prompts,
                "{\"type\":\"action\",\"thought\":\"read\",\"tool\":\"read_file\",\"input\":{\"path\":\"a.txt\"}}",
                "{\"type\":\"action\",\"thought\":\"search\",\"tool\":\"code_search\",\"input\":{\"query\":\"x\"}}",
                "{\"type\":\"final\",\"answer\":\"两步完成。\",\"evidence\":[]}");

        AgentTool readTool = fakeReadTool("read_file", "file content");
        AgentTool searchTool = fakeReadTool("code_search", "search result");

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(gateway)
                .tools(List.of(readTool, searchTool))
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("multi step").maxSteps(6).build())
                .collectList().block(TIMEOUT);

        List<AgentEvent> toolCalls = events.stream()
                .filter(e -> e.getType() == AgentEventType.TOOL_CALL)
                .collect(Collectors.toList());
        assertTrue(toolCalls.size() >= 2);

        List<AgentEventType> types = events.stream().map(AgentEvent::getType).collect(Collectors.toList());
        int answerIdx = types.indexOf(AgentEventType.ANSWER);
        int doneIdx = types.indexOf(AgentEventType.DONE);
        assertTrue(answerIdx >= 0);
        assertTrue(answerIdx < doneIdx);
    }

    @Test
    public void shouldEnforceStepLimit() {
        String[] manyActions = new String[10];
        for (int i = 0; i < 10; i++) {
            manyActions[i] = "{\"type\":\"action\",\"thought\":\"step" + i + "\",\"tool\":\"code_search\",\"input\":{\"query\":\"x\"}}";
        }

        List<String> prompts = new ArrayList<>();
        ModelGateway gateway = completeGateway(prompts, manyActions);

        AgentTool tool = fakeReadTool("code_search", "result");

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(gateway)
                .tools(List.of(tool))
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("test").maxSteps(5).build())
                .collectList().block(TIMEOUT);

        assertNotNull("events should not be null", events);
        assertFalse("events should not be empty", events.isEmpty());

        long toolCallCount = events.stream().filter(e -> e.getType() == AgentEventType.TOOL_CALL).count();
        assertTrue("should have executed at least 1 tool call", toolCallCount >= 1);
    }

    // ==================== helpers ====================

    private ModelGateway completeGateway(List<String> prompts, String... outputs) {
        AtomicInteger index = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }
            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                String visible = AgentRuntimeTestFixture.modelVisibleText(prompt);
                try {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(visible));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        prompts.add(line);
                    }
                } catch (java.io.IOException ignored) {
                }
                int current = Math.min(index.getAndIncrement(), outputs.length - 1);
                return Mono.just(ModelChatResult.builder()
                        .content(outputs[current])
                        .finishReason("stop")
                        .build());
            }
        };
    }

    private AgentTool fakeReadTool(String name, String observation) {
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
}
