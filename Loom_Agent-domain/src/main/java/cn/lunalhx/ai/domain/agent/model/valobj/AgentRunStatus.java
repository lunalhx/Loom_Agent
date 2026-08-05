package cn.lunalhx.ai.domain.agent.model.valobj;

public enum AgentRunStatus {

    RUNNING,
    WAITING_APPROVAL,
    WAITING_USER_INPUT,
    COMPLETED,
    STOPPED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED
                || this == STOPPED
                || this == FAILED;
    }

    public boolean resumable() {
        return this == RUNNING
                || this == WAITING_APPROVAL
                || this == WAITING_USER_INPUT;
    }
}
