package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextCompactResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;

import java.util.List;

final class ContextSimplifyStep implements ContextRecoveryStep {

    private final ContextWindowManager contextWindowManager;

    ContextSimplifyStep(ContextWindowManager contextWindowManager) {
        this.contextWindowManager = contextWindowManager;
    }

    @Override
    public ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();
        int currentTokens = contextWindowManager.estimateTokens(context);
        int targetTokens = currentTokens / 2;
        if (targetTokens < 1000) {
            return ContextRecoveryTransition.continueChain();
        }

        ContextCompactResult result = contextWindowManager.reactiveCompact(context, targetTokens);
        if (!result.isCompacted()) {
            return ContextRecoveryTransition.continueChain();
        }

        AgentEvent event = AgentEvent.builder()
                .type(AgentEventType.OBSERVATION)
                .code("context_simplified_for_model_error")
                .message("已精简上下文（" + result.getBeforeEstimatedTokens() + " → " + result.getAfterEstimatedTokens() + " tokens），重试模型调用")
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .build();
        accumulatedEvents.add(event);
        return ContextRecoveryTransition.renderPrompt(accumulatedEvents);
    }
}
