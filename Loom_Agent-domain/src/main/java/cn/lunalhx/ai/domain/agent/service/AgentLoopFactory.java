package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Agent Loop 统一装配入口（Phase 3 §七）。
 *
 * <p>生产代码只能通过本 Factory 创建 Agent Loop；所有依赖必须显式传入。
 * 内部持有 {@link AgentFlowFactory} 创建节点图。
 *
 * <p>不提供含糊的 {@code create(..., nullableCoordinator)} 方法：语义由方法名区分。
 */
public class AgentLoopFactory {

    private final AgentFlowFactory flowFactory;
    private final AgentLoopStateDependencies state;
    private final AgentLoopRuntimeDependencies runtime;

    public AgentLoopFactory(ModelGateway modelGateway,
                           AgentLoopStateDependencies state,
                           AgentLoopRuntimeDependencies runtime) {
        Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.flowFactory = new AgentFlowFactory(modelGateway, state, runtime);
    }

    /**
     * 创建独立 Agent Loop（无子 Agent 节点），使用调用方提供的 Executor。
     */
    public DefaultAgentLoopService createStandalone(ToolRegistry toolRegistry, Executor executor) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        return new DefaultAgentLoopService(assembleStandalone(toolRegistry), executor);
    }

    /**
     * 创建根 Agent Loop（启用子 Agent 节点），所有参数必须非空。
     */
    public DefaultAgentLoopService createRoot(ToolRegistry toolRegistry,
                                              Executor executor,
                                              SubAgentCoordinator subAgentCoordinator) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(subAgentCoordinator, "subAgentCoordinator must not be null");
        return new DefaultAgentLoopService(assembleRoot(toolRegistry, subAgentCoordinator), executor);
    }

    /**
     * 创建子 Agent Loop（无子 Agent 节点），固定使用同步 Executor（{@code Runnable::run}）。
     *
     * <p>外层 CompletableFuture 负责并发，子 Agent 内部 Loop 不再额外切换线程。
     */
    public DefaultAgentLoopService createChild(ToolRegistry toolRegistry) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        return new DefaultAgentLoopService(assembleStandalone(toolRegistry), Runnable::run);
    }

    // ==================== Phase 3 装配方法 ====================

    AgentLoopAssembly assembleStandalone(ToolRegistry toolRegistry) {
        AgentFlowDefinition flow = flowFactory.createStandalone(toolRegistry);
        AgentLoopComponents components = buildComponents(flow);
        return new AgentLoopAssembly(runtime.properties(), flow, components);
    }

    AgentLoopAssembly assembleRoot(ToolRegistry toolRegistry, SubAgentCoordinator coordinator) {
        AgentFlowDefinition flow = flowFactory.createRoot(toolRegistry, coordinator);
        AgentLoopComponents components = buildComponents(flow);
        return new AgentLoopAssembly(runtime.properties(), flow, components);
    }

    private AgentLoopComponents buildComponents(AgentFlowDefinition flow) {
        AgentEventFactory eventFactory = new AgentEventFactory();
        AgentContextFactory contextFactory = new AgentContextFactory(
                runtime.properties(), state.workspaceResolver(), flow.toolSpecs(), flow.subAgentAvailable());
        AgentResumeCoordinator resumeCoordinator = new AgentResumeCoordinator(
                state.approvalStore(), state.checkpointRepository(), contextFactory, eventFactory);
        AgentNodeLifecycle nodeLifecycle = new AgentNodeLifecycle(
                runtime.traceRecorder(), runtime.agentMetrics(), flow.hookRegistry(), eventFactory);
        return new AgentLoopComponents(contextFactory, resumeCoordinator, nodeLifecycle, eventFactory, state.runRepository(), state.checkpointRepository());
    }
}
