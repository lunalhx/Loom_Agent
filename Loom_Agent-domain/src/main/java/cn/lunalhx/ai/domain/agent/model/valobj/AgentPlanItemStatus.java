package cn.lunalhx.ai.domain.agent.model.valobj;

public enum AgentPlanItemStatus {

    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    BLOCKED("blocked"),
    SKIPPED("skipped");

    private final String code;

    AgentPlanItemStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean terminal() {
        return this == COMPLETED || this == BLOCKED || this == SKIPPED;
    }

    public static AgentPlanItemStatus from(String value) {
        for (AgentPlanItemStatus status : values()) {
            if (status.code.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("非法任务状态：" + value);
    }

}
