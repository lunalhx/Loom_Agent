package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.BudgetProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.MemoryRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.SandboxProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ShellCommandProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.StepBudgetProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;

import java.math.BigDecimal;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;

public final class RuntimeConfigValidators implements RuntimeConfigValidator<RuntimeConfigValidators.ConfigGroups> {

    private static final Set<String> PERMISSION_MODES = Set.of("SANDBOX", "ACCEPT_EDITS", "BYPASS");
    private static final Set<String> HIGH_RISK_POLICIES = Set.of("DENY", "CONFIRM", "ALLOW");
    private static final Set<String> SHELL_LEVELS = Set.of("HIGH_RISK_CONFIRM", "HIGH_RISK_DENY");
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
        validateContext(agent.getContext(), model);
        validateShell(agent.getShellCommands());
        validateStepBudget(agent.getStepBudget(), agent.getMaxSteps());
        validateSandbox(agent.getSandbox());
        validateMemory(agent.getLongTermMemory(), model);
        validateLedger(agent);
        validateSkills(agent);
        validateBackground(agent);
        validateUndo(agent);
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
        positive(value.getApprovalTtlSeconds(), "loom.agent.approval-ttl-seconds");
        positive(value.getShellTimeoutMs(), "loom.agent.shell-timeout-ms");
        positive(value.getShellMaxTimeoutMs(), "loom.agent.shell-max-timeout-ms");
        positive(value.getSubAgentTimeoutMs(), "loom.agent.sub-agent-timeout-ms");
        positive(value.getSubAgentMaxChildren(), "loom.agent.sub-agent-max-children");
        positive(value.getSubAgentMaxConcurrency(), "loom.agent.sub-agent-max-concurrency");
        positive(value.getSubAgentMaxDepth(), "loom.agent.sub-agent-max-depth");
        if (!PERMISSION_MODES.contains(upper(value.getPermissionMode()))) {
            fail("loom.agent.permission-mode", "must be SANDBOX, ACCEPT_EDITS, or BYPASS");
        }
        if (!HIGH_RISK_POLICIES.contains(upper(value.getHighRiskPolicy()))) {
            fail("loom.agent.high-risk-policy", "must be DENY, CONFIRM, or ALLOW");
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

    static void validateContext(ContextProperties value, ModelRuntimeProperties model) {
        if (value == null) {
            fail("loom.agent.context", "is required");
        }
        positive(value.getPersistToolResultChars(), "loom.agent.context.persist-tool-result-chars");
        range(value.getToolPreviewChars(), 1, 2000, "loom.agent.context.tool-preview-chars");
        positive(value.getKeepRecentToolResults(), "loom.agent.context.keep-recent-tool-results");
        positive(value.getMaxDynamicEntries(), "loom.agent.context.max-dynamic-entries");
        positive(value.getAutoCompactTokenLimit(), "loom.agent.context.auto-compact-token-limit");
        range(value.getReactiveCompactMaxAttempts(), 0, 1, "loom.agent.context.reactive-compact-max-attempts");
        positive(value.getSummaryMaxChars(), "loom.agent.context.summary-max-chars");
        path(value.getStorageRoot(), "loom.agent.context.storage-root");
        if (value.getDeepSummaryModel() != null && !value.getDeepSummaryModel().isBlank()
                && !model.getAllowedModels().contains(value.getDeepSummaryModel())) {
            fail("loom.agent.context.deep-summary-model", "must be present in loom.ai.allowed-models");
        }
    }

    static void validateShell(ShellCommandProperties value) {
        if (value == null) {
            fail("loom.agent.shell-commands", "is required");
        }
        if (!SHELL_LEVELS.contains(upper(value.getShellSyntaxLevel()))) {
            fail("loom.agent.shell-commands.shell-syntax-level", "must be HIGH_RISK_CONFIRM or HIGH_RISK_DENY");
        }
        if (!Set.of("WRITE_CONFIRM", "HIGH_RISK_CONFIRM", "HIGH_RISK_DENY").contains(upper(value.getUnknownLevel()))) {
            fail("loom.agent.shell-commands.unknown-level", "cannot grant READ_ONLY to unknown commands");
        }
        if (value.getShellInterpreter() == null || value.getShellInterpreter().isBlank()) {
            fail("loom.agent.shell-commands.shell-interpreter", "is required");
        }
    }

    static void validateStepBudget(StepBudgetProperties value, Integer maxSteps) {
        if (value == null) {
            fail("loom.agent.step-budget", "is required");
        }
        positive(value.getMaxSegments(), "loom.agent.step-budget.max-segments");
        positive(value.getChildMaxSegments(), "loom.agent.step-budget.child-max-segments");
        positive(value.getMaxTotalSteps(), "loom.agent.step-budget.max-total-steps");
        positive(value.getSameActionMaxRepeats(), "loom.agent.step-budget.same-action-max-repeats");
        positive(value.getSameFailureMaxRepeats(), "loom.agent.step-budget.same-failure-max-repeats");
        positive(value.getNoProgressMaxRounds(), "loom.agent.step-budget.no-progress-max-rounds");
        if (value.getMaxTotalSteps() < maxSteps) {
            fail("loom.agent.step-budget.max-total-steps", "cannot be less than loom.agent.max-steps");
        }
    }

    static void validateSandbox(SandboxProperties value) {
        if (value == null) {
            fail("loom.agent.sandbox", "is required");
        }
        if (!Set.of("ALLOWLIST", "BLACKLIST").contains(upper(value.getEnvMode()))) {
            fail("loom.agent.sandbox.env-mode", "must be ALLOWLIST or BLACKLIST");
        }
        positive(value.getMaxCachedConversations(), "loom.agent.sandbox.max-cached-conversations");
        positive(value.getIdleTtlMs(), "loom.agent.sandbox.idle-ttl-ms");
    }

    static void validateMemory(MemoryRuntimeProperties value, ModelRuntimeProperties model) {
        if (value == null) {
            fail("loom.agent.long-term-memory", "is required");
        }
        positive(value.getMaxActive(), "loom.agent.long-term-memory.max-active");
        positive(value.getMaxSelected(), "loom.agent.long-term-memory.max-selected");
        positive(value.getMaxInjectedChars(), "loom.agent.long-term-memory.max-injected-chars");
        range(value.getPinnedLimit(), 0, value.getMaxSelected(),
                "loom.agent.long-term-memory.pinned-limit");
        if (value.getMinRelevanceScore() < 0 || value.getMinRelevanceScore() > 1) {
            fail("loom.agent.long-term-memory.min-relevance-score", "must be between 0 and 1");
        }
        positive(value.getArchiveAfterUnusedDays(), "loom.agent.long-term-memory.archive-after-unused-days");
        range(value.getArchiveMinImportance(), 0, 100,
                "loom.agent.long-term-memory.archive-min-importance");
        positive(value.getCleanupIntervalHours(), "loom.agent.long-term-memory.cleanup-interval-hours");
        positive(value.getExtractionTimeoutSeconds(), "loom.agent.long-term-memory.extraction-timeout-seconds");
        validateOptionalModel(value.getSelectionModel(), "loom.agent.long-term-memory.selection-model", model);
        validateOptionalModel(value.getExtractionModel(), "loom.agent.long-term-memory.extraction-model", model);
    }

    private static void validateOptionalModel(String value, String path, ModelRuntimeProperties model) {
        if (value != null && !value.isBlank() && !model.getAllowedModels().contains(value)) {
            fail(path, "must be present in loom.ai.allowed-models");
        }
    }

    static void validateLedger(AgentRuntimeProperties agent) {
        var value = agent.getConversationLedger();
        positive(value.getCompactionHighWatermark(), "loom.agent.conversation-ledger.compaction-high-watermark");
        positive(value.getCompactionLowWatermark(), "loom.agent.conversation-ledger.compaction-low-watermark");
        positive(value.getMaxCompactionDepth(), "loom.agent.conversation-ledger.max-compaction-depth");
        if (value.getCompactionLowWatermark() >= value.getCompactionHighWatermark()) {
            fail("loom.agent.conversation-ledger.compaction-low-watermark", "must be below the high watermark");
        }
    }

    static void validateSkills(AgentRuntimeProperties agent) {
        var value = agent.getSkills();
        positive(value.getCatalogMaxChars(), "loom.agent.skills.catalog-max-chars");
        positive(value.getMaxResourceFiles(), "loom.agent.skills.max-resource-files");
        positive(value.getMaxResourceBytes(), "loom.agent.skills.max-resource-bytes");
        positive(value.getMaxSnapshotBytes(), "loom.agent.skills.max-snapshot-bytes");
        path(value.getProjectDir(), "loom.agent.skills.project-dir");
        if (value.getUserDir() != null && !value.getUserDir().isBlank()) {
            path(value.getUserDir(), "loom.agent.skills.user-dir");
        }
    }

    static void validateBackground(AgentRuntimeProperties agent) {
        var value = agent.getBackgroundShell();
        positive(value.getGlobalMaxTasks(), "loom.agent.background-shell.global-max-tasks");
        positive(value.getPerRunMaxTasks(), "loom.agent.background-shell.per-run-max-tasks");
        positive(value.getIoThreads(), "loom.agent.background-shell.io-threads");
        positive(value.getForegroundYieldMs(), "loom.agent.background-shell.foreground-yield-ms");
        if (value.getPerRunMaxTasks() > value.getGlobalMaxTasks()) {
            fail("loom.agent.background-shell.per-run-max-tasks", "cannot exceed global-max-tasks");
        }
    }

    static void validateUndo(AgentRuntimeProperties agent) {
        var value = agent.getUndo();
        positive(value.getRetentionHours(), "loom.agent.undo.retention-hours");
        positive(value.getMaxChangedFiles(), "loom.agent.undo.max-changed-files");
        positive(value.getMaxChangedBytes(), "loom.agent.undo.max-changed-bytes");
        positive(value.getCommandTimeoutMs(), "loom.agent.undo.command-timeout-ms");
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
        String defaultModel = model.resolvedDefaultModel();
        String contextFallback = agent.getModelRecovery().getContextFallbackModel();
        if (contextFallback != null && !contextFallback.isBlank()) {
            if (!model.getAllowedModels().contains(contextFallback)) {
                fail("loom.agent.model-recovery.context-fallback-model",
                        "must be present in loom.ai.allowed-models");
            }
            Long currentLength = model.capability(defaultModel).getContextLength();
            Long fallbackLength = model.capability(contextFallback).getContextLength();
            if (currentLength == null || fallbackLength == null || fallbackLength <= currentLength) {
                fail("loom.agent.model-recovery.context-fallback-model",
                        "must have a larger context-length than the default model");
            }
        }
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

    private static void range(Integer value, int min, int max, String name) {
        if (value == null || value < min || value > max) fail(name, "must be in " + min + ".." + max);
    }

    private static void fail(String path, String message) {
        throw new IllegalStateException(path + " " + message);
    }
}
