package cn.lunalhx.ai.domain.agent.flow;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;

import java.util.List;

public interface AgentNode {

    String name();

    /**
     * Declares the state partitions this node reads. Used for trace and
     * display; overrides may restrict further.
     */
    List<String> inputKeys();

    NodeResult apply(AgentContext context);
}