package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.BudgetProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;

import java.math.BigDecimal;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

public final class RuntimeConfigValidators implements RuntimeConfigValidator<RuntimeConfigValidators.ConfigGroups> {

    private static final Set<String> APPROVAL_POLICIES = Set.of("ask", "auto", "never");
    private static final RuntimeConfigValidators AGGREGATE = new RuntimeConfigValidators();

    private RuntimeConfigValidators() {
    }

    public static void validate(AgentRuntimeProperties agent, ModelRuntimeProperties model) {
        AGGREGATE.validate(new ConfigGroups(agent, model));
    }

    @Override
    public void validate(ConfigGroups groups) {
        AgentRuntimeProperties agent = groups.agent();
        ModelRuntimeProperties model = groups.model();
        validateCore(agent);
        validateBudget(agent.getBudget());
        validateContext(agent.getContext());
        validateModel(model);
        validateCrossGroup(agent, model);
    }

    public record ConfigGroups(AgentRuntimeProperties agent, ModelRuntimeProperties model) {
    }

    static void validateCore(AgentRuntimeProperties value) {
        positive(value.getMaxSteps(), "loom.agent.max-steps");
        positive(value.getTotalTimeoutMs(), "loom.agent.total-timeout-ms");
        positive(value.getStepTimeoutMs(), "loom.agent.step-timeout-ms");
        positive(value.getToolTimeoutMs(), "loom.agent.tool-timeout-ms");
        if (value.getApprovalPolicy() != null
                && !APPROVAL_POLICIES.contains(value.getApprovalPolicy().toLowerCase())) {
            fail("loom.agent.approval-policy", "must be ask, auto, or never");
        }
        path(value.getWorkspaceRoot(), "loom.agent.workspace-root");
    }

    static void validateBudget(BudgetProperties value) {
        if (value == null) {
            fail("loom.agent.budget", "is required");
        }
        positive(value.getMaxTotalTokens(), "loom.agent.budget.max-total-tokens");
        positive(value.getEstimatedCharsPerToken(), "loom.agent.budget.estimated-chars-per-token");
        nonNegative(value.getReservedOutputTokens(), "loom.agent.budget.reserved-output-tokens");
        if (value.getMaxTotalCost() != null && value.getMaxTotalCost().compareTo(BigDecimal.ZERO) <= 0) {
            fail("loom.agent.budget.max-total-cost", "must be greater than zero");
        }
    }

    static void validateContext(ContextProperties value) {
        if (value == null) {
            fail("loom.agent.context", "is required");
        }
        positive(value.getTotalBudgetChars(), "loom.agent.context.total-budget-chars");
        positive(value.getPrefixBudgetChars(), "loom.agent.context.prefix-budget-chars");
        positive(value.getMemoryBudgetChars(), "loom.agent.context.memory-budget-chars");
        positive(value.getRelevantMemoryBudgetChars(), "loom.agent.context.relevant-memory-budget-chars");
        positive(value.getHistoryBudgetChars(), "loom.agent.context.history-budget-chars");
    }

    static void validateModel(ModelRuntimeProperties value) {
        positive(value.getFirstTokenTimeoutMs(), "loom.ai.first-token-timeout-ms");
        positive(value.getStreamTimeoutMs(), "loom.ai.stream-timeout-ms");
        positive(value.getRetryMaxAttempts(), "loom.ai.retry-max-attempts");
        if (value.getAllowedModels() == null || value.getAllowedModels().isEmpty()) {
            fail("loom.ai.allowed-models", "must contain at least one model");
        }
        ModelRuntimeProperties.ProviderConfig active;
        try {
            active = value.activeProvider();
        } catch (RuntimeException e) {
            throw new IllegalStateException("loom.ai.providers." + value.getProvider() + " is required", e);
        }
        if (active.getDefaultModel() == null || !value.getAllowedModels().contains(active.getDefaultModel())) {
            fail("loom.ai.providers." + value.getProvider() + ".default-model", "must be present in allowed-models");
        }
        if (!value.getAllowedModels().contains(value.resolvedDefaultModel())) {
            fail("loom.ai.default-model", "must be present in allowed-models");
        }
    }

    static void validateCrossGroup(AgentRuntimeProperties agent, ModelRuntimeProperties model) {
        var resilience = model.getResilience();
        if (Boolean.TRUE.equals(resilience.getEnabled())) {
            positive(resilience.getRetryMaxAttempts(), "loom.ai.resilience.retry-max-attempts");
            positive(resilience.getRetryBackoffInitialMs(), "loom.ai.resilience.retry-backoff-initial-ms");
            positive(resilience.getRetryBackoffMaxMs(), "loom.ai.resilience.retry-backoff-max-ms");
            positive(resilience.getCircuitSlidingWindowSize(), "loom.ai.resilience.circuit-sliding-window-size");
            positive(resilience.getCircuitOpenStateWaitMs(), "loom.ai.resilience.circuit-open-state-wait-ms");
            positive(resilience.getCircuitHalfOpenPermittedCalls(),
                    "loom.ai.resilience.circuit-half-open-permitted-calls");
            if (resilience.getFallbackModel() != null && !resilience.getFallbackModel().isBlank()
                    && !model.getAllowedModels().contains(resilience.getFallbackModel())) {
                fail("loom.ai.resilience.fallback-model", "must be present in loom.ai.allowed-models");
            }
            if (!"current_step".equalsIgnoreCase(resilience.getFallbackStickinessScope())) {
                fail("loom.ai.resilience.fallback-stickiness-scope", "must be current_step");
            }
        }
        if (Boolean.TRUE.equals(agent.getBudget().getEnabled())
                && agent.getBudget().getMaxTotalCost() != null) {
            for (String allowedModel : model.getAllowedModels()) {
                var pricing = model.pricing(allowedModel);
                if (pricing.getInputPricePer1k() == null || pricing.getOutputPricePer1k() == null
                        || pricing.getInputPricePer1k().signum() < 0
                        || pricing.getOutputPricePer1k().signum() < 0
                        || pricing.getInputPricePer1k().signum() + pricing.getOutputPricePer1k().signum() == 0) {
                    fail("loom.ai.model-pricing." + allowedModel,
                            "must contain non-negative pricing when a cost budget is enabled");
                }
            }
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase();
    }

    private static void path(String value, String name) {
        try {
            if (value == null || value.isBlank()) {
                fail(name, "is required");
            }
            Path.of(value).normalize();
        } catch (InvalidPathException e) {
            fail(name, "is not a valid path");
        }
    }

    private static void positive(Integer value, String name) {
        if (value == null || value <= 0) fail(name, "must be greater than zero");
    }

    private static void positive(Long value, String name) {
        if (value == null || value <= 0) fail(name, "must be greater than zero");
    }

    private static void positive(int value, String name) {
        if (value <= 0) fail(name, "must be greater than zero");
    }

    private static void positive(long value, String name) {
        if (value <= 0) fail(name, "must be greater than zero");
    }

    private static void nonNegative(Integer value, String name) {
        if (value == null || value < 0) fail(name, "must not be negative");
    }

    private static void fail(String path, String message) {
        throw new IllegalStateException(path + " " + message);
    }
}
