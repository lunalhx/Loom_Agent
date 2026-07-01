package cn.lunalhx.ai.domain.agent.service.subagent;

import cn.lunalhx.ai.domain.agent.adapter.port.SubAgentControlInbox;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentDispatchResult;
import cn.lunalhx.ai.domain.agent.model.entity.SubAgentResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
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
                               Executor executor,
                               SubAgentControlInbox controlInbox) {
        SubAgentResultFactory resultFactory = new SubAgentResultFactory();
        ChildAgentServiceFactory serviceFactory = new AgentLoopFactoryChildServiceFactory(agentLoopFactory);
        this.planner = new SubAgentDispatchPlanner(properties, new SubAgentDecisionParser());
        this.scheduler = new SubAgentExecutionScheduler(executor, resultFactory, properties, controlInbox);
        this.aggregator = new SubAgentResultAggregator(properties, objectMapper);
        this.runner = new ChildAgentRunner(toolRegistryFactory, serviceFactory, properties, resultFactory, objectMapper);
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
