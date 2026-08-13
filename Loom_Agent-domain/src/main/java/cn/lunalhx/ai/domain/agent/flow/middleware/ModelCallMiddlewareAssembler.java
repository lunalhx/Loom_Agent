package cn.lunalhx.ai.domain.agent.flow.middleware;

import java.util.List;
import java.util.Objects;

/**
 * Assembles the model-call middleware chain.
 *
 * <p>Context rebuild, reduction, and dynamic control-info writing now happen in
 * {@code PromptBuildNode}; this chain only handles budget enforcement and
 * model-call error handling around the terminal model call.
 */
public class ModelCallMiddlewareAssembler {

    private final ModelCallErrorMiddleware modelCallErrorMiddleware;
    private final BudgetMiddleware budgetMiddleware;

    public ModelCallMiddlewareAssembler(ModelCallErrorMiddleware modelCallErrorMiddleware,
                                        BudgetMiddleware budgetMiddleware) {
        this.modelCallErrorMiddleware = Objects.requireNonNull(modelCallErrorMiddleware, "modelCallErrorMiddleware must not be null");
        this.budgetMiddleware = Objects.requireNonNull(budgetMiddleware, "budgetMiddleware must not be null");
    }

    public ModelCallMiddlewareChain assemble(ModelCallNext terminal) {
        Objects.requireNonNull(terminal, "terminal must not be null");
        return new ModelCallMiddlewareChain(
                List.of(
                        modelCallErrorMiddleware,
                        budgetMiddleware
                ),
                terminal);
    }
}
