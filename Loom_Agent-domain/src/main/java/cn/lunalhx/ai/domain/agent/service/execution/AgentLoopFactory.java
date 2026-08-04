package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.hook.AgentHookRegistry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.agent.service.context.AgentContextFactory;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerBootstrapService;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Agent Loop 统一装配入口。
 */
public class AgentLoopFactory {

    private final AgentFlowFactory flowFactory;
    private final AgentLoopStateDependencies state;
    private final AgentLoopRuntimeDependencies runtime;
    private final ConversationHistoryAppendService ledgerAppendService;
    private final ContextManager contextManager;
    private final ConversationExecutionGuard executionGuard;

    public AgentLoopFactory(ModelGateway modelGateway,
                            AgentLoopStateDependencies state,
                            AgentLoopRuntimeDependencies runtime,
                            AgentHookRegistry hookRegistry,
                            ConversationHistoryAppendService ledgerAppendService,
                            ContextManager contextManager,
                            ConversationExecutionGuard executionGuard) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.ledgerAppendService = Objects.requireNonNull(
                ledgerAppendService, "ledgerAppendService must not be null");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager must not be null");
        this.executionGuard = Objects.requireNonNull(executionGuard, "executionGuard must not be null");
        LedgerBootstrapService bs = new LedgerBootstrapService(
                ledgerAppendService, new ConversationHistoryInitializer());

        this.flowFactory = new AgentFlowFactory(modelGateway, state, runtime, hookRegistry,
                ledgerAppendService, bs, contextManager);
    }

    public DefaultAgentLoopService createStandalone(ToolRegistry toolRegistry, Executor executor) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        return new DefaultAgentLoopService(assemble(toolRegistry), executor, executionGuard);
    }

    AgentLoopAssembly assemble(ToolRegistry toolRegistry) {
        AgentFlowDefinition flow = flowFactory.create(toolRegistry);
        AgentLoopComponents components = buildComponents(flow);
        return new AgentLoopAssembly(runtime.properties(), flow, components);
    }

    private AgentLoopComponents buildComponents(AgentFlowDefinition flow) {
        AgentEventFactory eventFactory = new AgentEventFactory();
        AgentContextFactory contextFactory = new AgentContextFactory(
                runtime.properties(), state.workspaceResolver(), flow.toolSpecs(),
                ledgerAppendService, runtime.runtimeConfigSource());
        AgentResumeCoordinator resumeCoordinator = new AgentResumeCoordinator(
                state.approvalStore(), state.checkpointRepository(), state.runRepository(),
                contextFactory, eventFactory, ledgerAppendService);
        AgentNodeLifecycle nodeLifecycle = new AgentNodeLifecycle(
                runtime.traceRecorder(), runtime.agentMetrics(), flow.hookRegistry(), eventFactory, flow.nodes());
        return new AgentLoopComponents(contextFactory, resumeCoordinator, nodeLifecycle, eventFactory,
                state.runRepository(), state.checkpointRepository(), state.approvalStore());
    }
}
