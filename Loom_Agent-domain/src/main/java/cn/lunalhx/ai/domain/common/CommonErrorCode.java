package cn.lunalhx.ai.domain.common;

import cn.lunalhx.ai.types.error.ErrorCategory;
import cn.lunalhx.ai.types.error.ErrorCode;

public enum CommonErrorCode implements ErrorCode {

    UNKNOWN("0001", "未知失败", ErrorCategory.INTERNAL, true),
    INVALID_PARAMETER("0002", "非法参数", ErrorCategory.BAD_REQUEST, true),
    INVALID_REQUEST("invalid_request", "请求参数不合法", ErrorCategory.BAD_REQUEST, false),
    RATE_LIMITED("rate_limited", "请求被限流，请稍后重试", ErrorCategory.RATE_LIMITED, false),
    ;

    private final String code;
    private final String message;
    private final ErrorCategory category;
    private final boolean legacy;

    CommonErrorCode(String code, String message, ErrorCategory category, boolean legacy) {
        this.code = code;
        this.message = message;
        this.category = category;
        this.legacy = legacy;
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
