package cn.lunalhx.ai.domain.agent.model.valobj;

public record TodoApplyResult(TodoApplyMode mode, String itemId, String reason) {

    public static TodoApplyResult applied(String itemId) {
        return new TodoApplyResult(TodoApplyMode.APPLIED, itemId, null);
    }

    public static TodoApplyResult skipped(String itemId, String reason) {
        return new TodoApplyResult(TodoApplyMode.SKIPPED, itemId, reason);
    }

    public boolean isApplied() {
        return mode == TodoApplyMode.APPLIED;
    }
}
