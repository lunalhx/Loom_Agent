package cn.lunalhx.ai.domain.agent.model.valobj;

public enum AgentEventType {

    META("meta"),
    NODE_START("node_start"),
    PLAN_UPDATED("plan_updated"),
    REPLAN_STARTED("replan_started"),
    CONTEXT_COMPACTED("context_compacted"),
    CHECKPOINT_SAVED("checkpoint_saved"),
    RESUME_STARTED("resume_started"),
    SUB_AGENT_STARTED("sub_agent_started"),
    SUB_AGENT_COMPLETED("sub_agent_completed"),
    SUB_AGENT_FAILED("sub_agent_failed"),
    SUB_AGENT_SUMMARY("sub_agent_summary"),
    THOUGHT("thought"),
    TOOL_CALL("tool_call"),
    APPROVAL_REQUIRED("approval_required"),
    USER_INPUT_REQUIRED("user_input_required"),
    POLICY_DENIED("policy_denied"),
    OBSERVATION("observation"),
    ANSWER("answer"),
    STOP_HOOK_RESULT("stop_hook_result"),
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
