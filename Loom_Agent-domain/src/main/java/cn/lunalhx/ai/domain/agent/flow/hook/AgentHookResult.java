package cn.lunalhx.ai.domain.agent.flow.hook;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

@Data
@Builder
public class AgentHookResult {

    private final List<AgentEvent> events;
    private final AgentHookAction action;

    public static AgentHookResult proceed(List<AgentEvent> events) {
        return AgentHookResult.builder()
                .events(events == null ? List.of() : events)
                .action(AgentHookAction.NONE)
                .build();
    }

    public static AgentHookResult proceed() {
        return AgentHookResult.builder()
                .events(List.of())
                .action(AgentHookAction.NONE)
                .build();
    }

    public static AgentHookResult interrupt(AgentHookAction action) {
        return AgentHookResult.builder()
                .events(List.of())
                .action(action)
                .build();
    }

    public static AgentHookResult interrupt(AgentHookAction action, List<AgentEvent> events) {
        return AgentHookResult.builder()
                .events(events == null ? List.of() : events)
                .action(action)
                .build();
    }

    public boolean isContinue() {
        return action != null && action.getType() == AgentHookAction.Type.CONTINUE_AT_NODE;
    }

}
