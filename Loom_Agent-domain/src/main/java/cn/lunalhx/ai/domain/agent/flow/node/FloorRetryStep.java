package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentEventType;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;

import java.util.List;
import java.util.Map;

/**
 * Provider-context-overflow recovery: on the first overflow the other four
 * sections are pressed to their floors (current request preserved) and a single
 * retry is made. A second overflow routes to the user input gate.
 */
final class FloorRetryStep implements ContextRecoveryStep {

    private static final java.util.Set<ContextRecoveryStage> ACCEPTABLE_STATES =
            java.util.Set.of(ContextRecoveryStage.NONE, ContextRecoveryStage.FALLBACK_MODEL_SELECTED);

    private final AgentRuntimeProperties properties;
    private final ContextManager contextManager;

    FloorRetryStep(AgentRuntimeProperties properties, ContextManager contextManager) {
        this.properties = properties;
        this.contextManager = contextManager;
    }

    @Override
    public ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents) {
        AgentContext context = request.context();
        if (!ACCEPTABLE_STATES.contains(request.stage())) {
            return ContextRecoveryTransition.continueChain();
        }
        if (context.isFloorRetryPending()) {
            // Already attempted floor retry once. Child agents must fail instead
            // of waiting for user input; the root agent routes to the input gate.
            if (context.getParentRunId() == null) {
                context.setContextRecoveryStage(ContextRecoveryStage.WAITING_USER_INPUT);
                context.setContextBlockedReason("context_overflow: floor retry exhausted");
                return ContextRecoveryTransition.waitUserInput(accumulatedEvents);
            }
            context.runtime().fail(cn.lunalhx.ai.domain.agent.model.valobj.AgentStopReason.CONTEXT_OVERFLOW,
                    cn.lunalhx.ai.domain.model.valobj.ModelErrorCode.CONTEXT_OVERFLOW.code(),
                    "模型上下文超限，floor 重试仍未恢复");
            return ContextRecoveryTransition.failContextOverflow(accumulatedEvents);
        }

        // Build the floor-pressed view and mark the pending retry.
        contextManager.buildFloorPressed(context);
        context.setFloorRetryPending(true);

        AgentEvent event = AgentEvent.builder()
                .type(AgentEventType.CONTEXT_COMPACTED)
                .runId(context.getRunId())
                .requestId(context.getRequestId())
                .conversationId(context.getConversationId())
                .workspace(context.getWorkspaceDisplayName())
                .parentRunId(context.getParentRunId())
                .message("Context pressed to floors for overflow retry")
                .metadata(Map.of("mode", "render_only", "budgetReductions", List.of("floor_pressure")))
                .build();
        accumulatedEvents.add(event);
        return ContextRecoveryTransition.renderPrompt(accumulatedEvents);
    }
}
