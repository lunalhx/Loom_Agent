package cn.lunalhx.ai.domain.agent.adapter.port;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;

public interface BudgetGuard {

    BudgetCheckResult checkBeforeModelCall(AgentContext context, String node, String input);

    TraceCost recordModelUsage(AgentContext context, TokenUsage usage);

    default BudgetCheckResult checkBeforeModelCall(AgentContext context,
                                                   String node,
                                                   String model,
                                                   ModelCallPurpose purpose,
                                                   String input,
                                                   int requestedMaxTokens) {
        return checkBeforeModelCall(context, node, input);
    }

    default TraceCost recordModelUsage(AgentContext context, String actualModel, TokenUsage usage) {
        return recordModelUsage(context, usage);
    }

}
