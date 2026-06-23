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
        if (budgetGuard != null) {
            BudgetCheckResult check = budgetGuard.checkBeforeModelCall(context, name(), context.getCurrentPrompt());
            if (!check.isAllowed()) {
                blockForBudget(context, check);
                return NodeResult.next(AgentNodeNames.FAIL, List.of());
            }
        }
        try {
            ChatPrompt prompt = ChatPrompt.builder()
                    .requestId(context.getRequestId())
                    .conversationId(context.getConversationId())
                    .message(context.getCurrentPrompt())
                    .capability(ModelCapabilities.COMPLETE_AGENT_DECISION)
                    .outputFormat(OutputFormat.JSON_OBJECT)
                    .build();
            ModelChatResult result;
            try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
                result = modelGateway.complete(prompt)
                        .timeout(Duration.ofMillis(properties.getStepTimeoutMs()))
                        .block(Duration.ofMillis(properties.getStepTimeoutMs() + 1000L));
            }
            if (result == null || StringUtils.isBlank(result.getContent())) {
                throw new IllegalStateException("模型响应为空");
            }
            recordUsage(context, result);
            context.setModelOutput(result.getContent());
            return NodeResult.next(AgentNodeNames.DECISION, List.of());
        } catch (Exception e) {
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
                fail(context, AgentStopReason.BUDGET_EXCEEDED, "context_overflow", "模型上下文超限，压缩后仍无法完成调用");
                return NodeResult.next(AgentNodeNames.FAIL, List.of());
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

    private void recordUsage(AgentContext context, ModelChatResult result) {
        TraceCost cost = budgetGuard == null ? null : budgetGuard.recordModelUsage(context, result.getUsage());
        if (traceRecorder != null) {
            Map<String, Object> metadata = result.getUsage() == null
                    ? Map.of("usageMissing", true)
                    : Map.of("finishReason", StringUtils.defaultString(result.getFinishReason()));
            traceRecorder.recordModelUsage(context, name(), result.getUsage(), cost, metadata);
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
