package cn.lunalhx.ai.domain.agent.service.subagent;

import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.agent.service.execution.DefaultAgentLoopService;

interface ChildAgentServiceFactory {
    DefaultAgentLoopService create(ToolRegistry registry);
}
