package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;

public record AgentLoopAssembly(
        AgentRuntimeProperties properties,
        AgentFlowDefinition flow,
        AgentLoopComponents components,
        UndoSessionCoordinator undoCoordinator
) {}
