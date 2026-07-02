package cn.lunalhx.ai.types.error;

public enum ErrorCategory {

    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    GONE(410),
    RATE_LIMITED(429),
    TIMEOUT(504),
    UNAVAILABLE(503),
    INTERNAL(500);

    private final int httpStatus;

    ErrorCategory(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
