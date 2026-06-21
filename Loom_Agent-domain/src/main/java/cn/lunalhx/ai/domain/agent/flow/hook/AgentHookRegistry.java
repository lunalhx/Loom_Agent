package cn.lunalhx.ai.domain.agent.flow.hook;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;

import java.util.ArrayList;
import java.util.List;

public class AgentHookRegistry {

    private final List<AgentHook> hooks;

    public AgentHookRegistry(List<AgentHook> hooks) {
        this.hooks = hooks == null ? List.of() : List.copyOf(hooks);
    }

    public static AgentHookRegistry empty() {
        return new AgentHookRegistry(List.of());
    }

    public List<AgentEvent> trigger(AgentHookEvent event, AgentHookContext context) {
        List<AgentEvent> events = new ArrayList<>();
        for (AgentHook hook : hooks) {
            List<AgentEvent> hookEvents = hook.onEvent(event, context);
            if (hookEvents != null && !hookEvents.isEmpty()) {
                events.addAll(hookEvents);
            }
        }
        return events;
    }

}
