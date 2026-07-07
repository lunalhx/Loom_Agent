package cn.lunalhx.ai.domain.agent.service.plan;

import java.util.List;

/**
 * Result of plan reconciliation: counts what was auto-completed via execution
 * facts, and classifies remaining incomplete items into real blockers (edits
 * with unmet targets, incomplete edits, active verify) vs bookkeeping-only
 * items.
 */
public final class PlanReconciliationResult {

    private final int changedCount;
    private final int bookkeepingResolvedCount;
    private final boolean hasRealBlockers;
    private final int incompleteEditCount;
    private final long unmetEditTargets;
    private final String activeVerifyBlockerId;

    PlanReconciliationResult(int changedCount,
                             int bookkeepingResolvedCount,
                             boolean hasRealBlockers,
                             int incompleteEditCount,
                             long unmetEditTargets,
                             String activeVerifyBlockerId) {
        this.changedCount = changedCount;
        this.bookkeepingResolvedCount = bookkeepingResolvedCount;
        this.hasRealBlockers = hasRealBlockers;
        this.incompleteEditCount = incompleteEditCount;
        this.unmetEditTargets = unmetEditTargets;
        this.activeVerifyBlockerId = activeVerifyBlockerId;
    }

    public static PlanReconciliationResult empty() {
        return new PlanReconciliationResult(0, 0, false, 0, 0, null);
    }

    // --- accessors ---

    public int changedCount() {
        return changedCount;
    }

    public int bookkeepingResolvedCount() {
        return bookkeepingResolvedCount;
    }

    public boolean hasRealBlockers() {
        return hasRealBlockers;
    }

    public int incompleteEditCount() {
        return incompleteEditCount;
    }

    public long unmetEditTargets() {
        return unmetEditTargets;
    }

    public String activeVerifyBlockerId() {
        return activeVerifyBlockerId;
    }

    public int totalResolved() {
        return changedCount + bookkeepingResolvedCount;
    }
}
