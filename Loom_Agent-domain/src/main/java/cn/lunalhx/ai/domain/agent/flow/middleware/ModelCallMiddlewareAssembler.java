package cn.lunalhx.ai.domain.agent.flow.middleware;

import java.util.List;
import java.util.Objects;

public class ModelCallMiddlewareAssembler {

    private final SummarizationMiddleware summarizationMiddleware;
    private final DynamicContextMiddleware dynamicContextMiddleware;
    private final ErrorRecoveryMiddleware errorRecoveryMiddleware;
    private final BudgetMiddleware budgetMiddleware;

    public ModelCallMiddlewareAssembler(SummarizationMiddleware summarizationMiddleware,
                                        DynamicContextMiddleware dynamicContextMiddleware,
                                        ErrorRecoveryMiddleware errorRecoveryMiddleware,
                                        BudgetMiddleware budgetMiddleware) {
        this.summarizationMiddleware = Objects.requireNonNull(summarizationMiddleware, "summarizationMiddleware must not be null");
        this.dynamicContextMiddleware = Objects.requireNonNull(dynamicContextMiddleware, "dynamicContextMiddleware must not be null");
        this.errorRecoveryMiddleware = Objects.requireNonNull(errorRecoveryMiddleware, "errorRecoveryMiddleware must not be null");
        this.budgetMiddleware = Objects.requireNonNull(budgetMiddleware, "budgetMiddleware must not be null");
    }

    public ModelCallMiddlewareChain assemble(ModelCallNext terminal) {
        Objects.requireNonNull(terminal, "terminal must not be null");
        return new ModelCallMiddlewareChain(
                List.of(
                        summarizationMiddleware,
                        dynamicContextMiddleware,
                        errorRecoveryMiddleware,
                        budgetMiddleware
                ),
                terminal);
    }
}
