package cn.lunalhx.ai.domain.agent.model.valobj;

public enum AgentRunStatus {

    RUNNING,
    WAITING_APPROVAL,
    WAITING_USER_INPUT,
    WAITING_AMBIGUITY_REVIEW,
    COMPLETED,
    STOPPED,
    FAILED,
    ABANDONED;

    public boolean terminal() {
        return this == COMPLETED
                || this == STOPPED
                || this == FAILED
                || this == ABANDONED;
    }

    public boolean resumable() {
        return this == RUNNING
                || this == WAITING_APPROVAL
                || this == WAITING_USER_INPUT
                || this == WAITING_AMBIGUITY_REVIEW;
    }
}
