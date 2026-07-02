package cn.lunalhx.ai.types.error;

public class ApplicationException extends RuntimeException {

    private static final long serialVersionUID = 5317680961212299217L;

    private final ApiError apiError;

    public ApplicationException(ApiError apiError) {
        super(apiError.message());
        this.apiError = apiError;
    }

    public ApplicationException(ApiError apiError, Throwable cause) {
        super(apiError.message(), cause);
        this.apiError = apiError;
    }

    public ApplicationException(ErrorCode errorCode) {
        this(ApiError.of(errorCode));
    }

    public ApplicationException(ErrorCode errorCode, String message) {
        this(ApiError.of(errorCode, message));
    }

    public ApplicationException(ErrorCode errorCode, String message, Throwable cause) {
        this(ApiError.of(errorCode, message), cause);
    }

    public ApiError apiError() {
        return apiError;
    }

    public String code() {
        return apiError.code();
    }

    public ErrorCategory category() {
        return apiError.category();
    }
}
