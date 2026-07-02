package cn.lunalhx.ai.domain.agent.flow;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;

import java.util.List;

public interface AgentNode {

    String name();

    /**
     * Declares which state partitions this node reads and writes.
     * Defaults to {@link NodeAccess#NONE}.
     */
    default NodeAccess access() {
        return NodeAccess.NONE;
    }

    /**
     * Derives input keys from {@link #access()}. Override to restrict further.
     */
    default List<String> inputKeys() {
        return access().inputKeys();
    }

    NodeResult apply(AgentContext context);
}
