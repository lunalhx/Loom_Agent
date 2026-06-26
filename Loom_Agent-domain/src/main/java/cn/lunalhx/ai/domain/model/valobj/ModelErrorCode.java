package cn.lunalhx.ai.domain.model.valobj;

public enum ModelErrorCode {

    CONFIG_ERROR("config_error", "模型配置不完整，请检查环境变量"),
    INVALID_REQUEST("invalid_request", "请求参数不符合模型接口要求"),
    BAD_REQUEST("bad_request", "模型请求不合法"),
    INVALID_PARAMETER("invalid_parameter", "模型请求参数不合法"),
    AUTHENTICATION_FAILED("authentication_failed", "模型鉴权失败，请检查 API Key"),
    INSUFFICIENT_BALANCE("insufficient_balance", "模型账户余额不足"),
    RATE_LIMITED("rate_limited", "模型服务限流，请稍后重试"),
    PROVIDER_UNAVAILABLE("provider_unavailable", "模型服务暂时不可用"),
    PROVIDER_OVERLOADED("provider_overloaded", "模型服务过载，请稍后重试"),
    TIMEOUT("timeout", "模型响应超时"),
    MODEL_CALL_TIMEOUT("model_call_timeout", "模型调用超过当前步骤截止时间"),
    BUDGET_EXCEEDED("budget_exceeded", "模型调用超过剩余预算"),
    CONTEXT_OVERFLOW("context_overflow", "模型上下文长度超限"),
    MODEL_CAPABILITY_MISMATCH("model_capability_mismatch", "模型能力不满足本次调用要求"),
    OUTPUT_EMPTY("output_empty", "模型未返回有效内容"),
    OUTPUT_TRUNCATED("output_truncated", "模型输出被截断，请调大 maxTokens 后重试"),
    MODEL_DECISION_TRUNCATED("model_decision_truncated", "模型控制决策输出被截断"),
    TOOL_RESULT_SUMMARY_TRUNCATED("tool_result_summary_truncated", "工具结果摘要输出被截断"),
    CONTENT_FILTERED("content_filtered", "模型输出被内容安全策略拦截"),
    VALIDATION_ERROR("validation_error", "模型输出格式校验失败"),
    MODEL_ERROR("model_error", "模型调用失败"),
    APPROVAL_STATE_MISSING("approval_state_missing", "审批状态不一致，审批记录已不可查");

    private final String code;
    private final String message;

    ModelErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

}
