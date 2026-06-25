package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentDispatchResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.Executor;

public class SubAgentCoordinator {

    // legacy fields retained for deprecated constructors
    private final ModelGateway modelGateway;
    private final ApprovalStore approvalStore;
    private final AgentWorkspaceResolver workspaceResolver;
    private final AgentRunRepository runRepository;
    private final AgentCheckpointRepository checkpointRepository;
    private final TraceRecorder traceRecorder;
    private final BudgetGuard budgetGuard;
    private final AgentMetrics agentMetrics;
    private final ContextWindowManager contextWindowManager;
    private final AgentLoopFactory agentLoopFactory;

    // new components
    private final SubAgentDispatchPlanner planner;
    private final SubAgentExecutionScheduler scheduler;
    private final SubAgentResultAggregator aggregator;
    private final ChildAgentRunner runner;

    // ==================== Phase 2 新生产构造器（5 参数） ====================

    public SubAgentCoordinator(RoleToolRegistryFactory toolRegistryFactory,
                               AgentLoopFactory agentLoopFactory,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor) {
        this.modelGateway = null;
        this.approvalStore = null;
        this.workspaceResolver = null;
        this.runRepository = null;
        this.checkpointRepository = null;
        this.traceRecorder = null;
        this.budgetGuard = null;
        this.agentMetrics = null;
        this.contextWindowManager = null;
        this.agentLoopFactory = agentLoopFactory;

        SubAgentResultFactory resultFactory = new SubAgentResultFactory();
        ChildAgentServiceFactory serviceFactory = new AgentLoopFactoryChildServiceFactory(agentLoopFactory);
        this.planner = new SubAgentDispatchPlanner(properties, new SubAgentDecisionParser());
        this.scheduler = new SubAgentExecutionScheduler(executor, resultFactory);
        this.aggregator = new SubAgentResultAggregator(properties, objectMapper);
        this.runner = new ChildAgentRunner(toolRegistryFactory, serviceFactory, properties, resultFactory);
    }

    // ==================== 旧构造器（标记废弃，最终阶段删除） ====================

    @Deprecated(forRemoval = true)
    public SubAgentCoordinator(ModelGateway modelGateway,
                               RoleToolRegistryFactory toolRegistryFactory,
                               ApprovalStore approvalStore,
                               AgentWorkspaceResolver workspaceResolver,
                               AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor) {
        this(modelGateway, toolRegistryFactory, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, new InMemoryTraceRecorder(), new DefaultBudgetGuard(properties), new NoopAgentMetrics());
    }

    @Deprecated(forRemoval = true)
    public SubAgentCoordinator(ModelGateway modelGateway,
                               RoleToolRegistryFactory toolRegistryFactory,
                               ApprovalStore approvalStore,
                               AgentWorkspaceResolver workspaceResolver,
                               AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor,
                               TraceRecorder traceRecorder,
                               BudgetGuard budgetGuard) {
        this(modelGateway, toolRegistryFactory, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, traceRecorder, budgetGuard, new NoopAgentMetrics());
    }

    @Deprecated(forRemoval = true)
    public SubAgentCoordinator(ModelGateway modelGateway,
                               RoleToolRegistryFactory toolRegistryFactory,
                               ApprovalStore approvalStore,
                               AgentWorkspaceResolver workspaceResolver,
                               AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor,
                               TraceRecorder traceRecorder,
                               BudgetGuard budgetGuard,
                               AgentMetrics agentMetrics) {
        this(modelGateway, toolRegistryFactory, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, executor, traceRecorder, budgetGuard, agentMetrics,
                ContextWindowManager.noop(properties));
    }

    @Deprecated(forRemoval = true)
    public SubAgentCoordinator(ModelGateway modelGateway,
                               RoleToolRegistryFactory toolRegistryFactory,
                               ApprovalStore approvalStore,
                               AgentWorkspaceResolver workspaceResolver,
                               AgentRunRepository runRepository,
                               AgentCheckpointRepository checkpointRepository,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor,
                               TraceRecorder traceRecorder,
                               BudgetGuard budgetGuard,
                               AgentMetrics agentMetrics,
                               ContextWindowManager contextWindowManager) {
        this.modelGateway = modelGateway;
        this.approvalStore = approvalStore;
        this.workspaceResolver = workspaceResolver;
        this.runRepository = runRepository;
        this.checkpointRepository = checkpointRepository;
        this.traceRecorder = traceRecorder == null ? new InMemoryTraceRecorder() : traceRecorder;
        this.budgetGuard = budgetGuard == null ? new DefaultBudgetGuard(properties) : budgetGuard;
        this.agentMetrics = agentMetrics == null ? new NoopAgentMetrics() : agentMetrics;
        this.contextWindowManager = contextWindowManager == null ? ContextWindowManager.noop(properties) : contextWindowManager;
        this.agentLoopFactory = null;

        SubAgentResultFactory resultFactory = new SubAgentResultFactory();
        ChildAgentServiceFactory serviceFactory = new LegacyChildServiceFactory(
                modelGateway, approvalStore, workspaceResolver, runRepository, checkpointRepository,
                properties, objectMapper, this.traceRecorder, this.budgetGuard, this.agentMetrics, this.contextWindowManager);
        this.planner = new SubAgentDispatchPlanner(properties, new SubAgentDecisionParser());
        this.scheduler = new SubAgentExecutionScheduler(executor, resultFactory);
        this.aggregator = new SubAgentResultAggregator(properties, objectMapper);
        this.runner = new ChildAgentRunner(toolRegistryFactory, serviceFactory, properties, resultFactory);
    }

    public SubAgentDispatchResult dispatch(AgentContext parent) {
        long startedAt = System.currentTimeMillis();

        SubAgentPlanResult planResult = planner.plan(parent);
        if (planResult.errorCode() != null) {
            return SubAgentDispatchResult.failure(planResult.errorCode(), planResult.errorMessage(), elapsed(startedAt));
        }

        SubAgentDispatchPlan plan = planResult.plan();
        List<SubAgentResult> results = scheduler.schedule(plan, parent, runner);
        return aggregator.aggregate(parent, plan.reason(), results, startedAt);
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
