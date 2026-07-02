package cn.lunalhx.ai.domain.agent.model.state;

import cn.lunalhx.ai.domain.agent.model.valobj.BudgetState;

import java.math.BigDecimal;

/**
 * Mutable budget snapshot. Only allows atomic replacement of the full {@link BudgetState};
 * individual token/cost setters on AgentContext create new BudgetState instances.
 */
public final class AgentBudgetState {

    private volatile BudgetState budgetState = BudgetState.EMPTY;

    // ---- legacy field pending removal ----
    private String budgetBlockedReason;

    public BudgetState budgetState() { return budgetState; }
    public long usedPromptTokens() { return budgetState.usedPromptTokens(); }
    public long usedCompletionTokens() { return budgetState.usedCompletionTokens(); }
    public long usedTokens() { return budgetState.usedTokens(); }
    public BigDecimal estimatedCost() { return budgetState.estimatedCost(); }
    public String budgetBlockedReason() { return budgetBlockedReason; }

    public void setBudgetState(BudgetState v) { this.budgetState = v == null ? BudgetState.EMPTY : v; }
    public void setBudgetBlockedReason(String v) { this.budgetBlockedReason = v; }

    /** Atomically replace the full budget snapshot. Only entry point for budget writers. */
    public void replace(BudgetState state) {
        this.budgetState = state == null ? BudgetState.EMPTY : state;
    }
}
