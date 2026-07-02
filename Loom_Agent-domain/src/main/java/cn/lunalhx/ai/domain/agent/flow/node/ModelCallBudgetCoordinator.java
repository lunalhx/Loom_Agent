package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

final class ModelCallBudgetCoordinator {

    private final BudgetGuard budgetGuard;
    private final TraceRecorder traceRecorder;
    private final ModelPromptFactory promptFactory;

    ModelCallBudgetCoordinator(BudgetGuard budgetGuard, TraceRecorder traceRecorder, ModelPromptFactory promptFactory) {
        this.budgetGuard = budgetGuard;
        this.traceRecorder = traceRecorder;
        this.promptFactory = promptFactory;
    }

    BudgetCheckResult checkBeforeModelCall(AgentContext context, String nodeName, String requestedModel,
                                           int requestedMaxTokens) {
        if (budgetGuard == null) {
            return null;
        }
        return budgetGuard.checkBeforeModelCall(context, nodeName, requestedModel,
                ModelCallPurpose.CONTROL_JSON, promptFactory.budgetInput(context), requestedMaxTokens);
    }

    boolean isAllowed(BudgetCheckResult check) {
        return check == null || check.isAllowed();
    }

    void blockForBudget(AgentContext context, BudgetCheckResult check) {
        String reason = "budget_exceeded: usedTokens=" + check.getUsedTokens()
                + ", estimatedInputTokens=" + check.getEstimatedInputTokens()
                + ", reservedOutputTokens=" + check.getReservedOutputTokens()
                + ", maxTotalTokens=" + check.getMaxTotalTokens();
        context.setBudgetBlockedReason(reason);
        context.runtime().fail(AgentStopReason.BUDGET_EXCEEDED, "budget_exceeded", reason);
    }

    boolean checkFallbackModelBudget(AgentContext context, String nodeName, String fallbackModel, int requestedMaxTokens) {
        if (budgetGuard == null) {
            return true;
        }
        BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context, nodeName, fallbackModel,
                ModelCallPurpose.CONTROL_JSON, compactedBudgetInput(context), Math.max(0, requestedMaxTokens));
        return check.isAllowed();
    }

    void recordUsage(AgentContext context, String nodeName, ModelChatResult result) {
        TraceCost cost = budgetGuard == null ? null
                : budgetGuard.recordModelUsage(context, result.getActualModel(), result.getUsage());
        if (traceRecorder != null) {
            Map<String, Object> metadata = result.getUsage() == null
                    ? Map.of("usageMissing", true)
                    : Map.of("finishReason", StringUtils.defaultString(result.getFinishReason()));
            traceRecorder.recordModelUsage(context, nodeName, result.getUsage(), cost, metadata);
        }
    }

    void traceRecovery(AgentContext context, String eventType, String nodeName, Map<String, Object> metadata) {
        if (traceRecorder != null) {
            traceRecorder.recordModelGatewayEvent(context, eventType, nodeName, "success", 0L,
                    eventType, null, metadata);
        }
    }

    private String compactedBudgetInput(AgentContext context) {
        StringBuilder input = new StringBuilder(StringUtils.defaultString(context.getQuestion()));
        if (context.getPlan() != null) {
            input.append(context.getPlan().render());
        }
        if (context.getDynamicText() != null) {
            input.append(context.getDynamicText().render());
        }
        context.getToolSpecs().forEach(spec -> input.append(spec.getName())
                .append(spec.getDescription())
                .append(spec.getInputSchema()));
        return input.toString();
    }
}
