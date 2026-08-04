package cn.lunalhx.ai.domain.agent.model.valobj;

/**
 * Stable type classification for {@code ConversationHistoryEntry}, used for
 * idempotency and diagnostic grouping independent of the dynamic-text role.
 */
public enum ConversationEntryType {

    USER_TASK("user_task"),
    USER_INPUT("user_input"),
    ASSISTANT_ACTION("assistant_action"),
    TOOL_RESULT("tool_result"),
    CONTROL_UPDATE("control_update"),
    SYSTEM_NOTE("system_note"),
    LONG_TERM_MEMORY("long_term_memory");

    private final String code;

    ConversationEntryType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

}
