package cn.lunalhx.ai.domain.agent.flow.hook;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;

import java.util.List;

public record StopHookResult(
        List<AgentEvent> events,
        AgentHookAction action,
        boolean continued
) {
    public static StopHookResult proceed(List<AgentEvent> events) {
        return new StopHookResult(events == null ? List.of() : events, AgentHookAction.NONE, false);
    }

    public static StopHookResult continued(AgentHookAction action, List<AgentEvent> events) {
        return new StopHookResult(events == null ? List.of() : events, action, true);
    }
}
