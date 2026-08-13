package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.flow.AgentNodeNames;
import cn.lunalhx.ai.domain.agent.flow.NodeResult;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;

import java.util.ArrayList;
import java.util.List;

public class ContextOverflowChain {

    private final List<ContextOverflowStep> steps;

    ContextOverflowChain(List<ContextOverflowStep> steps) {
        this.steps = List.copyOf(steps);
    }

    public NodeResult execute(AgentContext context, String attemptedModel, int requestedMaxTokens, long deadlineEpochMs) {
        ContextOverflowRequest request = new ContextOverflowRequest(context, attemptedModel, requestedMaxTokens, deadlineEpochMs);
        List<AgentEvent> events = new ArrayList<>();

        for (ContextOverflowStep step : steps) {
            ContextOverflowTransition transition = step.apply(request, events);
            switch (transition.action()) {
                case RENDER_PROMPT:
                    return NodeResult.nextRound(transition.events());
                case WAIT_USER_INPUT:
                    return NodeResult.pauseUserInput(transition.events());
                case FAIL_CONTEXT_OVERFLOW:
                    return NodeResult.fail(transition.events());
                case CONTINUE:
                    break;
            }
        }

        return NodeResult.fail(events);
    }
}