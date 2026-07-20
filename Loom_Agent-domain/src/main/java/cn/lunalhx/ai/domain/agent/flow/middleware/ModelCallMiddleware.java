package cn.lunalhx.ai.domain.agent.flow.middleware;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;

@FunctionalInterface
public interface ModelCallMiddleware {
    ModelCallOutcome apply(ModelCallContext ctx, ModelCallNext next);
}
