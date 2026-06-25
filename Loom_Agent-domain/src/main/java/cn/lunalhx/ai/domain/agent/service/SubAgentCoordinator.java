package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentDispatchResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.Executor;

public class SubAgentCoordinator {

    private final SubAgentDispatchPlanner planner;
    private final SubAgentExecutionScheduler scheduler;
    private final SubAgentResultAggregator aggregator;
    private final ChildAgentRunner runner;

    public SubAgentCoordinator(RoleToolRegistryFactory toolRegistryFactory,
                               AgentLoopFactory agentLoopFactory,
                               AgentRuntimeProperties properties,
                               ObjectMapper objectMapper,
                               Executor executor) {
        SubAgentResultFactory resultFactory = new SubAgentResultFactory();
        ChildAgentServiceFactory serviceFactory = new AgentLoopFactoryChildServiceFactory(agentLoopFactory);
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
