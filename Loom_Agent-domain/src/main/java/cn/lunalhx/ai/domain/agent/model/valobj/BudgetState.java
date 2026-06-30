package cn.lunalhx.ai.domain.agent.model.valobj;

import java.math.BigDecimal;

/**
 * Immutable budget snapshot capturing the aggregated token usage and cost
 * for a root Agent Run.
 *
 * <p>Created exclusively by {@code DefaultBudgetGuard} via atomic accumulation
 * on {@code ConcurrentMap.compute}. Readers receive a stable snapshot that
 * will not change under them.
 */
public class BudgetState {

    public static final BudgetState EMPTY = new BudgetState(0L, 0L, 0L, BigDecimal.ZERO);

    private final long usedPromptTokens;
    private final long usedCompletionTokens;
    private final long usedTokens;
    private final BigDecimal estimatedCost;

    public BudgetState(long usedPromptTokens, long usedCompletionTokens,
                       long usedTokens, BigDecimal estimatedCost) {
        this.usedPromptTokens = usedPromptTokens;
        this.usedCompletionTokens = usedCompletionTokens;
        this.usedTokens = usedTokens;
        this.estimatedCost = estimatedCost == null ? BigDecimal.ZERO : estimatedCost;
    }

    public long usedPromptTokens() { return usedPromptTokens; }
    public long usedCompletionTokens() { return usedCompletionTokens; }
    public long usedTokens() { return usedTokens; }
    public BigDecimal estimatedCost() { return estimatedCost; }

    public BudgetState plus(long promptTokens, long completionTokens,
                             long totalTokens, BigDecimal cost) {
        return new BudgetState(
                usedPromptTokens + promptTokens,
                usedCompletionTokens + completionTokens,
                usedTokens + totalTokens,
                cost != null ? estimatedCost.add(cost) : estimatedCost);
    }
}
