package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.BudgetCheckResult;
import cn.lunalhx.ai.domain.agent.service.observability.ModelCallTraceContext;
import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelChatResult;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.Map;

final class ModelCallExecutor {

    private final ModelGateway modelGateway;
    private final ModelPromptFactory promptFactory;
    private final ModelCallBudgetCoordinator budgetCoordinator;

    ModelCallExecutor(ModelGateway modelGateway, ModelPromptFactory promptFactory,
                      ModelCallBudgetCoordinator budgetCoordinator) {
        this.modelGateway = modelGateway;
        this.promptFactory = promptFactory;
        this.budgetCoordinator = budgetCoordinator;
    }

    ModelCallResult execute(AgentContext context, String requestedModel, int requestedMaxTokens,
                            long deadlineEpochMs, int escalatedMaxTokens) {
        boolean escalated = false;
        int currentMaxTokens = requestedMaxTokens;

        while (true) {
            BudgetCheckResult check = budgetCoordinator.checkBeforeModelCall(context,
                    AgentNodeNames.MODEL_CALL, requestedModel, currentMaxTokens);
            if (!budgetCoordinator.isAllowed(check)) {
                return ModelCallResult.budgetBlocked(check);
            }

            ChatPrompt prompt = promptFactory.build(context, requestedModel, currentMaxTokens, deadlineEpochMs);
            ModelChatResult result;
            try (ModelCallTraceContext.Scope ignored = ModelCallTraceContext.open(context)) {
                long remainingMs = Math.max(1L, deadlineEpochMs - System.currentTimeMillis());
                result = modelGateway.complete(prompt)
                        .timeout(Duration.ofMillis(remainingMs))
                        .block(Duration.ofMillis(remainingMs + 100L));
            } catch (Exception e) {
                return ModelCallResult.error(e, requestedModel, currentMaxTokens);
            }

            if (result == null || StringUtils.isBlank(result.getContent())) {
                return ModelCallResult.error(new IllegalStateException("模型响应为空"), requestedModel, currentMaxTokens);
            }

            budgetCoordinator.recordUsage(context, AgentNodeNames.MODEL_CALL, result);

            if (isLength(result.getFinishReason())) {
                if (!escalated) {
                    escalated = true;
                    currentMaxTokens = escalatedMaxTokens;
                    budgetCoordinator.traceRecovery(context, "model_output_escalated", AgentNodeNames.MODEL_CALL,
                            Map.of("purpose", ModelCallPurpose.CONTROL_JSON.name(),
                                    "maxTokens", currentMaxTokens));
                    continue;
                }
                return ModelCallResult.truncationExhausted(result);
            }

            return ModelCallResult.success(result);
        }
    }

    private boolean isLength(String finishReason) {
        return "length".equalsIgnoreCase(StringUtils.trimToEmpty(finishReason))
                || "max_tokens".equalsIgnoreCase(StringUtils.trimToEmpty(finishReason));
    }
}
