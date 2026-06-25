package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record AgentResumePlan(
        AgentContext context,
        String startNode,
        List<AgentEvent> initialEvents,
        boolean terminal
) {
    public AgentResumePlan {
        Objects.requireNonNull(initialEvents, "initialEvents must not be null");
        initialEvents = List.copyOf(initialEvents);
        if (!terminal) {
            Objects.requireNonNull(context, "context must not be null for non-terminal plan");
            Objects.requireNonNull(startNode, "startNode must not be null for non-terminal plan");
        }
    }

    public static AgentResumePlan continueAt(AgentContext context, String startNode, List<AgentEvent> events) {
        return new AgentResumePlan(context, startNode, new ArrayList<>(events), false);
    }

    public static AgentResumePlan complete(List<AgentEvent> events) {
        return new AgentResumePlan(null, null, new ArrayList<>(events), true);
    }
}
