package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;

import java.util.ArrayList;
import java.util.List;

final class ContextRecoveryChain {

    private final List<ContextRecoveryStep> steps;

    ContextRecoveryChain(List<ContextRecoveryStep> steps) {
        this.steps = List.copyOf(steps);
    }

    NodeResult execute(AgentContext context, String attemptedModel, int requestedMaxTokens, long deadlineEpochMs) {
        ContextRecoveryRequest request = new ContextRecoveryRequest(context, attemptedModel, requestedMaxTokens, deadlineEpochMs);
        List<AgentEvent> events = new ArrayList<>();

        for (ContextRecoveryStep step : steps) {
            ContextRecoveryTransition transition = step.apply(request, events);
            switch (transition.action()) {
                case RENDER_PROMPT:
                    return NodeResult.next(AgentNodeNames.RENDER_PROMPT, transition.events());
                case WAIT_USER_INPUT:
                    return NodeResult.next(AgentNodeNames.USER_INPUT_GATE, transition.events());
                case FAIL_CONTEXT_OVERFLOW:
                    return NodeResult.next(AgentNodeNames.FAIL, transition.events());
                case CONTINUE:
                    break;
            }
        }

        return NodeResult.next(AgentNodeNames.FAIL, events);
    }
}
