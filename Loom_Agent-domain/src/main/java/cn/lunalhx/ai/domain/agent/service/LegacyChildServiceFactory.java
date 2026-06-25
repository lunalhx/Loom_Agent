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

class LegacyChildServiceFactory implements ChildAgentServiceFactory {

    private final ModelGateway modelGateway;
    private final ApprovalStore approvalStore;
    private final AgentWorkspaceResolver workspaceResolver;
    private final AgentRunRepository runRepository;
    private final AgentCheckpointRepository checkpointRepository;
    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final TraceRecorder traceRecorder;
    private final BudgetGuard budgetGuard;
    private final AgentMetrics agentMetrics;
    private final ContextWindowManager contextWindowManager;

    LegacyChildServiceFactory(ModelGateway modelGateway,
                              ApprovalStore approvalStore,
                              AgentWorkspaceResolver workspaceResolver,
                              AgentRunRepository runRepository,
                              AgentCheckpointRepository checkpointRepository,
                              AgentRuntimeProperties properties,
                              ObjectMapper objectMapper,
                              TraceRecorder traceRecorder,
                              BudgetGuard budgetGuard,
                              AgentMetrics agentMetrics,
                              ContextWindowManager contextWindowManager) {
        this.modelGateway = modelGateway;
        this.approvalStore = approvalStore;
        this.workspaceResolver = workspaceResolver;
        this.runRepository = runRepository;
        this.checkpointRepository = checkpointRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.traceRecorder = traceRecorder;
        this.budgetGuard = budgetGuard;
        this.agentMetrics = agentMetrics;
        this.contextWindowManager = contextWindowManager;
    }

    @Override
    public DefaultAgentLoopService create(ToolRegistry registry) {
        return new DefaultAgentLoopService(
                modelGateway, registry, approvalStore, workspaceResolver,
                runRepository, checkpointRepository, properties, objectMapper,
                Runnable::run, null, traceRecorder, budgetGuard, agentMetrics,
                contextWindowManager);
    }
}
