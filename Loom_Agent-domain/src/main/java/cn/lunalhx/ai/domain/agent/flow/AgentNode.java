package cn.lunalhx.ai.domain.agent.flow;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;

public interface AgentNode {

    String name();

    NodeResult execute(AgentContext context);

}
