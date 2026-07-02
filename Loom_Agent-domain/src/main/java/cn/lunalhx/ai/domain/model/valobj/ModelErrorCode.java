package cn.lunalhx.ai.domain.model.valobj;

import cn.lunalhx.ai.types.error.ErrorCategory;
import cn.lunalhx.ai.types.error.ErrorCode;

public enum ModelErrorCode implements ErrorCode {

    CONFIG_ERROR("config_error", "模型配置不完整，请检查环境变量", ErrorCategory.BAD_REQUEST),
    INVALID_REQUEST("invalid_request", "模型请求参数不符合接口要求", ErrorCategory.BAD_REQUEST),
    BAD_REQUEST("bad_request", "模型请求不合法", ErrorCategory.BAD_REQUEST),
    INVALID_PARAMETER("invalid_parameter", "模型请求参数不合法", ErrorCategory.BAD_REQUEST),
    AUTHENTICATION_FAILED("authentication_failed", "模型鉴权失败，请检查 API Key", ErrorCategory.UNAUTHORIZED),
    INSUFFICIENT_BALANCE("insufficient_balance", "模型账户余额不足", ErrorCategory.FORBIDDEN),
    RATE_LIMITED("rate_limited", "模型服务限流，请稍后重试", ErrorCategory.RATE_LIMITED),
    PROVIDER_UNAVAILABLE("provider_unavailable", "模型服务暂时不可用", ErrorCategory.UNAVAILABLE),
    PROVIDER_OVERLOADED("provider_overloaded", "模型服务过载，请稍后重试", ErrorCategory.UNAVAILABLE),
    MODEL_CALL_TIMEOUT("model_call_timeout", "模型调用超过当前步骤截止时间", ErrorCategory.TIMEOUT),
    BUDGET_EXCEEDED("budget_exceeded", "模型调用超过剩余预算", ErrorCategory.FORBIDDEN),
    CONTEXT_OVERFLOW("context_overflow", "模型上下文长度超限", ErrorCategory.BAD_REQUEST),
    MODEL_CAPABILITY_MISMATCH("model_capability_mismatch", "模型能力不满足本次调用要求", ErrorCategory.BAD_REQUEST),
    MODEL_DECISION_TRUNCATED("model_decision_truncated", "模型控制决策输出被截断", ErrorCategory.INTERNAL),
    TOOL_RESULT_SUMMARY_TRUNCATED("tool_result_summary_truncated", "工具结果摘要输出被截断", ErrorCategory.INTERNAL),
    CONTENT_FILTERED("content_filtered", "模型输出被内容安全策略拦截", ErrorCategory.FORBIDDEN),
    MODEL_ERROR("model_error", "模型调用失败", ErrorCategory.INTERNAL),
    APPROVAL_STATE_MISSING("approval_state_missing", "审批状态不一致，审批记录已不可查", ErrorCategory.INTERNAL);

    private final String code;
    private final String message;
    private final ErrorCategory category;

    ModelErrorCode(String code, String message, ErrorCategory category) {
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
}
