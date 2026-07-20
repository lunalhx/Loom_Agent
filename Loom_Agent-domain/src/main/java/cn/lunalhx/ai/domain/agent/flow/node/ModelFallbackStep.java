package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

final class ModelFallbackStep implements ContextRecoveryStep {

    private final ModelRuntimeProperties properties;

    ModelFallbackStep(ModelRuntimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();
        ModelRuntimeProperties runProperties = context.modelRuntimeProperties(properties);
        String fallbackModel = runProperties.getResilience() == null
                ? null : runProperties.getResilience().getFallbackModel();
        String attemptedModel = request.attemptedModel();

        if (StringUtils.isBlank(fallbackModel) || fallbackModel.equals(attemptedModel)) {
            return ContextRecoveryTransition.continueChain();
        }

        context.setRecoveryModelOverride(fallbackModel);
        AgentEvent event = AgentEvent.builder()
                .type(AgentEventType.OBSERVATION)
                .code("model_fallback_for_error")
                .message("切换到 fallback 模型: " + fallbackModel)
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
