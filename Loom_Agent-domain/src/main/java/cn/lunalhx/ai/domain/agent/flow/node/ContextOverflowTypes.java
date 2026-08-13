package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextOverflowStage;

import java.util.Collections;
import java.util.List;

final class ContextOverflowRequest {

    private final AgentContext context;
    private final String attemptedModel;
    private final int requestedMaxTokens;
    private final long deadlineEpochMs;

    ContextOverflowRequest(AgentContext context, String attemptedModel, int requestedMaxTokens, long deadlineEpochMs) {
        this.context = context;
        this.attemptedModel = attemptedModel;
        this.requestedMaxTokens = requestedMaxTokens;
        this.deadlineEpochMs = deadlineEpochMs;
    }

    AgentContext context() { return context; }
    String attemptedModel() { return attemptedModel; }
    int requestedMaxTokens() { return requestedMaxTokens; }
    long deadlineEpochMs() { return deadlineEpochMs; }

    ContextOverflowStage stage() {
        return context.getContextOverflowStage() == null
                ? ContextOverflowStage.NONE : context.getContextOverflowStage();
    }
}

final class ContextOverflowTransition {

    enum Action {
        RENDER_PROMPT,
        CONTINUE,
        WAIT_USER_INPUT,
        FAIL_CONTEXT_OVERFLOW,
    }

    private final Action action;
    private final List<AgentEvent> events;

    private ContextOverflowTransition(Action action, List<AgentEvent> events) {
        this.action = action;
        this.events = events;
    }

    static ContextOverflowTransition renderPrompt(List<AgentEvent> events) {
        return new ContextOverflowTransition(Action.RENDER_PROMPT, events);
    }

    static ContextOverflowTransition continueChain() {
        return new ContextOverflowTransition(Action.CONTINUE, Collections.emptyList());
    }

    static ContextOverflowTransition waitUserInput(List<AgentEvent> events) {
        return new ContextOverflowTransition(Action.WAIT_USER_INPUT, events);
    }

    static ContextOverflowTransition failContextOverflow(List<AgentEvent> events) {
        return new ContextOverflowTransition(Action.FAIL_CONTEXT_OVERFLOW, events);
    }

    Action action() { return action; }
    List<AgentEvent> events() { return events; }
}

interface ContextOverflowStep {

    ContextOverflowTransition apply(ContextOverflowRequest request, List<AgentEvent> accumulatedEvents);
}
