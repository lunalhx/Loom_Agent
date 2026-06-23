package cn.lunalhx.ai.domain.agent.model.valobj;

public enum AgentEventType {

    META("meta"),
    NODE_START("node_start"),
    PLAN_UPDATED("plan_updated"),
    REPLAN_STARTED("replan_started"),
    CHECKPOINT_SAVED("checkpoint_saved"),
    RESUME_STARTED("resume_started"),
    SUB_AGENT_STARTED("sub_agent_started"),
    SUB_AGENT_COMPLETED("sub_agent_completed"),
    SUB_AGENT_FAILED("sub_agent_failed"),
    SUB_AGENT_SUMMARY("sub_agent_summary"),
    THOUGHT("thought"),
    TOOL_CALL("tool_call"),
    APPROVAL_REQUIRED("approval_required"),
    POLICY_DENIED("policy_denied"),
    OBSERVATION("observation"),
    ANSWER("answer"),
    DONE("done"),
    ERROR("error");

    private final String eventName;

    AgentEventType(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }

}
