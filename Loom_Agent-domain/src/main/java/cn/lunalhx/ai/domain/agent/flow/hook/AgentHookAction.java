package cn.lunalhx.ai.domain.agent.flow.hook;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentHookAction {

    public static final AgentHookAction NONE = AgentHookAction.builder().type(Type.NONE).build();

    private final Type type;
    private final String nextNode;
    private final String reason;
    private final boolean clearTerminalState;

    public enum Type {
        NONE,
        CONTINUE_AT_NODE
    }

    public static AgentHookAction continueAt(String nextNode, String reason, boolean clearTerminalState) {
        return AgentHookAction.builder()
                .type(Type.CONTINUE_AT_NODE)
                .nextNode(nextNode)
                .reason(reason)
                .clearTerminalState(clearTerminalState)
                .build();
    }

}
