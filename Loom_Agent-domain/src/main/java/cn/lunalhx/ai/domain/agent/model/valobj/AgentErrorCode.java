package cn.lunalhx.ai.domain.agent.model.valobj;

import cn.lunalhx.ai.types.error.ErrorCategory;
import cn.lunalhx.ai.types.error.ErrorCode;

public enum AgentErrorCode implements ErrorCode {

    AGENT_DISABLED("agent_disabled", "Agent 功能未启用", ErrorCategory.UNAVAILABLE),
    AGENT_ERROR("agent_error", "Agent 执行失败", ErrorCategory.INTERNAL),
    LEDGER_BOOTSTRAP_FAILED("ledger_bootstrap_failed", "Ledger 初始化失败，模型调用已阻止", ErrorCategory.INTERNAL),
    AGENT_TIMEOUT("agent_timeout", "Agent 执行超时", ErrorCategory.TIMEOUT),
    CHECKPOINT_NOT_FOUND("checkpoint_not_found", "未找到可恢复的 checkpoint", ErrorCategory.NOT_FOUND),
    RUN_NOT_FOUND("run_not_found", "未找到 run", ErrorCategory.NOT_FOUND),
    CONVERSATION_BUSY("conversation_busy", "当前会话正在处理另一个请求，请稍后重试", ErrorCategory.CONFLICT),
    WORKSPACE_RESOLUTION_FAILED("workspace_resolution_failed", "工作区路径校验失败", ErrorCategory.BAD_REQUEST);

    private final String code;
    private final String message;
    private final ErrorCategory category;

    AgentErrorCode(String code, String message, ErrorCategory category) {
        this.code = code;
        this.message = message;
        this.category = category;
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
        return false;
    }
}
