package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;

interface ContextCompactionStrategy {
    ContextStrategyResult compact(AgentContext context, ContextCompactionCommand command);
}
