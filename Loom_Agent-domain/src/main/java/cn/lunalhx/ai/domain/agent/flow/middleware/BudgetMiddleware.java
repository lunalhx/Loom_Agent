package cn.lunalhx.ai.domain.agent.flow.middleware;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;

import java.util.Objects;

public class BudgetMiddleware implements ModelCallMiddleware {

    private final BudgetGuard budgetGuard;
    private final AgentRuntimeProperties properties;

    public BudgetMiddleware(BudgetGuard budgetGuard, AgentRuntimeProperties properties) {
        this.budgetGuard = Objects.requireNonNull(budgetGuard, "budgetGuard must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public ModelCallOutcome apply(ModelCallContext ctx, ModelCallNext next) {
        AgentContext context = ctx.getAgentContext();

        // Consume the same PreparedContextView built by ContextReductionMiddleware so
        // that budget estimation exactly matches the actual request. BudgetGuard only
        // performs the global token/cost quota check; it never changes section budgets.
        String budgetText = ctx.getPreparedView() != null
                ? ctx.getPreparedView().budgetText()
                : (context.getQuestion() == null ? "" : context.getQuestion());

        BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context,
                AgentNodeNames.MODEL_CALL, ctx.getRequestModel(),
                ModelCallPurpose.CONTROL_JSON,
                budgetText, ctx.getMaxTokens() == null ? 0 : ctx.getMaxTokens());
        if (!check.isAllowed()) {
            String reason = "budget_exceeded: usedTokens=" + check.getUsedTokens()
                    + ", estimatedInputTokens=" + check.getEstimatedInputTokens()
                    + ", reservedOutputTokens=" + check.getReservedOutputTokens()
                    + ", maxTotalTokens=" + check.getMaxTotalTokens();
            context.setBudgetBlockedReason(reason);
            context.blockForBudget("budget_exceeded", reason);
            return ModelCallOutcome.budgetBlocked();
        }
        return next.invoke(ctx);
    }

}
