package cn.lunalhx.ai.domain.agent.service.execution;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.service.PermissionPrompt;

public record AgentLoopAssembly(
        AgentRuntimeProperties properties,
        AgentFlowDefinition flow,
        AgentLoopComponents components,
        PermissionPrompt permissionPrompt
) {}
