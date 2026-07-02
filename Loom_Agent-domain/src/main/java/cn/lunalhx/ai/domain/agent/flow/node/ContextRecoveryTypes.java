package cn.lunalhx.ai.domain.agent.flow.node;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.valobj.ContextRecoveryStage;

import java.util.Collections;
import java.util.List;

final class ContextRecoveryRequest {

    private final AgentContext context;
    private final String attemptedModel;
    private final int requestedMaxTokens;
    private final long deadlineEpochMs;

    ContextRecoveryRequest(AgentContext context, String attemptedModel, int requestedMaxTokens, long deadlineEpochMs) {
        this.context = context;
        this.attemptedModel = attemptedModel;
        this.requestedMaxTokens = requestedMaxTokens;
        this.deadlineEpochMs = deadlineEpochMs;
    }

    AgentContext context() { return context; }
    String attemptedModel() { return attemptedModel; }
    int requestedMaxTokens() { return requestedMaxTokens; }
    long deadlineEpochMs() { return deadlineEpochMs; }

    ContextRecoveryStage stage() {
        return context.getContextRecoveryStage() == null
                ? ContextRecoveryStage.NONE : context.getContextRecoveryStage();
    }
}

final class ContextRecoveryTransition {

    enum Action {
        RENDER_PROMPT,
        CONTINUE,
        WAIT_USER_INPUT,
        FAIL_CONTEXT_OVERFLOW,
    }

    private final Action action;
    private final List<AgentEvent> events;

    private ContextRecoveryTransition(Action action, List<AgentEvent> events) {
        this.action = action;
        this.events = events;
    }

    static ContextRecoveryTransition renderPrompt(List<AgentEvent> events) {
        return new ContextRecoveryTransition(Action.RENDER_PROMPT, events);
    }

    static ContextRecoveryTransition continueChain() {
        return new ContextRecoveryTransition(Action.CONTINUE, Collections.emptyList());
    }

    static ContextRecoveryTransition waitUserInput(List<AgentEvent> events) {
        return new ContextRecoveryTransition(Action.WAIT_USER_INPUT, events);
    }

    static ContextRecoveryTransition failContextOverflow(List<AgentEvent> events) {
        return new ContextRecoveryTransition(Action.FAIL_CONTEXT_OVERFLOW, events);
    }

    Action action() { return action; }
    List<AgentEvent> events() { return events; }
}

interface ContextRecoveryStep {

    ContextRecoveryTransition apply(ContextRecoveryRequest request, List<AgentEvent> accumulatedEvents);
}
