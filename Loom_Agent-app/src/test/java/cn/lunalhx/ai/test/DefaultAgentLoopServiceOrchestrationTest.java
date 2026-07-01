package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.agent.service.execution.DefaultAgentLoopService;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Phase 3 orchestration smoke tests.
 *
 * <p>Verify the refactored Service's entry points (ask/resume/resumeRun/resumeWithUserInput)
 * still produce stable externally observable behavior.
 */
public class DefaultAgentLoopServiceOrchestrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    public void normalAskShouldEmitAnswerAndDoneAndComplete() {
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway("{\"type\":\"final\",\"answer\":\"ok\",\"evidence\":[]}"))
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
                .modelGateway(completeGateway("{\"type\":\"final\",\"answer\":\"ok\",\"evidence\":[]}"))
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
    public void resumeRunWithMissingCheckpointShouldReturnStableError() {
        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway("{\"type\":\"final\",\"answer\":\"x\",\"evidence\":[]}"))
                .buildAgentLoop();

        List<AgentEvent> events = service.resumeRun("nonexistent-run")
                .collectList().block(TIMEOUT);

        assertEquals(1, events.size());
        assertEquals(AgentEventType.ERROR, events.get(0).getType());
        assertEquals("checkpoint_not_found", events.get(0).getCode());
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

        // ModelCallNode catches gateway exceptions internally and emits a specific error;
        // the Service's executeAsync wrapper ensures Flux always completes.
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.ERROR));
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.DONE));
    }

    @Test
    public void terminalResumePlanShouldNotEnterRunLoop() {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        AgentContext context = new AgentContext();
        context.setRunId("waiting-run");
        context.setRootRunId("waiting-run");
        context.setRequestId("req");
        context.setConversationId("conv");
        context.setQuestion("waiting");
        context.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        context.setWorkspaceDisplayName(".");
        context.setMaxSteps(6);
        context.setStartedAt(Instant.now());
        context.setCurrentNode(AgentNodeNames.USER_INPUT_GATE);
        context.setContextRecoveryStage(cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage.WAITING_USER_INPUT);
        context.setToolSpecs(List.of());
        checkpointRepository.save(AgentCheckpoint.builder()
                .runId("waiting-run")
                .currentNode(AgentNodeNames.USER_INPUT_GATE)
                .contextSnapshot(AgentContextSnapshot.from(context))
                .reason("after_node:user_input_gate")
                .build());

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway("{}"))
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository)
                .buildAgentLoop();

        List<AgentEvent> events = service.resumeRun("waiting-run")
                .collectList().block(TIMEOUT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.RESUME_STARTED));
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.USER_INPUT_REQUIRED));
        assertFalse(events.stream().anyMatch(e -> e.getType() == AgentEventType.DONE));
    }

    @Test
    public void continueResumePlanShouldExecuteFromSpecifiedNode() {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();
        AgentContext context = new AgentContext();
        context.setRunId("continue-run");
        context.setRootRunId("continue-run");
        context.setRequestId("req");
        context.setConversationId("conv");
        context.setQuestion("continue me");
        context.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        context.setWorkspaceDisplayName(".");
        context.setMaxSteps(6);
        context.setStartedAt(Instant.now());
        context.setCurrentNode(AgentNodeNames.USER_INPUT_GATE);
        context.setContextRecoveryStage(cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage.WAITING_USER_INPUT);
        context.setStep(1);
        context.setToolSpecs(List.of());
        checkpointRepository.save(AgentCheckpoint.builder()
                .runId("continue-run")
                .currentNode(AgentNodeNames.USER_INPUT_GATE)
                .contextSnapshot(AgentContextSnapshot.from(context))
                .reason("after_node:user_input_gate")
                .build());

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway("{\"type\":\"final\",\"answer\":\"resumed ok\",\"evidence\":[]}"))
                .tools(List.of(fakeTool("code_search", "found")))
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository)
                .buildAgentLoop();

        List<AgentEvent> events = service.resumeWithUserInput(
                        "continue-run", UserInputAction.CONTINUE, "keep going")
                .collectList().block(TIMEOUT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.RESUME_STARTED));
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.ANSWER
                && "resumed ok".equals(e.getAnswer())));
        assertTrue(events.stream().anyMatch(e -> e.getType() == AgentEventType.DONE));
    }

    @Test
    public void resumeRunWithCompletedStatusShouldReturnRunAlreadyTerminal() {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();

        runRepository.save(AgentRun.builder()
                .runId("completed-run")
                .requestId("req-1")
                .conversationId("conv-1")
                .workspace(".")
                .status(AgentRunStatus.COMPLETED)
                .build());

        AgentContext context = new AgentContext();
        context.setRunId("completed-run");
        context.setRequestId("req-1");
        context.setConversationId("conv-1");
        context.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        context.setWorkspaceDisplayName(".");
        context.setMaxSteps(6);
        context.setStartedAt(Instant.now());
        context.setStep(3);
        context.setToolSpecs(List.of());
        checkpointRepository.save(AgentCheckpoint.builder()
                .runId("completed-run")
                .currentNode(AgentNodeNames.FINAL_ANSWER)
                .contextSnapshot(AgentContextSnapshot.from(context))
                .reason("after_node:final_answer")
                .build());

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway("{}"))
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository)
                .buildAgentLoop();

        List<AgentEvent> events = service.resumeRun("completed-run")
                .collectList().block(TIMEOUT);

        assertEquals(1, events.size());
        assertEquals(AgentEventType.ERROR, events.get(0).getType());
        assertEquals("run_already_terminal", events.get(0).getCode());
        assertFalse(events.stream().anyMatch(e -> e.getType() == AgentEventType.RESUME_STARTED));
        assertFalse(events.stream().anyMatch(e -> e.getType() == AgentEventType.ANSWER));
        assertFalse(events.stream().anyMatch(e -> e.getType() == AgentEventType.DONE));
    }

    @Test
    public void resumeRunWithFailedStatusShouldReturnRunAlreadyTerminal() {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();

        runRepository.save(AgentRun.builder()
                .runId("failed-run")
                .requestId("req-2")
                .conversationId("conv-2")
                .workspace(".")
                .status(AgentRunStatus.FAILED)
                .build());

        AgentContext context = new AgentContext();
        context.setRunId("failed-run");
        context.setRequestId("req-2");
        context.setConversationId("conv-2");
        context.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        context.setWorkspaceDisplayName(".");
        context.setMaxSteps(6);
        context.setStartedAt(Instant.now());
        context.setStep(1);
        context.setToolSpecs(List.of());
        checkpointRepository.save(AgentCheckpoint.builder()
                .runId("failed-run")
                .currentNode(AgentNodeNames.FAIL)
                .contextSnapshot(AgentContextSnapshot.from(context))
                .reason("after_node:fail")
                .build());

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway("{}"))
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository)
                .buildAgentLoop();

        List<AgentEvent> events = service.resumeRun("failed-run")
                .collectList().block(TIMEOUT);

        assertEquals(1, events.size());
        assertEquals(AgentEventType.ERROR, events.get(0).getType());
        assertEquals("run_already_terminal", events.get(0).getCode());
    }

    @Test
    public void resumeRunWithBudgetExceededStatusShouldReturnRunAlreadyTerminal() {
        InMemoryAgentRunRepository runRepository = new InMemoryAgentRunRepository();
        InMemoryAgentCheckpointRepository checkpointRepository = new InMemoryAgentCheckpointRepository();

        runRepository.save(AgentRun.builder()
                .runId("budget-run")
                .requestId("req-3")
                .conversationId("conv-3")
                .workspace(".")
                .status(AgentRunStatus.BUDGET_EXCEEDED)
                .build());

        AgentContext context = new AgentContext();
        context.setRunId("budget-run");
        context.setRequestId("req-3");
        context.setConversationId("conv-3");
        context.setResolvedWorkspace(Path.of(".").toAbsolutePath().normalize());
        context.setWorkspaceDisplayName(".");
        context.setMaxSteps(6);
        context.setStartedAt(Instant.now());
        context.setStep(2);
        context.setToolSpecs(List.of());
        checkpointRepository.save(AgentCheckpoint.builder()
                .runId("budget-run")
                .currentNode(AgentNodeNames.RENDER_PROMPT)
                .contextSnapshot(AgentContextSnapshot.from(context))
                .reason("after_node:render_prompt")
                .build());

        DefaultAgentLoopService service = AgentRuntimeTestFixture.fixture()
                .modelGateway(completeGateway("{}"))
                .runRepository(runRepository)
                .checkpointRepository(checkpointRepository)
                .buildAgentLoop();

        List<AgentEvent> events = service.resumeRun("budget-run")
                .collectList().block(TIMEOUT);

        assertEquals(1, events.size());
        assertEquals(AgentEventType.ERROR, events.get(0).getType());
        assertEquals("run_already_terminal", events.get(0).getCode());
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
