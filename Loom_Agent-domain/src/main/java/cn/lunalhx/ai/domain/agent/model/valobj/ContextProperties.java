package cn.lunalhx.ai.domain.agent.model.valobj;

import lombok.Data;

/**
 * Character-budget context-reduction configuration for the fixed five-section
 * send view (prefix → memory → relevant_memory → history → current_request).
 *
 * <p>Only the settings actually consumed by the reduction pipeline are kept:
 * the reduction switch, total budget, the four trimmed-section budgets/floors,
 * the recent-history window, and the relevant-memory limit. Legacy storage,
 * artifact, tool-preview, dynamic-entry and transcript-cleanup settings have
 * been removed.
 */
@Data
public class ContextProperties {
    private Boolean contextReductionEnabled = true;
    private Integer totalBudgetChars = 12000;
    private Integer prefixBudgetChars = 3600;
    private Integer prefixFloorChars = 900;
    private Integer workspaceBudgetChars = 2200;
    private Integer workspaceFloorChars = 400;
    private Integer memoryBudgetChars = 1600;
    private Integer memoryFloorChars = 400;
    private Integer relevantMemoryBudgetChars = 1200;
    private Integer relevantMemoryFloorChars = 300;
    private Integer historyBudgetChars = 5200;
    private Integer historyFloorChars = 1300;
    private Integer recentHistoryItems = 6;
    private Integer relevantMemoryLimit = 3;
}
