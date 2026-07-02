package cn.lunalhx.ai.types.error;

public record ApiError(ErrorCode errorCode, String message) {

    public static ApiError of(ErrorCode errorCode) {
        return new ApiError(errorCode, errorCode.defaultMessage());
    }

    public static ApiError of(ErrorCode errorCode, String message) {
        return new ApiError(errorCode, message);
    }

    public String code() {
        return errorCode.code();
    }

    public ErrorCategory category() {
        return errorCode.category();
    }
}
