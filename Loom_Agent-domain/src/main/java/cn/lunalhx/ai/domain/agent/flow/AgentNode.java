package cn.lunalhx.ai.domain.agent.flow;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;

import java.util.List;

public interface AgentNode {

    String name();

    List<String> inputKeys();

    NodeResult execute(AgentContext context);

}
