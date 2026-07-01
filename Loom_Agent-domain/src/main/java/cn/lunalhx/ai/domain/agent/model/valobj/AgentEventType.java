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
    SKILL_ACTIVATED("skill_activated"),
    THOUGHT("thought"),
    TOOL_CALL("tool_call"),
    APPROVAL_REQUIRED("approval_required"),
    HIGH_RISK_APPROVAL_REQUIRED("high_risk_approval_required"),
    USER_INPUT_REQUIRED("user_input_required"),
    POLICY_DENIED("policy_denied"),
    OBSERVATION("observation"),
    ANSWER("answer"),
    BACKGROUND_TASK_STARTED("background_task_started"),
    BACKGROUND_TASK_COMPLETED("background_task_completed"),
    BACKGROUND_TASK_FAILED("background_task_failed"),
    BACKGROUND_TASK_CANCELLED("background_task_cancelled"),
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
