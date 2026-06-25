package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.flow.hook.CheckpointAgentHook;
import cn.lunalhx.ai.domain.agent.flow.node.ApprovalGateNode;
import cn.lunalhx.ai.domain.agent.flow.node.DecisionNode;
import cn.lunalhx.ai.domain.agent.flow.node.FailNode;
import cn.lunalhx.ai.domain.agent.flow.node.FinalAnswerNode;
import cn.lunalhx.ai.domain.agent.flow.node.ModelCallNode;
import cn.lunalhx.ai.domain.agent.flow.node.ObservationNode;
import cn.lunalhx.ai.domain.agent.flow.node.PlannerNode;
import cn.lunalhx.ai.domain.agent.flow.node.RenderPromptNode;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanGuardNode;
import cn.lunalhx.ai.domain.agent.flow.node.ReplanNode;
import cn.lunalhx.ai.domain.agent.flow.node.StartNode;
import cn.lunalhx.ai.domain.agent.flow.node.SubAgentDispatchNode;
import cn.lunalhx.ai.domain.agent.flow.node.ToolDispatchNode;
import cn.lunalhx.ai.domain.agent.flow.node.UserInputGateNode;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.AgentQuestion;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.ApprovalDecision;
import cn.lunalhx.ai.domain.agent.model.valobj.UserInputAction;
import cn.lunalhx.ai.domain.agent.model.valobj.WorkspaceResolutionException;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

@Slf4j
public class DefaultAgentLoopService implements AgentLoopService {

    private final AgentRuntimeProperties properties;
    private final Map<String, AgentNode> nodes;
    private final AgentLoopComponents components;
    private final Executor executor;

    // ==================== Phase 3 生产构造器 ====================

    DefaultAgentLoopService(AgentLoopAssembly assembly, Executor executor) {
        this.properties = assembly.properties();
        this.nodes = assembly.flow().nodes();
        this.components = assembly.components();
        this.executor = executor;
    }

    // ==================== Phase 2 兼容构造器（委托给新构造器） ====================

    DefaultAgentLoopService(AgentLoopStateDependencies state,
                            AgentLoopRuntimeDependencies runtime,
                            AgentFlowDefinition flow,
                            SubAgentCoordinator subAgentCoordinator,
                            Executor executor) {
        this.properties = runtime.properties();
        this.nodes = flow.nodes();
        this.components = buildComponents(state, runtime, flow);
        this.executor = executor;
    }

    private static AgentLoopComponents buildComponents(AgentLoopStateDependencies state,
                                                        AgentLoopRuntimeDependencies runtime,
                                                        AgentFlowDefinition flow) {
        AgentEventFactory eventFactory = new AgentEventFactory();
        AgentContextFactory contextFactory = new AgentContextFactory(
                runtime.properties(), state.workspaceResolver(), flow.toolSpecs(), flow.subAgentAvailable());
        AgentResumeCoordinator resumeCoordinator = new AgentResumeCoordinator(
                state.approvalStore(), state.checkpointRepository(), contextFactory, eventFactory);
        AgentNodeLifecycle nodeLifecycle = new AgentNodeLifecycle(
                runtime.traceRecorder(), runtime.agentMetrics(), flow.hookRegistry(), eventFactory);
        return new AgentLoopComponents(contextFactory, resumeCoordinator, nodeLifecycle, eventFactory);
    }

    // ==================== 旧构造器（Phase 2 兼容层，标记废弃，最终阶段删除） ====================

