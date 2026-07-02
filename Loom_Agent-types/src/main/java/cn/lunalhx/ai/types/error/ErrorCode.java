package cn.lunalhx.ai.types.error;

public interface ErrorCode {

    String code();

    String defaultMessage();

    ErrorCategory category();

    default boolean legacy() {
        return false;
    }
}
