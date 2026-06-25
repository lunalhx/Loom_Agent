package cn.lunalhx.ai.domain.model.valobj;

public class ModelGatewayException extends RuntimeException {

    private static final long serialVersionUID = -734176253641175884L;

    private final ModelErrorCode errorCode;
    private final boolean retryable;
    private final Integer httpStatus;
    private final Long retryAfterMs;
    private String model;

    public ModelGatewayException(ModelErrorCode errorCode, String message, boolean retryable, Integer httpStatus, Throwable cause) {
        this(errorCode, message, retryable, httpStatus, null, null, cause);
    }

    public ModelGatewayException(ModelErrorCode errorCode,
                                 String message,
                                 boolean retryable,
                                 Integer httpStatus,
                                 Long retryAfterMs,
                                 String model,
                                 Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.retryAfterMs = retryAfterMs;
        this.model = model;
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

    public Long getRetryAfterMs() {
        return retryAfterMs;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

}
