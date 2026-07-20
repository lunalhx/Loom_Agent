package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelCapability;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class FallbackModelStep implements ContextRecoveryStep {

    private static final Set<ContextRecoveryStage> ACCEPTABLE_STATES =
            Set.of(ContextRecoveryStage.NONE, ContextRecoveryStage.REACTIVE_COMPACTED);

    private final AgentRuntimeProperties properties;
    private final ModelGateway modelGateway;
    private final ModelCallBudgetCoordinator budgetCoordinator;

    FallbackModelStep(AgentRuntimeProperties properties, ModelGateway modelGateway,
                      ModelCallBudgetCoordinator budgetCoordinator) {
        this.properties = properties;
        this.modelGateway = modelGateway;
        this.budgetCoordinator = budgetCoordinator;
    }

    @Override
    public ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();
        if (!ACCEPTABLE_STATES.contains(request.stage())) {
            return ContextRecoveryTransition.continueChain();
        }

        String fallbackModel = selectFallbackModel(request.attemptedModel(), request.requestedMaxTokens(), context);
        if (StringUtils.isBlank(fallbackModel)) {
            return ContextRecoveryTransition.continueChain();
        }

        context.setRecoveryModelOverride(fallbackModel);
        context.setFallbackReason("context_overflow");
        context.setContextRecoveryStage(ContextRecoveryStage.FALLBACK_MODEL_SELECTED);
        budgetCoordinator.traceRecovery(context, "model_context_fallback_selected", AgentNodeNames.MODEL_CALL,
                Map.of("model", fallbackModel, "reason", "context_overflow"));
        return ContextRecoveryTransition.renderPrompt(accumulatedEvents);
    }

    private String selectFallbackModel(String attemptedModel, int requestedMaxTokens, AgentContext context) {
        AgentRuntimeProperties runProperties = context.runtimeProperties(properties);
        String fallbackModel = runProperties.getModelRecovery() == null
                ? null : runProperties.getModelRecovery().getContextFallbackModel();
        if (StringUtils.isBlank(fallbackModel)
                || !canUseContextFallback(attemptedModel, fallbackModel, context)) {
            return null;
        }
        if (!budgetCoordinator.checkFallbackModelBudget(context, AgentNodeNames.MODEL_CALL,
                fallbackModel, requestedMaxTokens)) {
            return null;
        }
        return fallbackModel;
    }

    private boolean canUseContextFallback(String currentModel, String fallbackModel,
                                          AgentContext context) {
        if (StringUtils.equals(currentModel, fallbackModel)) {
            return false;
        }
        ModelCapability current;
        ModelCapability fallback;
        try {
            ModelRuntimeProperties runModel = context.modelRuntimeProperties(null);
            String effectiveCurrentModel = runModel == null
                    ? currentModel : StringUtils.defaultIfBlank(currentModel, runModel.resolvedDefaultModel());
            current = runModel == null
                    ? modelGateway.capability(effectiveCurrentModel) : runModel.capability(effectiveCurrentModel);
            fallback = runModel == null
                    ? modelGateway.capability(fallbackModel) : runModel.capability(fallbackModel);
        } catch (RuntimeException e) {
            return false;
        }
        return current != null && fallback != null
                && current.getContextLength() != null
                && fallback.getContextLength() != null
                && fallback.getContextLength() > current.getContextLength();
    }
}
