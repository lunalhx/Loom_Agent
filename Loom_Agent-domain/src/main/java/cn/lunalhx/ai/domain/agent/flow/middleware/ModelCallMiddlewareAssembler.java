package cn.lunalhx.ai.domain.agent.flow.middleware;

import java.util.List;
import java.util.Objects;

/**
 * Assembles the model-call middleware chain.
 *
 * <p>Context rebuild, reduction, and dynamic control-info writing now happen in
 * {@code PromptBuildNode}; this chain only handles budget enforcement and
 * model-level error recovery around the terminal model call.
 */
public class ModelCallMiddlewareAssembler {

    private final ErrorRecoveryMiddleware errorRecoveryMiddleware;
    private final BudgetMiddleware budgetMiddleware;

    public ModelCallMiddlewareAssembler(ErrorRecoveryMiddleware errorRecoveryMiddleware,
                                        BudgetMiddleware budgetMiddleware) {
        this.errorRecoveryMiddleware = Objects.requireNonNull(errorRecoveryMiddleware, "errorRecoveryMiddleware must not be null");
        this.budgetMiddleware = Objects.requireNonNull(budgetMiddleware, "budgetMiddleware must not be null");
    }

    public ModelCallMiddlewareChain assemble(ModelCallNext terminal) {
        Objects.requireNonNull(terminal, "terminal must not be null");
        return new ModelCallMiddlewareChain(
                List.of(
                        errorRecoveryMiddleware,
                        budgetMiddleware
                ),
                terminal);
    }
}