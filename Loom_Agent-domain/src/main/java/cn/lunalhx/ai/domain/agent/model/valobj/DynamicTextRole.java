package cn.lunalhx.ai.domain.agent.model.valobj;

public enum DynamicTextRole {

    USER_TASK("user_task"),
    ASSISTANT_ACTION("assistant_action"),
    TOOL_RESULT("tool_result"),
    SYSTEM_NOTE("system_note");

    private final String code;

    DynamicTextRole(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

}
