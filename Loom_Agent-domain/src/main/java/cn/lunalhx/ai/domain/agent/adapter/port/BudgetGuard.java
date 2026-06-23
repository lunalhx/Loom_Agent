package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;

public interface BudgetGuard {

    BudgetCheckResult checkBeforeModelCall(AgentContext context, String node, String input);

    TraceCost recordModelUsage(AgentContext context, TokenUsage usage);

}