    /**
     * @deprecated Use {@link AgentLoopFactory#createStandalone}.
     */
    @Deprecated(forRemoval = true)
    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this(modelGateway, toolRegistry, new InMemoryApprovalStore(), new AgentWorkspaceResolver(properties),
                new InMemoryAgentRunRepository(), new InMemoryAgentCheckpointRepository(), properties, objectMapper, executor);
    }

    /**
     * @deprecated Use {@link AgentLoopFactory}.
     */
    @Deprecated(forRemoval = true)
    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this(modelGateway, toolRegistry, approvalStore, new AgentWorkspaceResolver(properties),
                new InMemoryAgentRunRepository(), new InMemoryAgentCheckpointRepository(), properties, objectMapper, executor);
    }

    /**
     * @deprecated Use {@link AgentLoopFactory}.
     */
    @Deprecated(forRemoval = true)
    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this(modelGateway, toolRegistry, approvalStore, workspaceResolver,
                new InMemoryAgentRunRepository(), new InMemoryAgentCheckpointRepository(), properties, objectMapper, executor);
    }

    /**
     * @deprecated Use {@link AgentLoopFactory}.
     */
    @Deprecated(forRemoval = true)
    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRunRepository runRepository,
                                   AgentCheckpointRepository checkpointRepository,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor) {
        this(modelGateway, toolRegistry, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, null);
    }

    /**
     * @deprecated Use {@link AgentLoopFactory}.
     */
    @Deprecated(forRemoval = true)
    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRunRepository runRepository,
                                   AgentCheckpointRepository checkpointRepository,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor,
                                   SubAgentCoordinator subAgentCoordinator) {
        this(modelGateway, toolRegistry, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, subAgentCoordinator,
                new InMemoryTraceRecorder(), new DefaultBudgetGuard(properties), new NoopAgentMetrics());
    }

    /**
     * @deprecated Use {@link AgentLoopFactory}.
     */
    @Deprecated(forRemoval = true)
    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRunRepository runRepository,
                                   AgentCheckpointRepository checkpointRepository,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor,
                                   SubAgentCoordinator subAgentCoordinator,
                                   TraceRecorder traceRecorder,
                                   BudgetGuard budgetGuard) {
        this(modelGateway, toolRegistry, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, subAgentCoordinator, traceRecorder, budgetGuard, new NoopAgentMetrics());
    }

    /**
     * @deprecated Use {@link AgentLoopFactory}.
     */
    @Deprecated(forRemoval = true)
    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRunRepository runRepository,
                                   AgentCheckpointRepository checkpointRepository,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor,
                                   SubAgentCoordinator subAgentCoordinator,
                                   TraceRecorder traceRecorder,
                                   BudgetGuard budgetGuard,
                                   AgentMetrics agentMetrics) {
        this(modelGateway, toolRegistry, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, subAgentCoordinator, traceRecorder, budgetGuard, agentMetrics,
                ContextWindowManager.noop(properties));
    }

    /**
     * @deprecated Use {@link AgentLoopFactory}.
     */
    @Deprecated(forRemoval = true)
    public DefaultAgentLoopService(ModelGateway modelGateway,
                                   ToolRegistry toolRegistry,
                                   ApprovalStore approvalStore,
                                   AgentWorkspaceResolver workspaceResolver,
                                   AgentRunRepository runRepository,
                                   AgentCheckpointRepository checkpointRepository,
                                   AgentRuntimeProperties properties,
                                   ObjectMapper objectMapper,
                                   Executor executor,
                                   SubAgentCoordinator subAgentCoordinator,
                                   TraceRecorder traceRecorder,
                                   BudgetGuard budgetGuard,
                                   AgentMetrics agentMetrics,
                                   ContextWindowManager contextWindowManager) {
        this(legacyAssembly(modelGateway, toolRegistry, approvalStore, workspaceResolver, runRepository,
                checkpointRepository, properties, objectMapper, subAgentCoordinator,
                traceRecorder, budgetGuard, agentMetrics, contextWindowManager), executor);
    }

    private static AgentLoopAssembly legacyAssembly(ModelGateway modelGateway,
                                                     ToolRegistry toolRegistry,
                                                     ApprovalStore approvalStore,
                                                     AgentWorkspaceResolver workspaceResolver,
                                                     AgentRunRepository runRepository,
                                                     AgentCheckpointRepository checkpointRepository,
                                                     AgentRuntimeProperties properties,
                                                     ObjectMapper objectMapper,
                                                     SubAgentCoordinator subAgentCoordinator,
                                                     TraceRecorder traceRecorder,
                                                     BudgetGuard budgetGuard,
                                                     AgentMetrics agentMetrics,
                                                     ContextWindowManager contextWindowManager) {
        TraceRecorder tr = traceRecorder == null ? new InMemoryTraceRecorder() : traceRecorder;
        BudgetGuard bg = budgetGuard == null ? new DefaultBudgetGuard(properties) : budgetGuard;
        AgentMetrics am = agentMetrics == null ? new NoopAgentMetrics() : agentMetrics;
        ContextWindowManager cwm = contextWindowManager == null ? ContextWindowManager.noop(properties) : contextWindowManager;

        AgentLoopStateDependencies state = new AgentLoopStateDependencies(
                approvalStore, workspaceResolver, runRepository, checkpointRepository, objectMapper);
        AgentLoopRuntimeDependencies runtime = new AgentLoopRuntimeDependencies(properties, tr, bg, am, cwm);

        boolean subAgentAvailable = subAgentCoordinator != null;
        List<AgentNode> nodeList = new ArrayList<>(List.of(
                new StartNode(),
                new PlannerNode(),
                new RenderPromptNode(cwm),
                new ModelCallNode(modelGateway, properties, tr, bg, cwm),
                new DecisionNode(objectMapper, toolRegistry, properties),
                new ApprovalGateNode(toolRegistry, approvalStore, properties),
                new ToolDispatchNode(toolRegistry, properties,
                        new AgentHookRegistry(List.of(new CheckpointAgentHook(runRepository, checkpointRepository, objectMapper))),
                        cwm),
                new ObservationNode(),
                new ReplanGuardNode(),
                new ReplanNode(modelGateway, properties, objectMapper, tr, bg),
                new FinalAnswerNode(),
                new UserInputGateNode(),
                new FailNode()));
        if (subAgentAvailable) {
            nodeList.add(new SubAgentDispatchNode(subAgentCoordinator, properties));
        }
        Map<String, AgentNode> nodes = new LinkedHashMap<>();
        for (AgentNode node : nodeList) {
            if (nodes.containsKey(node.name())) {
                throw new IllegalStateException("重复的 Agent 节点：" + node.name());
            }
            nodes.put(node.name(), node);
        }

        AgentHookRegistry hookRegistry = new AgentHookRegistry(
                List.of(new CheckpointAgentHook(runRepository, checkpointRepository, objectMapper)));
        AgentFlowDefinition flow = new AgentFlowDefinition(nodes, hookRegistry, toolRegistry.specs(), subAgentAvailable);

        AgentEventFactory eventFactory = new AgentEventFactory();
        AgentContextFactory contextFactory = new AgentContextFactory(properties, workspaceResolver, flow.toolSpecs(), subAgentAvailable);
        AgentResumeCoordinator resumeCoordinator = new AgentResumeCoordinator(approvalStore, checkpointRepository, contextFactory, eventFactory);
        AgentNodeLifecycle nodeLifecycle = new AgentNodeLifecycle(tr, am, hookRegistry, eventFactory);
        AgentLoopComponents components = new AgentLoopComponents(contextFactory, resumeCoordinator, nodeLifecycle, eventFactory);

        return new AgentLoopAssembly(properties, flow, components);
    }

    // ==================== 公共入口 ====================

    @Override
    public Flux<AgentEvent> ask(AgentQuestion question) {
        return executeAsync("ask", question == null ? null : question.getWorkspace(), sink -> {
            AgentContext context = components.contextFactory().create(question);
            components.nodeLifecycle().userPromptSubmitted(context, events -> emit(sink, events));
            runLoop(context, AgentNodeNames.START, sink);
        });
    }

    @Override
    public Flux<AgentEvent> resume(String approvalId, ApprovalDecision decision, String reason) {
        return executeAsync("resume", approvalId, sink -> {
            AgentResumePlan plan = components.resumeCoordinator().prepareApprovalResume(approvalId, decision, reason);
            continueFrom(plan, sink);
        });
    }

    @Override
    public Flux<AgentEvent> resumeRun(String runId) {
        return executeAsync("resumeRun", runId, sink -> {
            AgentResumePlan plan = components.resumeCoordinator().prepareRunResume(runId);
            continueFrom(plan, sink);
        });
    }

    @Override
    public Flux<AgentEvent> resumeWithUserInput(String runId, UserInputAction action, String message) {
        return executeAsync("resumeWithUserInput", runId, sink -> {
            AgentResumePlan plan = components.resumeCoordinator().prepareUserInputResume(runId, action, message);
            continueFrom(plan, sink);
        });
    }

    // ==================== 核心编排 ====================

    private Flux<AgentEvent> executeAsync(String operation, String reference,
                                          Consumer<FluxSink<AgentEvent>> action) {
        return Flux.create(sink -> executor.execute(() -> {
            try {
                action.accept(sink);
            } catch (WorkspaceResolutionException e) {
                emit(sink, List.of(components.eventFactory().workspaceError(e)));
                sink.complete();
            } catch (Exception e) {
                log.error("Agent loop failed before terminal event, operation={}, reference={}",
                        operation,
                        reference == null ? null : StringUtils.abbreviate(reference, 200),
                        e);
                emit(sink, List.of(components.eventFactory().agentError()));
                sink.complete();
            } finally {
                MDC.clear();
            }
        }), FluxSink.OverflowStrategy.BUFFER);
    }

    private void continueFrom(AgentResumePlan plan, FluxSink<AgentEvent> sink) {
        emit(sink, plan.initialEvents());
        if (plan.terminal()) {
            sink.complete();
            return;
        }
        runLoop(plan.context(), plan.startNode(), sink);
    }

    private void runLoop(AgentContext context, String currentNode, FluxSink<AgentEvent> sink) {
        while (!sink.isCancelled()) {
            if (isTotalTimeout(context)) {
                fail(context, AgentStopReason.TIMEOUT, "agent_timeout", "Agent 执行超时");
                currentNode = AgentNodeNames.FAIL;
            }

            AgentNode node = nodes.get(currentNode);
            context.setCurrentNode(currentNode);
            if (node == null) {
                fail(context, AgentStopReason.MODEL_ERROR, "node_not_found", "未知节点：" + currentNode);
                node = nodes.get(AgentNodeNames.FAIL);
            }

            AgentNodeExecution execution =
                    components.nodeLifecycle().execute(context, node, events -> emit(sink, events));

            if (execution.terminal()) {
                components.nodeLifecycle().stop(context, node, events -> emit(sink, events));
                sink.complete();
                return;
            }

            currentNode = execution.nextNode();
        }
    }

    // ==================== 私有辅助 ====================

    private void fail(AgentContext context, AgentStopReason reason, String code, String message) {
        context.setStopReason(reason);
        context.setErrorCode(code);
        context.setErrorMessage(message);
    }

    private boolean isTotalTimeout(AgentContext context) {
        return Duration.between(context.getStartedAt(), Instant.now()).toMillis() > properties.getTotalTimeoutMs();
    }

    private void emit(FluxSink<AgentEvent> sink, List<AgentEvent> events) {
        for (AgentEvent event : events) {
            if (!sink.isCancelled()) {
                sink.next(event);
            }
        }
    }
}
