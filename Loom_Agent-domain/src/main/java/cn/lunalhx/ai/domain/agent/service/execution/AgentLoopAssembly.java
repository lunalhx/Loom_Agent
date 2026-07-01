package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.undo.UndoSessionCoordinator;

public record AgentLoopAssembly(
        AgentRuntimeProperties properties,
        AgentFlowDefinition flow,
        AgentLoopComponents components,
        UndoSessionCoordinator undoCoordinator
) {}
