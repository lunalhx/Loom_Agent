package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason;

import java.util.List;

final class ModelErrorExhaustedStep implements ContextRecoveryStep {

    @Override
    public ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();
        context.runtime().fail(
                AgentStopReason.MODEL_ERROR,
                "model_error_recovery_exhausted",
                "模型决策失败，所有恢复策略已耗尽（格式提醒、模型切换、上下文精简）"
        );
        AgentEvent event = AgentEvent.builder()
                .type(AgentEventType.OBSERVATION)
                .code("model_error_recovery_exhausted")
                .message("所有 model_error 恢复策略已耗尽")
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .build();
        accumulatedEvents.add(event);
        return ContextRecoveryTransition.failContextOverflow(accumulatedEvents);
    }
}
