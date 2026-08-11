package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;

/** Runtime boundary for the terminal Plan Submission action. */
public interface PlanSubmissionHandler {

    PlanSubmissionResult prepare(AgentContext context);

    PlanSubmissionResult commit(AgentContext context);

    /** Release a prepared submission when terminal Run persistence fails. */
    void abort(AgentContext context);
}
