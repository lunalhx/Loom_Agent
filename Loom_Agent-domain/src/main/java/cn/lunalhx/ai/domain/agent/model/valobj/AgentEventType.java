package cn.lunalhx.ai.domain.agent.model.valobj;

public enum AgentEventType {

    META("meta"),
    NODE_START("node_start"),
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
