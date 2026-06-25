package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.model.valobj.TokenUsage;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelPricing;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DefaultBudgetGuard implements BudgetGuard {

    private final AgentRuntimeProperties properties;
    private final ModelRuntimeProperties modelProperties;
    private final ConcurrentMap<String, BudgetState> states = new ConcurrentHashMap<>();

    public DefaultBudgetGuard(AgentRuntimeProperties properties) {
        this(properties, null);
    }

    public DefaultBudgetGuard(AgentRuntimeProperties properties, ModelRuntimeProperties modelProperties) {
        this.properties = properties;
        this.modelProperties = modelProperties;
    }

    @Override
    public BudgetCheckResult checkBeforeModelCall(AgentContext context, String node, String input) {
        AgentRuntimeProperties.BudgetProperties budget = budget();
        long usedTokens = currentUsedTokens(context);
        long estimatedInputTokens = estimateTokens(input, budget);
        long reservedOutputTokens = positive(budget.getReservedOutputTokens(), 0);
        long maxTotalTokens = positive(budget.getMaxTotalTokens(), Long.MAX_VALUE);
        if (!Boolean.TRUE.equals(budget.getEnabled())) {
            return BudgetCheckResult.allowed(usedTokens, estimatedInputTokens, reservedOutputTokens, maxTotalTokens);
        }
        long requested = usedTokens + estimatedInputTokens + reservedOutputTokens;
        if (requested > maxTotalTokens) {
            return BudgetCheckResult.blocked(usedTokens, estimatedInputTokens, reservedOutputTokens, maxTotalTokens);
        }
        return BudgetCheckResult.allowed(usedTokens, estimatedInputTokens, reservedOutputTokens, maxTotalTokens);
    }

    @Override
    public TraceCost recordModelUsage(AgentContext context, TokenUsage usage) {
        return recordModelUsage(context, null, usage);
    }

    @Override
    public BudgetCheckResult checkBeforeModelCall(AgentContext context,
                                                  String node,
                                                  String model,
                                                  ModelCallPurpose purpose,
                                                  String input,
                                                  int requestedMaxTokens) {
        BudgetCheckResult tokenCheck = checkBeforeModelCall(context, node, input);
        if (!tokenCheck.isAllowed() || !Boolean.TRUE.equals(budget().getEnabled())) {
            return tokenCheck;
        }
        BigDecimal maxTotalCost = budget().getMaxTotalCost();
        if (maxTotalCost == null || maxTotalCost.signum() <= 0) {
            return tokenCheck;
        }
        long estimatedInputTokens = tokenCheck.getEstimatedInputTokens();
        ModelPricing pricing = pricing(model);
        BigDecimal estimatedRequestCost = price(pricing.getInputPricePer1k(), estimatedInputTokens)
                .add(price(pricing.getOutputPricePer1k(), Math.max(0, requestedMaxTokens)));
        BigDecimal remainingCost = maxTotalCost.subtract(context.getEstimatedCost());
        tokenCheck.setEstimatedRequestCost(estimatedRequestCost);
        tokenCheck.setRemainingCost(remainingCost);
        if (estimatedRequestCost.compareTo(remainingCost) > 0) {
            tokenCheck.setAllowed(false);
            tokenCheck.setReason("estimated_request_cost > remaining_cost");
        }
        return tokenCheck;
    }

    @Override
    public TraceCost recordModelUsage(AgentContext context, String actualModel, TokenUsage usage) {
        if (context == null || usage == null) {
            return null;
        }
        long promptTokens = safe(usage.getPromptTokens());
        long completionTokens = safe(usage.getCompletionTokens());
        long totalTokens = safe(usage.getTotalTokens());
        if (totalTokens <= 0) {
            totalTokens = promptTokens + completionTokens;
        }
        TraceCost cost = calculateCost(actualModel, promptTokens, completionTokens);
        String rootRunId = rootRunId(context);
        BudgetState state = states.computeIfAbsent(rootRunId, ignored -> new BudgetState());
        synchronized (state) {
            state.usedPromptTokens += promptTokens;
            state.usedCompletionTokens += completionTokens;
            state.usedTokens += totalTokens;
            if (cost != null && cost.getTotalCost() != null) {
                state.estimatedCost = state.estimatedCost.add(cost.getTotalCost());
            }
            context.setUsedPromptTokens(state.usedPromptTokens);
            context.setUsedCompletionTokens(state.usedCompletionTokens);
            context.setUsedTokens(state.usedTokens);
            context.setEstimatedCost(state.estimatedCost);
        }
        return cost;
    }

    private long currentUsedTokens(AgentContext context) {
        if (context == null) {
            return 0L;
        }
        BudgetState state = states.get(rootRunId(context));
        if (state != null) {
            synchronized (state) {
                context.setUsedPromptTokens(Math.max(context.getUsedPromptTokens(), state.usedPromptTokens));
                context.setUsedCompletionTokens(Math.max(context.getUsedCompletionTokens(), state.usedCompletionTokens));
                context.setUsedTokens(Math.max(context.getUsedTokens(), state.usedTokens));
                if (state.estimatedCost.compareTo(context.getEstimatedCost()) > 0) {
                    context.setEstimatedCost(state.estimatedCost);
                }
            }
        }
        return context.getUsedTokens();
    }

    private TraceCost calculateCost(String model, long promptTokens, long completionTokens) {
        ModelPricing pricing = pricing(model);
        BigDecimal inputCost = price(pricing.getInputPricePer1k(), promptTokens);
        BigDecimal outputCost = price(pricing.getOutputPricePer1k(), completionTokens);
        return TraceCost.builder()
                .inputCost(inputCost)
                .outputCost(outputCost)
                .totalCost(inputCost.add(outputCost))
                .build();
    }

    private ModelPricing pricing(String model) {
        if (modelProperties != null && StringUtils.isNotBlank(model)) {
            ModelPricing configured = modelProperties.getModelPricing().get(model);
            if (configured != null) {
                return configured;
            }
        }
        return new ModelPricing(budget().getInputPricePer1k(), budget().getOutputPricePer1k());
    }

    private BigDecimal price(BigDecimal pricePer1k, long tokens) {
        if (pricePer1k == null || tokens <= 0) {
            return BigDecimal.ZERO;
        }
        return pricePer1k.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1000L), 8, RoundingMode.HALF_UP);
    }

    private long estimateTokens(String input, AgentRuntimeProperties.BudgetProperties budget) {
        if (StringUtils.isEmpty(input)) {
            return 0L;
        }
        int charsPerToken = Math.max(1, positive(budget.getEstimatedCharsPerToken(), 4));
        return (StringUtils.length(input) + charsPerToken - 1L) / charsPerToken;
    }

    private AgentRuntimeProperties.BudgetProperties budget() {
        if (properties.getBudget() == null) {
            properties.setBudget(new AgentRuntimeProperties.BudgetProperties());
        }
        return properties.getBudget();
    }

    private String rootRunId(AgentContext context) {
        return StringUtils.defaultIfBlank(context.getRootRunId(), context.getRunId());
    }

    private long safe(Integer value) {
        return value == null ? 0L : Math.max(0, value);
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private long positive(Integer value, long fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static class BudgetState {
        private long usedPromptTokens;
        private long usedCompletionTokens;
        private long usedTokens;
        private BigDecimal estimatedCost = BigDecimal.ZERO;
    }

}
