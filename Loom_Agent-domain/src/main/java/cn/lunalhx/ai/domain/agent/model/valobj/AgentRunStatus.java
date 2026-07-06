package cn.lunalhx.ai.domain.agent.model.valobj;

public enum AgentRunStatus {

    RUNNING,
    WAITING_APPROVAL,
    WAITING_USER_INPUT,
    COMPLETED,
    FAILED,
    BUDGET_EXCEEDED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED
                || this == FAILED
                || this == BUDGET_EXCEEDED
                || this == CANCELLED;
    }

    public boolean resumable() {
        return this == RUNNING
                || this == WAITING_APPROVAL
                || this == WAITING_USER_INPUT;
    }
}
