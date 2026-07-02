package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.context.ContextCompactResult;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.service.context.ContextWindowManager;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ReactiveCompactStep implements ContextRecoveryStep {

    private static final Set<ContextRecoveryStage> ACCEPTABLE_STATES =
            Set.of(ContextRecoveryStage.NONE);

    private final AgentRuntimeProperties properties;
    private final ContextWindowManager contextWindowManager;
    private final ModelGateway modelGateway;

    ReactiveCompactStep(AgentRuntimeProperties properties, ContextWindowManager contextWindowManager,
                        ModelGateway modelGateway) {
        this.properties = properties;
        this.contextWindowManager = contextWindowManager;
        this.modelGateway = modelGateway;
    }

    @Override
    public ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();
        if (!ACCEPTABLE_STATES.contains(request.stage())) {
            return ContextRecoveryTransition.continueChain();
        }
        if (!canReactiveCompact(context)) {
            return ContextRecoveryTransition.continueChain();
        }

        context.setReactiveCompactAttempts(context.getReactiveCompactAttempts() + 1);
        int targetTokens = targetTokens(request.attemptedModel(), request.requestedMaxTokens());
        ContextCompactResult compactResult = contextWindowManager.reactiveCompact(context, targetTokens);
        context.setContextRecoveryStage(ContextRecoveryStage.REACTIVE_COMPACTED);
        context.setContextTranscriptArtifactId(compactResult.getTranscriptArtifactId());

        AgentEvent event = compactEvent(context, compactResult,
                "Reactive context compact triggered by context length error");
        accumulatedEvents.add(event);

        if (compactResult.isFitsTarget()) {
            return ContextRecoveryTransition.renderPrompt(accumulatedEvents);
        }
        return ContextRecoveryTransition.continueChain();
    }

    private boolean canReactiveCompact(AgentContext context) {
        int maxAttempts = properties.getContext() == null || properties.getContext().getReactiveCompactMaxAttempts() == null
                ? 1
                : Math.max(0, properties.getContext().getReactiveCompactMaxAttempts());
        return context.getReactiveCompactAttempts() < maxAttempts;
    }

    private int targetTokens(String model, int requestedMaxTokens) {
        int configuredLimit = positive(contextProperties().getAutoCompactTokenLimit(), 64000);
        var capability = safeCapability(model);
        if (capability == null || capability.getContextLength() == null) {
            return configuredLimit;
        }
        int outputReserve = requestedMaxTokens > 0
                ? requestedMaxTokens
                : positive(properties.getBudget().getReservedOutputTokens(), 2048);
        int safetyMargin = positive(contextProperties().getContextSafetyMarginTokens(), 4096);
        long modelTarget = capability.getContextLength() - outputReserve - safetyMargin;
        return (int) Math.max(1L, Math.min(configuredLimit, modelTarget));
    }

    private cn.lunalhx.ai.domain.model.valobj.ModelCapability safeCapability(String model) {
        try {
            return modelGateway.capability(model);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private AgentRuntimeProperties.ContextProperties contextProperties() {
        if (properties.getContext() == null) {
            properties.setContext(new AgentRuntimeProperties.ContextProperties());
        }
        return properties.getContext();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private AgentEvent compactEvent(AgentContext context, ContextCompactResult result, String message) {
        return AgentEvent.builder()
                .type(AgentEventType.CONTEXT_COMPACTED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .message(message)
                .metadata(Map.of(
                        "beforeEstimatedTokens", result.getBeforeEstimatedTokens(),
                        "afterEstimatedTokens", result.getAfterEstimatedTokens(),
                        "targetTokens", result.getTargetTokens(),
                        "fitsTarget", result.isFitsTarget(),
                        "retainedEntryCount", result.getRetainedEntryCount(),
                        "strategies", result.getStrategies(),
                        "artifactCount", result.getArtifactCount(),
                        "attempt", context.getReactiveCompactAttempts(),
                        "transcriptArtifactId", StringUtils.defaultString(result.getTranscriptArtifactId())))
                .build();
    }
}
