package cn.lunalhx.ai.domain.agent.flow;

import cn.lunalhx.ai.domain.agent.model.entity.AgentEvent;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

@Getter
@Builder
public class NodeResult {

    private final String nextNode;
    private final boolean terminal;
    @Builder.Default
    private final List<AgentEvent> events = Collections.emptyList();

    public static NodeResult next(String nextNode, List<AgentEvent> events) {
        return NodeResult.builder().nextNode(nextNode).events(events).build();
    }

    public static NodeResult terminal(List<AgentEvent> events) {
        return NodeResult.builder().terminal(true).events(events).build();
    }

}
