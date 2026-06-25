package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;

interface ChildAgentServiceFactory {
    DefaultAgentLoopService create(ToolRegistry registry);
}
