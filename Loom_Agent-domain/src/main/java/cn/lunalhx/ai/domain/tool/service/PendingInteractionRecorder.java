package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import cn.lunalhx.ai.domain.agent.model.entity.PendingInteraction;

import java.util.List;

/** Persists an unanswered approval or user-input pause before interactive I/O. */
public interface PendingInteractionRecorder {

    PendingInteractionRecorder NOOP = new PendingInteractionRecorder() {
        @Override
        public List<AgentEvent> persistPending(AgentContext context, PendingInteraction pending) {
            return List.of();
        }

        @Override
        public List<AgentEvent> clearPending(AgentContext context) {
            return List.of();
        }
    };

    List<AgentEvent> persistPending(AgentContext context, PendingInteraction pending);

    List<AgentEvent> clearPending(AgentContext context);
}
