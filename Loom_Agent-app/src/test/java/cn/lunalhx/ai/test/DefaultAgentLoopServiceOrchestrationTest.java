package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.service.execution.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.conversation.model.entity.ModelStreamChunk;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Orchestration smoke tests for the standalone AgentLoopService.
 *
 * <p>Verify the refactored Service's ask() entry point still produces stable
 * externally observable behavior without HTTP/SSE resume entry points.
 */
public class DefaultAgentLoopServiceOrchestrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    public void normalAskShouldEmitAnswerAndDoneAndComplete() {
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway("<final>ok</final>"))
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("hello")
                        .maxSteps(3)
                        .build())
                .collectList().block(TIMEOUT);

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.ANSWER));
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.DONE));
    }

    @Test
    public void askShouldGenerateRunIdWhenNotProvided() {
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway("<final>ok</final>"))
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("id check")
                        .maxSteps(3)
                        .build())
                .collectList().block(TIMEOUT);

        String runId = events.get(0).getRunId();
        assertNotNull(runId);
        assertFalse(runId.isEmpty());
        assertTrue(events.stream().allMatch(e -> runId.equals(e.getRunId())));
    }

    @Test
    public void exceptionInNodeShouldEmitErrorAndComplete() {
        AtomicInteger calls = new AtomicInteger();
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(new ModelGateway() {
                    @Override
                    public Flux<ModelStreamChunk> stream(ChatPrompt prompt) { return Flux.empty(); }
                    @Override
                    public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                        calls.incrementAndGet();
                        throw new RuntimeException("unexpected");
                    }
                })
                .buildAgentLoop();

        List<AgentEvent> events = service.ask(AgentQuestion.builder()
                        .question("error")
                        .maxSteps(3)
                        .build())
                .collectList().block(TIMEOUT);

        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.ERROR));
        assertFalse(events.stream().anyMatch(e -> e.getType() == AgentEventType.DONE));
    }

    private ModelGateway completeGateway(String output) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) { return Flux.empty(); }
            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                return Mono.just(ModelChatResult.builder().content(output).finishReason("stop").build());
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
}
