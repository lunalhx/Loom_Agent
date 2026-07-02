package cn.lunalhx.ai.domain.agent.model.valobj;

import cn.lunalhx.ai.types.error.ErrorCategory;
import cn.lunalhx.ai.types.error.ErrorCode;

public enum AgentErrorCode implements ErrorCode {

    AGENT_DISABLED("agent_disabled", "Agent 功能未启用", ErrorCategory.UNAVAILABLE),
    AGENT_ERROR("agent_error", "Agent 执行失败", ErrorCategory.INTERNAL),
    AGENT_TIMEOUT("agent_timeout", "Agent 执行超时", ErrorCategory.TIMEOUT),
    REPLAY_FAILED("replay_failed", "Replay 失败", ErrorCategory.INTERNAL),
    CONVERSATION_DELETED("1004", "会话已删除", ErrorCategory.GONE, true),
    APPROVAL_NOT_FOUND("approval_not_found", "审批不存在或已过期", ErrorCategory.NOT_FOUND),
    APPROVAL_STATE_MISSING("approval_state_missing", "审批状态不一致，审批记录已不可查", ErrorCategory.INTERNAL),
    CHECKPOINT_NOT_FOUND("checkpoint_not_found", "未找到可恢复的 checkpoint", ErrorCategory.NOT_FOUND),
    RUN_NOT_WAITING_USER_INPUT("run_not_waiting_user_input", "当前运行不在等待用户输入状态", ErrorCategory.CONFLICT),
    INVALID_USER_INPUT("invalid_user_input", "CONTINUE 必须提供非空 message", ErrorCategory.BAD_REQUEST),
    RUN_ALREADY_TERMINAL("run_already_terminal", "当前运行已结束，不能再次恢复", ErrorCategory.CONFLICT),
    BACKGROUND_TASK_NOT_FOUND("background_task_not_found", "后台任务不存在", ErrorCategory.NOT_FOUND),
    WORKSPACE_RESOLUTION_FAILED("workspace_resolution_failed", "工作区路径校验失败", ErrorCategory.BAD_REQUEST);

    private final String code;
    private final String message;
    private final ErrorCategory category;
    private final boolean legacy;

    AgentErrorCode(String code, String message, ErrorCategory category, boolean legacy) {
        this.code = code;
        this.message = message;
        this.category = category;
        this.legacy = legacy;
    }

    AgentErrorCode(String code, String message, ErrorCategory category) {
        this(code, message, category, false);
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return message;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }

    @Override
    public boolean legacy() {
        return legacy;
    }
}
