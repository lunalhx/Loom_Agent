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
            AgentHookResult result = hook.onEvent(event, context);
            if (result != null) {
                if (result.getEvents() != null && !result.getEvents().isEmpty()) {
                    events.addAll(result.getEvents());
                }
            }
        }
        return events;
    }

    /**
     * Trigger hooks with stop-hook semantics: collect events from all hooks,
     * but if any hook returns {@link AgentHookAction.Type#CONTINUE_AT_NODE},
     * return that action immediately (short-circuit) along with events collected so far.
     */
    public StopHookResult triggerStop(AgentHookEvent event, AgentHookContext context) {
        List<AgentEvent> events = new ArrayList<>();
        for (AgentHook hook : hooks) {
            AgentHookResult result = hook.onEvent(event, context);
            if (result != null) {
                if (result.getEvents() != null && !result.getEvents().isEmpty()) {
                    events.addAll(result.getEvents());
                }
                if (result.isContinue()) {
                    return StopHookResult.continued(result.getAction(), events);
                }
            }
        }
        return StopHookResult.proceed(events);
    }

}
