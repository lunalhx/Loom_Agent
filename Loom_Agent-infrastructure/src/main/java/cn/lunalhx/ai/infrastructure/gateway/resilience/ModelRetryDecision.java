package cn.lunalhx.ai.infrastructure.gateway.resilience;

public record ModelRetryDecision(
        Action action,
        ModelAttemptState nextAttempt,
        long delayMs,
        Throwable terminalError,
        ModelFallbackSwitch fallbackSwitch,
        boolean retryExhausted
) {

    public enum Action {
        RETRY,
        STOP
    }

    public ModelRetryDecision {
        if (action == Action.RETRY && nextAttempt == null) {
            throw new IllegalArgumentException("RETRY requires nextAttempt");
        }
        if (action == Action.STOP && terminalError == null) {
            throw new IllegalArgumentException("STOP requires terminalError");
        }
    }

}
