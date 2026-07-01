package cn.lunalhx.ai.domain.agent.service.subagent;

import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.execution.DefaultAgentLoopService;

class AgentLoopFactoryChildServiceFactory implements ChildAgentServiceFactory {

    private final AgentLoopFactory agentLoopFactory;

    AgentLoopFactoryChildServiceFactory(AgentLoopFactory agentLoopFactory) {
        this.agentLoopFactory = agentLoopFactory;
    }

    @Override
    public DefaultAgentLoopService create(ToolRegistry registry) {
        return agentLoopFactory.createChild(registry);
    }
}
