package cn.lunalhx.ai.domain.agent.model.valobj;

public enum AgentEventType {

    RUN_STARTED("run_started"),
    META("meta"),
    NODE_START("node_start"),
    CONTEXT_COMPACTED("context_compacted"),
    CHECKPOINT_SAVED("checkpoint_saved"),
    THOUGHT("thought"),
    TOOL_CALL("tool_call"),
    USER_INPUT_REQUIRED("user_input_required"),
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
