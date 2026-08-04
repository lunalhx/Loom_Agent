package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;

public record AgentLoopAssembly(
        AgentRuntimeProperties properties,
        AgentFlowDefinition flow,
        AgentLoopComponents components
) {}
