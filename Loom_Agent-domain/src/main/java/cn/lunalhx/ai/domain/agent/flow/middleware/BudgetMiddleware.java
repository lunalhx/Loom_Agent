package cn.lunalhx.ai.domain.agent.flow.middleware;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;

import java.util.Objects;
import java.util.function.Function;

public class BudgetMiddleware implements ModelCallMiddleware {

    private final BudgetGuard budgetGuard;
    private final AgentRuntimeProperties properties;
    private final Function<AgentContext, String> budgetInput;

    public BudgetMiddleware(BudgetGuard budgetGuard, AgentRuntimeProperties properties) {
        this(budgetGuard, properties, context -> context.getQuestion() == null ? "" : context.getQuestion());
    }

    public BudgetMiddleware(BudgetGuard budgetGuard, AgentRuntimeProperties properties,
                            Function<AgentContext, String> budgetInput) {
        this.budgetGuard = Objects.requireNonNull(budgetGuard, "budgetGuard must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.budgetInput = Objects.requireNonNull(budgetInput, "budgetInput must not be null");
    }

    @Override
    public ModelCallOutcome apply(ModelCallContext ctx, ModelCallNext next) {
        AgentContext context = ctx.getAgentContext();

        BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context,
                AgentNodeNames.MODEL_CALL, ctx.getRequestModel(),
                ModelCallPurpose.CONTROL_JSON,
                budgetInput.apply(context), ctx.getMaxTokens() == null ? 0 : ctx.getMaxTokens());
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
