package cn.lunalhx.ai.infrastructure.gateway.resilience;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelCapability;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.apache.commons.lang3.StringUtils;

import java.util.function.LongSupplier;

public final class ModelCallPreflight {

    private final ModelRuntimeProperties properties;
    private final ModelRequestNormalizer requestNormalizer;
    private final BudgetGuard budgetGuard;
    private final LongSupplier currentTimeMillis;

    ModelCallPreflight(ModelRuntimeProperties properties,
                       ModelRequestNormalizer requestNormalizer,
                       BudgetGuard budgetGuard,
                       LongSupplier currentTimeMillis) {
        this.properties = properties;
        this.requestNormalizer = requestNormalizer;
        this.budgetGuard = budgetGuard;
        this.currentTimeMillis = currentTimeMillis;
    }

    void validate(ChatPrompt prompt, ModelCallKey key, AgentContext context) {
        if (remainingMs(prompt) <= 0) {
            throw deadlineExceeded(key.model());
        }
        ModelCapability capability;
        try {
            capability = properties.capability(key.model());
        } catch (ModelGatewayException e) {
            throw e;
        }
        int requestedMaxTokens = prompt.getMaxTokens() == null
                ? requestNormalizer.defaultMaxTokens()
                : prompt.getMaxTokens();
        long estimatedPromptTokens = estimateTokens(prompt);
        if (requestedMaxTokens > capability.getMaxOutputTokens()) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_CAPABILITY_MISMATCH,
                    "模型输出能力不足：model=" + key.model() + ", requestedMaxTokens=" + requestedMaxTokens
                            + ", maxOutputTokens=" + capability.getMaxOutputTokens(),
                    false, null, null);
        }
        if (estimatedPromptTokens + requestedMaxTokens > capability.getContextLength()) {
            throw new ModelGatewayException(ModelErrorCode.CONTEXT_OVERFLOW,
                    "模型上下文长度超限：model=" + key.model() + ", promptTokens=" + estimatedPromptTokens
                            + ", requestedMaxTokens=" + requestedMaxTokens
                            + ", contextLength=" + capability.getContextLength(),
                    false, null, null, key.model(), null);
        }
        if (prompt.getPurpose() == ModelCallPurpose.CONTROL_JSON
                && !Boolean.TRUE.equals(capability.getSupportsJsonOutput())) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_CAPABILITY_MISMATCH,
                    "模型不支持 JSON 输出：" + key.model(), false, null, null);
        }
        if (prompt.getPurpose() == ModelCallPurpose.CONTROL_JSON
                && prompt.getOutputFormat() != cn.lunalhx.ai.domain.model.valobj.OutputFormat.JSON_OBJECT) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_CAPABILITY_MISMATCH,
                    "CONTROL_JSON 必须使用 JSON_OBJECT 输出格式", false, null, null);
        }
        if (ModelCapabilities.COMPLETE_AGENT_DECISION.equals(prompt.getCapability())
                && !Boolean.TRUE.equals(capability.getSupportsToolCalls())) {
            throw new ModelGatewayException(ModelErrorCode.MODEL_CAPABILITY_MISMATCH,
                    "模型不支持 Agent 工具决策：" + key.model(), false, null, null);
        }
        checkBudget(context, prompt, key.model(), requestedMaxTokens);
    }

    void validateFallbackBudget(AgentContext context, ChatPrompt prompt, String fallbackModel) {
        int maxTokens = prompt.getMaxTokens() == null
                ? requestNormalizer.defaultMaxTokens()
                : prompt.getMaxTokens();
        checkBudget(context, prompt, fallbackModel, maxTokens);
    }

    long remainingMs(ChatPrompt prompt) {
        if (prompt.getDeadlineEpochMs() == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, prompt.getDeadlineEpochMs() - currentTimeMillis.getAsLong());
    }

    boolean canWait(ChatPrompt prompt, long delayMs) {
        long remaining = remainingMs(prompt);
        return remaining == Long.MAX_VALUE || delayMs < remaining;
    }

    private void checkBudget(AgentContext context, ChatPrompt prompt, String model, int maxTokens) {
        if (budgetGuard == null || context == null) {
            return;
        }
        BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context,
                "model_gateway_preflight", model, prompt.getPurpose(), budgetInput(prompt), maxTokens);
        if (!check.isAllowed()) {
            throw new ModelGatewayException(ModelErrorCode.BUDGET_EXCEEDED,
                    "model call exceeds remaining budget: " + model, false, null, null);
        }
    }

    private long estimateTokens(ChatPrompt prompt) {
        return Math.max(1L,
                StringUtils.length(prompt.getMessage()) / 4L
                        + StringUtils.length(prompt.getSystemPrompt()) / 4L
                        + (prompt.getMessages() == null ? 0L : prompt.getMessages().stream()
                        .mapToLong(message -> StringUtils.length(message.getContent()) / 4L).sum()));
    }

    private String budgetInput(ChatPrompt prompt) {
        StringBuilder input = new StringBuilder(StringUtils.defaultString(prompt.getSystemPrompt()))
                .append(StringUtils.defaultString(prompt.getMessage()));
        if (prompt.getMessages() != null) {
            prompt.getMessages().forEach(message -> input.append(StringUtils.defaultString(message.getContent())));
        }
        return input.toString();
    }

    private ModelGatewayException deadlineExceeded(String model) {
        return new ModelGatewayException(ModelErrorCode.MODEL_CALL_TIMEOUT,
                ModelErrorCode.MODEL_CALL_TIMEOUT.message(), false, null, null, model, null);
    }

}
