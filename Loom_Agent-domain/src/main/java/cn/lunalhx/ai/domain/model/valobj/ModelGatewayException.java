package cn.lunalhx.ai.domain.model.valobj;

public class ModelGatewayException extends RuntimeException {

    private static final long serialVersionUID = -734176253641175884L;

    private final ModelErrorCode errorCode;
    private final boolean retryable;
    private final Integer httpStatus;

    public ModelGatewayException(ModelErrorCode errorCode, String message, boolean retryable, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public ModelErrorCode getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

}
