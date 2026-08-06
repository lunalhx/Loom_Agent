package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.agent.service.observability.ModelCallTraceLabels;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

final class ModelCallBudgetCoordinator {

    private final BudgetGuard budgetGuard;
    private final TraceRecorder traceRecorder;
    private final ModelPromptFactory promptFactory;
    private final cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics agentMetrics;

    ModelCallBudgetCoordinator(BudgetGuard budgetGuard, TraceRecorder traceRecorder,
                               ModelPromptFactory promptFactory,
                               cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics agentMetrics) {
        this.budgetGuard = budgetGuard;
        this.traceRecorder = traceRecorder;
        this.promptFactory = promptFactory;
        this.agentMetrics = agentMetrics;
    }

    BudgetCheckResult checkBeforeModelCall(AgentContext context, String nodeName, String requestedModel,
                                           int requestedMaxTokens,
                                           cn.lunalhx.ai.domain.agent.service.context.PreparedContextView view) {
        if (budgetGuard == null) {
            return null;
        }
        String input = view != null ? view.budgetText()
                : (promptFactory != null ? "" : String.valueOf(context.getQuestion()));
        return budgetGuard.checkBeforeModelCall(context, nodeName, requestedModel,
                ModelCallPurpose.CONTROL_JSON, input, requestedMaxTokens);
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

    boolean checkFallbackModelBudget(AgentContext context, String nodeName, String fallbackModel, int requestedMaxTokens,
                                     cn.lunalhx.ai.domain.agent.service.context.PreparedContextView view) {
        if (budgetGuard == null) {
            return true;
        }
        String input = view != null ? view.budgetText() : String.valueOf(context.getQuestion());
        BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context, nodeName, fallbackModel,
                ModelCallPurpose.CONTROL_JSON, input, Math.max(0, requestedMaxTokens));
        return check.isAllowed();
    }

    void recordUsage(AgentContext context, String nodeName, ModelChatResult result) {
        TraceCost cost = budgetGuard == null ? null
                : budgetGuard.recordModelUsage(context, result.getActualModel(), result.getUsage());
        if (traceRecorder != null) {
            Map<String, Object> extras = new java.util.LinkedHashMap<>();
            extras.put("finishReason", StringUtils.defaultString(result.getFinishReason()));
            if (result.getFallbackReason() != null) {
                extras.put("fallbackReason", result.getFallbackReason());
            }
            cn.lunalhx.ai.domain.model.valobj.TokenUsage usage = result.getUsage();
            if (usage != null) {
                cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus status =
                        cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.fromUsage(usage);
                extras.put("promptCacheStatus", status == null ? "unknown" : status.name());
                extras.put("promptCacheCapability", StringUtils.defaultString(usage.getCacheCapability(), "unsupported"));
                if (usage.getPromptCacheHitTokens() != null) {
                    extras.put("promptCacheReadTokens", usage.getPromptCacheHitTokens());
                }
                if (usage.getPromptCacheMissTokens() != null) {
                    extras.put("promptCacheCreationTokens", usage.getPromptCacheMissTokens());
                }
                String fingerprint = context.getStablePrefix() == null
                        ? null : context.getStablePrefix().fingerprint();
                if (fingerprint != null) {
                    extras.put("stablePrefixFingerprint", fingerprint);
                }
            }
            Map<String, Object> metadata = ModelCallTraceLabels.buildUsageMetadata(context, nodeName,
                    ModelCapabilities.COMPLETE_AGENT_DECISION, ModelCallPurpose.CONTROL_JSON,
                    result.getActualModel(), usage, extras.isEmpty() ? null : extras);
            traceRecorder.recordModelUsage(context, nodeName, usage, cost, metadata);
            recordCacheMetrics(context, nodeName, result, usage);
        }
    }

    void traceRecovery(AgentContext context, String eventType, String nodeName, Map<String, Object> metadata) {
        if (traceRecorder != null) {
            traceRecorder.recordModelGatewayEvent(context, eventType, nodeName, "success", 0L,
                    eventType, null, metadata);
        }
    }

    private void recordCacheMetrics(AgentContext context, String nodeName, ModelChatResult result,
                                    cn.lunalhx.ai.domain.model.valobj.TokenUsage usage) {
        if (agentMetrics == null) {
            return;
        }
        cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus status =
                cn.lunalhx.ai.domain.model.valobj.PromptCacheStatus.fromUsage(usage);
        agentMetrics.recordPromptCache(
                providerOf(context),
                result == null ? null : result.getActualModel(),
                usage == null ? "unsupported" : usage.getCacheCapability(),
                "read",
                status,
                usage == null ? null : usage.getPromptCacheHitTokens(),
                usage == null ? null : usage.getPromptCacheMissTokens(),
                usage);
    }

    private String providerOf(AgentContext context) {
        if (context == null || context.getRunConfig() == null
                || context.getRunConfig().model() == null) {
            return "unknown";
        }
        return StringUtils.defaultIfBlank(context.getRunConfig().model().getProvider(), "unknown");
    }
}
