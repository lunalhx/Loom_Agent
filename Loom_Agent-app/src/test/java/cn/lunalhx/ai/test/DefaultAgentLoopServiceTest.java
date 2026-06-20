package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DefaultAgentLoopServiceTest {

    @Test
    public void shouldCallToolsAndReturnFinalAnswer() {
        ModelGateway modelGateway = completeGateway(
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
        assertEquals("DefaultChatStreamService.stream 定义在 DefaultChatStreamService.java，负责归一化请求、调用模型流并输出 SSE 事件。",
                events.stream().filter(event -> event.getType() == AgentEventType.ANSWER).findFirst().get().getAnswer());
    }

    @Test
    public void shouldStopAfterRepeatedParseErrors() {
        AgentRuntimeProperties properties = properties();
        properties.setParseErrorMaxAttempts(1);
        DefaultAgentLoopService service = new DefaultAgentLoopService(
                completeGateway("not json", "still not json"),
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

    private DefaultAgentLoopService newService(ModelGateway modelGateway, List<AgentTool> tools) {
        return new DefaultAgentLoopService(
                modelGateway,
                new ToolRegistry(tools),
                properties(),
                new ObjectMapper(),
                Runnable::run);
    }

    private AgentRuntimeProperties properties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setStepTimeoutMs(1000L);
        properties.setTotalTimeoutMs(3000L);
        properties.setToolTimeoutMs(1000L);
        properties.setObservationMaxChars(8000);
        properties.setMaxSteps(6);
        return properties;
    }

    private ModelGateway completeGateway(String... outputs) {
        AtomicInteger index = new AtomicInteger();
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
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

}
