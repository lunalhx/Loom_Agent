package cn.lunalhx.ai.domain.agent.flow.middleware;

@FunctionalInterface
public interface ModelCallNext {
    ModelCallOutcome invoke(ModelCallContext ctx);
}
