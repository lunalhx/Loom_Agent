package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.config.DelegateService;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.agent.model.entity.ToolExecutionMarker;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ConversationEntryType;
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
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.infrastructure.loom.DelegateTool;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileAttemptLeaseRepository;
import cn.lunalhx.ai.infrastructure.store.FileConversationHistoryRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import cn.lunalhx.ai.test.AgentRuntimeTestFixture;
import cn.lunalhx.ai.test.CliLoopTestFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Ticket 12 seam: the root Run is the only recovery unit. Durable Delegate
 * Results stay in History; a child without a parent-visible result becomes
 * Interrupted Delegate Call and {@code INTERRUPTED_WITH_PARENT}.
 */
public class CliDelegateRecoveryE2ETest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    public void inFlightDelegateTerminalizesChildWithoutNestedRecovery() throws Exception {
        Path workspace = Files.createTempDirectory("delegate-interrupt");
        CountingTool delegate = CountingTool.of(new DelegateTool(null));
        delegate.crashAfterAdapter.set(true);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService first = service(workspace,
                delegateThenChildFinalGateway("child done", "should-not-finish", modelCalls),
                delegate);
        String sessionId;
        String rootRunId;
        String childRunId;
        try {
            first.runTurn("investigate via delegate");
            sessionId = first.sessionId();
            rootRunId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
            List<AgentRun> children = first.runRepository().findChildren(rootRunId);
            assertEquals(1, children.size());
            childRunId = children.getFirst().getRunId();
        } finally {
            first.close();
        }
        assertEquals(1, delegate.invocations());
        ConversationHistoryDocument before = historyRepository(workspace).find(sessionId).orElseThrow();
        assertTrue(before.getEntries().stream()
                .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_CALL
                        && "delegate".equals(entry.toolName())));
        assertFalse(before.getEntries().stream()
                .anyMatch(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT
                        && "delegate".equals(entry.toolName())));

        String inflightChildId = "delegate_inflight";
        new FileAgentRunRepository(workspace, mapper).save(AgentRun.builder()
                .runId(inflightChildId)
                .parentRunId(rootRunId)
                .rootRunId(rootRunId)
                .sessionId(sessionId)
                .conversationId(sessionId)
                .runKind(AgentRunKind.CHILD)
                .status(AgentRunStatus.RUNNING)
                .createdAt(java.time.Instant.now())
                .build());

        delegate.crashAfterAdapter.set(false);
        AtomicInteger recoveredCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalAnswerGateway("must-not-run", recoveredCalls),
                CountingTool.of(new DelegateTool(null)));
        try {
            assertTrue(resumed.recoveryRequired());
            AgentRun blocked = resumed.recoveryRequiredRun().orElseThrow();
            assertEquals(rootRunId, blocked.getRunId());
            assertEquals(AgentRunKind.ROOT, blocked.getRunKind());

            String shown = resumed.recover();
            assertTrue("shown=" + shown, shown.contains("Ambiguity Review"));
            assertTrue("shown=" + shown, shown.contains("Interrupted Delegate Call"));
            assertEquals(0, recoveredCalls.get());
            assertTrue(resumed.ambiguityReview());
            assertFalse(resumed.recoveryRequired());

            AgentRun child = resumed.runRepository().find(childRunId).orElseThrow();
            assertEquals(AgentRunStatus.COMPLETED, child.getStatus());
            assertFalse(new FileAttemptLeaseRepository(workspace, mapper).isHealthy(childRunId));
            AgentRun inflight = resumed.runRepository().find(inflightChildId).orElseThrow();
            assertEquals(AgentRunStatus.INTERRUPTED_WITH_PARENT, inflight.getStatus());
            assertEquals("INTERRUPTED_WITH_PARENT", inflight.getStopReason());
            assertTrue(resumed.recoveryRequiredRun()
                    .filter(run -> childRunId.equals(run.getRunId())
                            || inflightChildId.equals(run.getRunId()))
                    .isEmpty());

            AgentCheckpoint latest = new FileAgentCheckpointRepository(workspace, mapper)
                    .latest(rootRunId).orElseThrow();
            List<ToolExecutionMarker> interrupted = latest.getContextSnapshot().getInterruptedToolCalls();
            assertNotNull(interrupted);
            assertEquals(1, interrupted.size());
            assertEquals("delegate", interrupted.getFirst().getToolName());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void durableDelegateResultRemainsVisibleWhenLaterToolInterrupts() throws Exception {
        Path workspace = Files.createTempDirectory("delegate-durable");
        CountingTool delegate = CountingTool.of(new DelegateTool(null));
        CountingTool write = CountingTool.of(new cn.lunalhx.ai.infrastructure.loom.WriteFileTool(
                new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort()));
        write.crashBeforeAdapter.set(true);
        AtomicInteger modelCalls = new AtomicInteger();
        CliSessionService first = service(workspace,
                delegateThenWriteGateway(modelCalls),
                delegate, write);
        String sessionId;
        String rootRunId;
        String childRunId;
        try {
            first.runTurn("delegate then write");
            sessionId = first.sessionId();
            rootRunId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
            childRunId = first.runRepository().findChildren(rootRunId).getFirst().getRunId();
        } finally {
            first.close();
        }
        assertEquals(1, delegate.invocations());
        assertEquals(0, write.invocations());
        ConversationHistoryDocument before = historyRepository(workspace).find(sessionId).orElseThrow();
        assertEquals(1, before.getEntries().stream()
                .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT
                        && "delegate".equals(entry.toolName()))
                .count());
        assertEquals(AgentRunStatus.COMPLETED,
                new FileAgentRunRepository(workspace, mapper).find(childRunId).orElseThrow().getStatus());

        write.crashBeforeAdapter.set(false);
        AtomicInteger recoveredCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                countingFinalAnswerGateway("must-not-run", recoveredCalls),
                CountingTool.of(new DelegateTool(null)), write);
        try {
            String shown = resumed.recover();
            assertTrue(shown.contains("Ambiguity Review"));
            assertTrue(shown.contains("Interrupted Tool Call write_file"));
            assertFalse(shown.contains("Interrupted Delegate Call"));
            assertEquals(0, recoveredCalls.get());

            ConversationHistoryDocument after = historyRepository(workspace).find(sessionId).orElseThrow();
            assertEquals(1, after.getEntries().stream()
                    .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT
                            && "delegate".equals(entry.toolName()))
                    .count());
            AgentRun child = resumed.runRepository().find(childRunId).orElseThrow();
            assertEquals(AgentRunStatus.COMPLETED, child.getStatus());
        } finally {
            resumed.close();
        }
    }

    @Test
    public void recoveredRootCreatesNewDelegateThroughOrdinaryPermissions() throws Exception {
        Path workspace = Files.createTempDirectory("delegate-replan");
        CountingTool firstDelegate = CountingTool.of(new DelegateTool(null));
        firstDelegate.crashAfterAdapter.set(true);
        CountingTool write = CountingTool.of(new cn.lunalhx.ai.infrastructure.loom.WriteFileTool(
                new cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort()));
        AtomicInteger firstCalls = new AtomicInteger();
        CliSessionService first = service(workspace,
                delegateThenChildFinalGateway("child done", "should-not-finish", firstCalls),
                firstDelegate, write);
        String sessionId;
        String rootRunId;
        String interruptedChildId;
        try {
            first.runTurn("investigate via delegate");
            sessionId = first.sessionId();
            rootRunId = first.runRepository().findLatestRootByConversationId(sessionId)
                    .orElseThrow().getRunId();
            interruptedChildId = first.runRepository().findChildren(rootRunId).getFirst().getRunId();
        } finally {
            first.close();
        }

        CountingTool recoveredDelegate = CountingTool.of(new DelegateTool(null));
        write.crashBeforeAdapter.set(true);
        AtomicInteger recoveredCalls = new AtomicInteger();
        CliSessionService resumed = resume(workspace, sessionId,
                newDelegateThenWriteGateway(recoveredCalls),
                recoveredDelegate, write);
        try {
            String shown = resumed.recover();
            assertTrue("shown=" + shown, shown.contains("Interrupted Delegate Call"));
            resumed.continueWithAmbiguity();
            assertEquals(1, recoveredDelegate.invocations());
            String later = resumed.recover();
            assertTrue(later.contains("Interrupted Tool Call write_file"));
            assertFalse(later.contains("Interrupted Delegate Call"));

            List<AgentRun> children = resumed.runRepository().findChildren(rootRunId);
            assertEquals(2, children.size());
            AgentRun interrupted = resumed.runRepository().find(interruptedChildId).orElseThrow();
            assertEquals(AgentRunStatus.COMPLETED, interrupted.getStatus());
            AgentRun created = children.stream()
                    .filter(run -> !interruptedChildId.equals(run.getRunId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(AgentRunStatus.COMPLETED, created.getStatus());

            ConversationHistoryDocument history = historyRepository(workspace).find(sessionId).orElseThrow();
            assertEquals(1, history.getEntries().stream()
                    .filter(entry -> entry.stableType() == ConversationEntryType.TOOL_RESULT
                            && "delegate".equals(entry.toolName()))
                    .count());
        } finally {
            resumed.close();
        }
    }

    private CliSessionService service(Path workspace, ModelGateway gateway, CountingTool delegate,
                                      AgentTool... extras) {
        return service(workspace, options(workspace, gateway), gateway, delegate, extras);
    }

    private CliSessionService resume(Path workspace, String sessionId, ModelGateway gateway,
                                     CountingTool delegate, AgentTool... extras) {
        CliSessionService.CliOptions opts = options(workspace, gateway);
        opts.resumeSessionId = sessionId;
        return service(workspace, opts, gateway, delegate, extras);
    }

    private CliSessionService service(Path workspace, CliSessionService.CliOptions opts,
                                      ModelGateway gateway, CountingTool delegate,
                                      AgentTool... extras) {
        AgentRuntimeProperties agent = CliLoopTestFixture.agentProperties(workspace);
        agent.setApprovalPolicy(opts.approvalPolicy);
        Path root = workspace.toAbsolutePath().normalize();
        FileAgentRunRepository runs = new FileAgentRunRepository(root, mapper);
        FileAgentSessionRepository sessions = new FileAgentSessionRepository(root, mapper);
        FileAgentCheckpointRepository checkpoints = new FileAgentCheckpointRepository(root, mapper);
        FileTraceRecorder traces = new FileTraceRecorder(root, mapper);
        FileConversationHistoryRepository histories = historyRepository(workspace);

        AgentLoopStateDependencies state = new AgentLoopStateDependencies(
                new cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver(agent),
                runs, checkpoints, histories,
                new FileAttemptLeaseRepository(root, mapper),
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
        delegate.replaceDelegate(new DelegateTool(delegateService));
        ArrayList<AgentTool> tools = new ArrayList<>();
        tools.add(delegate.delegate());
        if (extras != null) {
            tools.addAll(List.of(extras));
        }
        ToolRegistry registry = new ToolRegistry(tools, new ToolSchemaValidator(mapper));
        registryRef.set(registry);
        AgentLoopService loop = factory.createStandalone(registry, Runnable::run);
        return new CliSessionService(opts, mapper, agent, new ModelRuntimeProperties(),
                sessions, runs, checkpoints, histories, traces, loop);
    }

    private FileConversationHistoryRepository historyRepository(Path workspace) {
        return CliLoopTestFixture.historyRepository(workspace, mapper);
    }

    private CliSessionService.CliOptions options(Path workspace, ModelGateway gateway) {
        CliSessionService.CliOptions o = new CliSessionService.CliOptions();
        o.provider = "deepseek";
        o.model = "deepseek-v4-flash";
        o.baseUrl = "http://unused";
        o.apiKey = "";
        o.workspaceRoot = workspace.toString();
        o.approvalPolicy = "auto";
        o.maxSteps = 6;
        o.maxNewTokens = 512;
        o.temperature = 0.2;
        o.topP = 0.9;
        o.timeoutSeconds = 30;
        o.modelGateway = gateway;
        return o;
    }

    private ModelGateway delegateThenChildFinalGateway(String childAnswer, String parentAnswer,
                                                       AtomicInteger modelCalls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int call = modelCalls.getAndIncrement();
                String content = call == 0
                        ? "<tool>{\"name\":\"delegate\",\"args\":{\"task\":\"look around\",\"max_steps\":1}}</tool>"
                        : call == 1
                        ? "<final>" + childAnswer + "</final>"
                        : "<final>" + parentAnswer + "</final>";
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway delegateThenWriteGateway(AtomicInteger modelCalls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int call = modelCalls.getAndIncrement();
                String content = switch (call) {
                    case 0 -> "<tool>{\"name\":\"delegate\",\"args\":{\"task\":\"look around\",\"max_steps\":1}}</tool>";
                    case 1 -> "<final>child done</final>";
                    default -> "<tool>{\"name\":\"write_file\",\"args\":{\"path\":\"notes.txt\",\"content\":\"hello-write\"}}</tool>";
                };
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway newDelegateThenWriteGateway(AtomicInteger modelCalls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                int call = modelCalls.getAndIncrement();
                String content = switch (call) {
                    case 0 -> "<tool>{\"name\":\"delegate\",\"args\":{\"task\":\"replan investigation\",\"max_steps\":1}}</tool>";
                    case 1 -> "<final>new child done</final>";
                    default -> "<tool>{\"name\":\"write_file\",\"args\":{\"path\":\"notes.txt\",\"content\":\"hello-write\"}}</tool>";
                };
                return Mono.just(ModelChatResult.builder()
                        .content(content)
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private ModelGateway countingFinalAnswerGateway(String answer, AtomicInteger modelCalls) {
        return new ModelGateway() {
            @Override
            public Flux<ModelStreamChunk> stream(ChatPrompt prompt) {
                return Flux.empty();
            }

            @Override
            public Mono<ModelChatResult> complete(ChatPrompt prompt) {
                modelCalls.incrementAndGet();
                return Mono.just(ModelChatResult.builder()
                        .content("<final>" + answer + "</final>")
                        .finishReason("stop")
                        .actualModel("deepseek-v4-flash")
                        .build());
            }
        };
    }

    private static final class CountingTool implements AgentTool {
        private AgentTool delegate;
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicBoolean crashBeforeAdapter = new AtomicBoolean();
        private final AtomicBoolean crashAfterAdapter = new AtomicBoolean();

        private CountingTool(AgentTool delegate) {
            this.delegate = delegate;
        }

        static CountingTool of(AgentTool delegate) {
            return new CountingTool(delegate);
        }

        void replaceDelegate(AgentTool next) {
            this.delegate = next;
        }

        int invocations() {
            return invocations.get();
        }

        AgentTool delegate() {
            return this;
        }

        @Override
        public cn.lunalhx.ai.domain.tool.model.ToolSpec spec() {
            return delegate.spec();
        }

        @Override
        public ToolResult call(ToolCall call) {
            if (crashBeforeAdapter.get()) {
                throw new SimulatedProcessCrash("before adapter");
            }
            ToolResult result = delegate.call(call);
            invocations.incrementAndGet();
            if (crashAfterAdapter.get()) {
                throw new SimulatedProcessCrash("after adapter");
            }
            return result;
        }
    }

    private static final class SimulatedProcessCrash extends Error {
        SimulatedProcessCrash(String message) {
            super(message);
        }
    }
}
