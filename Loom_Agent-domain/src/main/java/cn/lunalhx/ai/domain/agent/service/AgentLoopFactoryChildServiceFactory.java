package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;

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
