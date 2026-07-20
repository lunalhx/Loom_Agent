package cn.lunalhx.ai.domain.agent.flow.middleware;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ModelCallMiddlewareChain {
    private final List<ModelCallMiddleware> middlewares;
    private final ModelCallNext terminal;

    public ModelCallMiddlewareChain(List<ModelCallMiddleware> middlewares, ModelCallNext terminal) {
        this.middlewares = Collections.unmodifiableList(new ArrayList<>(middlewares));
        this.terminal = Objects.requireNonNull(terminal);
    }

    public ModelCallOutcome execute(ModelCallContext ctx) {
        return new ChainInvocation(middlewares, 0, terminal).invoke(ctx);
    }

    private static class ChainInvocation implements ModelCallNext {
        private final List<ModelCallMiddleware> middlewares;
        private final int index;
        private final ModelCallNext terminal;

        ChainInvocation(List<ModelCallMiddleware> middlewares, int index, ModelCallNext terminal) {
            this.middlewares = middlewares;
            this.index = index;
            this.terminal = terminal;
        }

        @Override
        public ModelCallOutcome invoke(ModelCallContext ctx) {
            if (index >= middlewares.size()) {
                return terminal.invoke(ctx);
            }
            return middlewares.get(index).apply(ctx,
                    new ChainInvocation(middlewares, index + 1, terminal));
        }
    }
}
