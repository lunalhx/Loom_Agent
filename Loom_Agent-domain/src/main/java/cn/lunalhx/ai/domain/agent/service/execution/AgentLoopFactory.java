package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.agent.service.context.AgentContextFactory;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryInitializer;
import cn.lunalhx.ai.domain.agent.service.ledger.LedgerBootstrapService;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.tool.service.ToolExecutor;

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
    private final ToolExecutor.ApprovalPrompt approvalPrompt;

    public AgentLoopFactory(ModelGateway modelGateway,
                            AgentLoopStateDependencies state,
                            AgentLoopRuntimeDependencies runtime,
                            ConversationHistoryAppendService ledgerAppendService,
                            ContextManager contextManager,
                            ConversationExecutionGuard executionGuard) {
        this(modelGateway, state, runtime, ledgerAppendService, contextManager, executionGuard, null);
    }

    public AgentLoopFactory(ModelGateway modelGateway,
                            AgentLoopStateDependencies state,
                            AgentLoopRuntimeDependencies runtime,
                            ConversationHistoryAppendService ledgerAppendService,
                            ContextManager contextManager,
                            ConversationExecutionGuard executionGuard,
                            ToolExecutor.ApprovalPrompt approvalPrompt) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.ledgerAppendService = Objects.requireNonNull(
                ledgerAppendService, "ledgerAppendService must not be null");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager must not be null");
        this.executionGuard = Objects.requireNonNull(executionGuard, "executionGuard must not be null");
        this.approvalPrompt = approvalPrompt;
        LedgerBootstrapService bs = new LedgerBootstrapService(
                ledgerAppendService, new ConversationHistoryInitializer());

        this.flowFactory = new AgentFlowFactory(modelGateway, state, runtime,
                ledgerAppendService, bs, contextManager, approvalPrompt);
    }

    public DefaultAgentLoopService createStandalone(ToolRegistry toolRegistry, Executor executor) {
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        AgentLoopAssembly assembly = assemble(toolRegistry);
        AgentRunLifecycle lifecycle = new AgentRunLifecycle(
                state.runRepository(), state.checkpointRepository());
        return new DefaultAgentLoopService(assembly, executor, lifecycle, executionGuard);
    }

    AgentLoopAssembly assemble(ToolRegistry toolRegistry) {
        AgentFlowDefinition flow = flowFactory.create(toolRegistry);
        AgentLoopComponents components = buildComponents(flow, toolRegistry);
        return new AgentLoopAssembly(runtime.properties(), flow, components);
    }

    private AgentLoopComponents buildComponents(AgentFlowDefinition flow, ToolRegistry toolRegistry) {
        AgentEventFactory eventFactory = new AgentEventFactory();
        AgentContextFactory contextFactory = new AgentContextFactory(
                runtime.properties(), state.workspaceResolver(),
                runtime.runtimeConfigSource(), toolRegistry);
        AgentNodeLifecycle nodeLifecycle = new AgentNodeLifecycle(
                runtime.traceRecorder(), runtime.agentMetrics(), eventFactory, flow.nodes());
        AgentRunLifecycle lifecycle = new AgentRunLifecycle(
                state.runRepository(), state.checkpointRepository());
        return new AgentLoopComponents(contextFactory, nodeLifecycle, eventFactory,
                state.runRepository(), state.checkpointRepository(),
                lifecycle, ledgerAppendService);
    }
}
