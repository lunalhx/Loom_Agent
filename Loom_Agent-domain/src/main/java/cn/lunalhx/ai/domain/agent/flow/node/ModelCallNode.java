package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.adapter.port.BudgetGuard;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.flow.AbstractAgentNode;
import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextCompactResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;
import cn.lunalhx.ai.domain.agent.model.valobj.TraceCost;
import cn.lunalhx.ai.domain.agent.service.ContextWindowManager;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelCapability;
import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import cn.lunalhx.ai.domain.agent.service.ModelCallTraceContext;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class ModelCallNode extends AbstractAgentNode {

    private final ModelGateway modelGateway;
    private final AgentRuntimeProperties properties;
    private final TraceRecorder traceRecorder;
    private final BudgetGuard budgetGuard;
    private final ContextWindowManager contextWindowManager;

    public ModelCallNode(ModelGateway modelGateway, AgentRuntimeProperties properties) {
        this(modelGateway, properties, null, null);
    }

    public ModelCallNode(ModelGateway modelGateway,
                         AgentRuntimeProperties properties,
                         TraceRecorder traceRecorder,
                         BudgetGuard budgetGuard) {
        this(modelGateway, properties, traceRecorder, budgetGuard, ContextWindowManager.noop(properties));
    }

    public ModelCallNode(ModelGateway modelGateway,
                         AgentRuntimeProperties properties,
                         TraceRecorder traceRecorder,
                         BudgetGuard budgetGuard,
                         ContextWindowManager contextWindowManager) {
        super(AgentNodeNames.MODEL_CALL, List.of("currentPrompt", "requestId", "conversationId"));
        this.modelGateway = modelGateway;
        this.properties = properties;
        this.traceRecorder = traceRecorder;
        this.budgetGuard = budgetGuard;
        this.contextWindowManager = contextWindowManager == null ? ContextWindowManager.noop(properties) : contextWindowManager;
    }

    @Override
    protected NodeResult doApply(AgentContext context) {
        long deadlineEpochMs = System.currentTimeMillis() + properties.getStepTimeoutMs();
        int requestedMaxTokens = 0;
        boolean escalated = false;
        boolean contextFallbackAttempted = false;
        String requestedModel = null;
        try {
            while (true) {
                if (budgetGuard != null) {
                    BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context, name(), requestedModel,
                            ModelCallPurpose.CONTROL_JSON, context.getCurrentPrompt(), requestedMaxTokens);
                    if (!check.isAllowed()) {
                        blockForBudget(context, check);
                        return NodeResult.next(AgentNodeNames.FAIL, List.of());
                    }
                }
                ChatPrompt prompt = ChatPrompt.builder()
                        .requestId(context.getRequestId())
                        .conversationId(context.getConversationId())
                        .message(context.getCurrentPrompt())
                        .model(requestedModel)
                        .maxTokens(requestedMaxTokens <= 0 ? null : requestedMaxTokens)
                        .capability(ModelCapabilities.COMPLETE_AGENT_DECISION)
                        .purpose(ModelCallPurpose.CONTROL_JSON)
                        .deadlineEpochMs(deadlineEpochMs)
                        .outputFormat(OutputFormat.JSON_OBJECT)
                        .build();
                ModelChatResult result;
                try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
                    long remainingMs = Math.max(1L, deadlineEpochMs - System.currentTimeMillis());
                    result = modelGateway.complete(prompt)
                            .timeout(Duration.ofMillis(remainingMs))
                            .block(Duration.ofMillis(remainingMs + 100L));
                }
                if (result == null || StringUtils.isBlank(result.getContent())) {
                    throw new IllegalStateException("模型响应为空");
                }
                recordUsage(context, result);
                context.setCurrentModel(result.getActualModel());
                context.setFallbackReason(result.getFallbackReason());
                if (isLength(result.getFinishReason())) {
                    if (!escalated) {
                        escalated = true;
                        requestedMaxTokens = escalatedMaxTokens();
                        traceRecovery(context, "model_output_escalated",
                                Map.of("purpose", ModelCallPurpose.CONTROL_JSON.name(),
                                        "maxTokens", requestedMaxTokens));
                        continue;
                    }
                    fail(context, AgentStopReason.MODEL_ERROR,
                            ModelErrorCode.MODEL_DECISION_TRUNCATED.code(),
                            ModelErrorCode.MODEL_DECISION_TRUNCATED.message());
                    traceRecovery(context, "model_recovery_exhausted",
                            Map.of("purpose", ModelCallPurpose.CONTROL_JSON.name(),
                                    "finishReason", StringUtils.defaultString(result.getFinishReason())));
                    return NodeResult.next(AgentNodeNames.FAIL, List.of());
                }
                context.setModelOutput(result.getContent());
                return NodeResult.next(AgentNodeNames.DECISION, List.of());
            }
        } catch (Exception e) {
            if (hasErrorCode(e, ModelErrorCode.BUDGET_EXCEEDED)) {
                context.setBudgetBlockedReason("fallback model exceeds remaining budget");
                fail(context, AgentStopReason.BUDGET_EXCEEDED,
                        ModelErrorCode.BUDGET_EXCEEDED.code(), ModelErrorCode.BUDGET_EXCEEDED.message());
                return NodeResult.next(AgentNodeNames.FAIL, List.of());
            }
            if (hasErrorCode(e, ModelErrorCode.MODEL_CALL_TIMEOUT)) {
                fail(context, AgentStopReason.TIMEOUT,
                        ModelErrorCode.MODEL_CALL_TIMEOUT.code(), ModelErrorCode.MODEL_CALL_TIMEOUT.message());
                return NodeResult.next(AgentNodeNames.FAIL, List.of());
            }
            String attemptedModel = attemptedModel(e);
            String contextFallbackModel = properties.getModelRecovery().getContextFallbackModel();
            if (isContextOverflow(e) && !contextFallbackAttempted
                    && StringUtils.isNotBlank(contextFallbackModel)
                    && canUseContextFallback(attemptedModel, contextFallbackModel)) {
                contextFallbackAttempted = true;
                context.setCurrentModel(contextFallbackModel);
                context.setFallbackReason("context_overflow");
                return retryContextFallback(context, deadlineEpochMs, escalated, requestedMaxTokens);
            }
            if (isContextOverflow(e) && canReactiveCompact(context)) {
                context.setReactiveCompactAttempts(context.getReactiveCompactAttempts() + 1);
                ContextCompactResult compactResult = contextWindowManager.reactiveCompact(context);
                AgentEvent event = event(context, AgentEventType.CONTEXT_COMPACTED)
                        .message("Reactive context compact triggered by provider context length error")
                        .metadata(Map.of(
                                "beforeEstimatedTokens", compactResult.getBeforeEstimatedTokens(),
                                "afterEstimatedTokens", compactResult.getAfterEstimatedTokens(),
                                "strategies", compactResult.getStrategies(),
                                "artifactCount", compactResult.getArtifactCount(),
                                "attempt", context.getReactiveCompactAttempts()))
                        .build();
                return NodeResult.next(AgentNodeNames.RENDER_PROMPT, List.of(event));
            }
            if (isContextOverflow(e)) {
                fail(context, AgentStopReason.MODEL_ERROR, "context_overflow", "模型上下文超限，压缩后仍无法完成调用");
                return NodeResult.next(AgentNodeNames.FAIL, List.of());
            }
            fail(context, AgentStopReason.MODEL_ERROR, "model_error", "模型决策失败");
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }
    }

    private NodeResult retryContextFallback(AgentContext context,
                                            long deadlineEpochMs,
                                            boolean escalated,
                                            int requestedMaxTokens) {
        try {
            int maxTokens = escalated ? requestedMaxTokens : 0;
            ModelChatResult result = null;
            for (int attempt = 0; attempt < (escalated ? 1 : 2); attempt++) {
                if (budgetGuard != null) {
                    BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context, name(), context.getCurrentModel(),
                            ModelCallPurpose.CONTROL_JSON, context.getCurrentPrompt(), maxTokens);
                    if (!check.isAllowed()) {
                        blockForBudget(context, check);
                        return NodeResult.next(AgentNodeNames.FAIL, List.of());
                    }
                }
                ChatPrompt prompt = ChatPrompt.builder()
                        .requestId(context.getRequestId())
                        .conversationId(context.getConversationId())
                        .message(context.getCurrentPrompt())
                        .model(context.getCurrentModel())
                        .maxTokens(maxTokens <= 0 ? null : maxTokens)
                        .capability(ModelCapabilities.COMPLETE_AGENT_DECISION)
                        .purpose(ModelCallPurpose.CONTROL_JSON)
                        .deadlineEpochMs(deadlineEpochMs)
                        .outputFormat(OutputFormat.JSON_OBJECT)
                        .build();
                try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
                    long remainingMs = Math.max(1L, deadlineEpochMs - System.currentTimeMillis());
                    result = modelGateway.complete(prompt)
                            .timeout(Duration.ofMillis(remainingMs))
                            .block(Duration.ofMillis(remainingMs + 100L));
                }
                if (result == null || !isLength(result.getFinishReason())) {
                    break;
                }
                recordUsage(context, result);
                maxTokens = escalatedMaxTokens();
            }
            if (result == null || StringUtils.isBlank(result.getContent())) {
                throw new IllegalStateException("模型响应为空");
            }
            if (isLength(result.getFinishReason())) {
                fail(context, AgentStopReason.MODEL_ERROR,
                        ModelErrorCode.MODEL_DECISION_TRUNCATED.code(),
                        ModelErrorCode.MODEL_DECISION_TRUNCATED.message());
                return NodeResult.next(AgentNodeNames.FAIL, List.of());
            }
            recordUsage(context, result);
            context.setCurrentModel(result.getActualModel());
            context.setModelOutput(result.getContent());
            traceRecovery(context, "model_context_fallback",
                    Map.of("model", StringUtils.defaultString(context.getCurrentModel()),
                            "reason", "context_overflow"));
            return NodeResult.next(AgentNodeNames.DECISION, List.of());
        } catch (Exception fallbackError) {
            if (isContextOverflow(fallbackError) && canReactiveCompact(context)) {
                context.setReactiveCompactAttempts(context.getReactiveCompactAttempts() + 1);
                ContextCompactResult compactResult = contextWindowManager.reactiveCompact(context);
                AgentEvent event = event(context, AgentEventType.CONTEXT_COMPACTED)
                        .message("Reactive context compact triggered after context fallback failed")
                        .metadata(Map.of(
                                "beforeEstimatedTokens", compactResult.getBeforeEstimatedTokens(),
                                "afterEstimatedTokens", compactResult.getAfterEstimatedTokens(),
                                "strategies", compactResult.getStrategies(),
                                "artifactCount", compactResult.getArtifactCount(),
                                "attempt", context.getReactiveCompactAttempts()))
                        .build();
                return NodeResult.next(AgentNodeNames.RENDER_PROMPT, List.of(event));
            }
            fail(context, AgentStopReason.MODEL_ERROR, "model_error", "模型决策失败");
            return NodeResult.next(AgentNodeNames.FAIL, List.of());
        }
    }

    private boolean canReactiveCompact(AgentContext context) {
        int maxAttempts = properties.getContext() == null || properties.getContext().getReactiveCompactMaxAttempts() == null
                ? 1
                : Math.max(0, properties.getContext().getReactiveCompactMaxAttempts());
        return context.getReactiveCompactAttempts() < maxAttempts;
    }

    private boolean isContextOverflow(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelGatewayException exception
                    && exception.getErrorCode() == ModelErrorCode.CONTEXT_OVERFLOW) {
                return true;
            }
            String message = StringUtils.lowerCase(current.getMessage());
            if (StringUtils.contains(message, "prompt_too_long")
                    || StringUtils.contains(message, "context_length_exceeded")
                    || StringUtils.contains(message, "too many tokens")
                    || StringUtils.contains(message, "context length")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean hasErrorCode(Throwable throwable, ModelErrorCode errorCode) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelGatewayException exception
                    && exception.getErrorCode() == errorCode) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String attemptedModel(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelGatewayException exception
                    && StringUtils.isNotBlank(exception.getModel())) {
                return exception.getModel();
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean canUseContextFallback(String currentModel, String fallbackModel) {
        if (StringUtils.isBlank(currentModel) || StringUtils.equals(currentModel, fallbackModel)) {
            return false;
        }
        ModelCapability current = modelGateway.capability(currentModel);
        ModelCapability fallback = modelGateway.capability(fallbackModel);
        return current != null && fallback != null
                && current.getContextLength() != null
                && fallback.getContextLength() != null
                && fallback.getContextLength() > current.getContextLength();
    }

    private void recordUsage(AgentContext context, ModelChatResult result) {
        TraceCost cost = budgetGuard == null ? null
                : budgetGuard.recordModelUsage(context, result.getActualModel(), result.getUsage());
        if (traceRecorder != null) {
            Map<String, Object> metadata = result.getUsage() == null
                    ? Map.of("usageMissing", true)
                    : Map.of("finishReason", StringUtils.defaultString(result.getFinishReason()));
            traceRecorder.recordModelUsage(context, name(), result.getUsage(), cost, metadata);
        }
    }

    private boolean isLength(String finishReason) {
        return "length".equalsIgnoreCase(StringUtils.trimToEmpty(finishReason))
                || "max_tokens".equalsIgnoreCase(StringUtils.trimToEmpty(finishReason));
    }

    private int escalatedMaxTokens() {
        Integer value = properties.getModelRecovery() == null
                ? null : properties.getModelRecovery().getEscalatedMaxTokens();
        return value == null || value <= 0 ? 64000 : value;
    }

    private void traceRecovery(AgentContext context, String eventType, Map<String, Object> metadata) {
        if (traceRecorder != null) {
            traceRecorder.recordModelGatewayEvent(context, eventType, name(), "success", 0L,
                    eventType, null, metadata);
        }
    }

    private void blockForBudget(AgentContext context, BudgetCheckResult check) {
        String reason = "budget_exceeded: usedTokens=" + check.getUsedTokens()
                + ", estimatedInputTokens=" + check.getEstimatedInputTokens()
                + ", reservedOutputTokens=" + check.getReservedOutputTokens()
                + ", maxTotalTokens=" + check.getMaxTotalTokens();
        context.setBudgetBlockedReason(reason);
        fail(context, AgentStopReason.BUDGET_EXCEEDED, "budget_exceeded", reason);
    }

}
