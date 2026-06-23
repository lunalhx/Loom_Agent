package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;

public final class ModelCallTraceContext {

    private static final ThreadLocal<AgentContext> CURRENT = new ThreadLocal<>();

    private ModelCallTraceContext() {
    }

    public static AgentContext current() {
        return CURRENT.get();
    }

    public static Scope open(AgentContext context) {
        AgentContext previous = CURRENT.get();
        CURRENT.set(context);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public interface Scope extends AutoCloseable {

        @Override
        void close();

    }

}
