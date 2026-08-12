package cn.lunalhx.ai.domain.tool.model;

/** Structured process outcome; observations are presentation only. */
public record ShellExecutionResult(int exitCode, TerminationReason terminationReason,
                                   boolean stdoutTruncated, boolean stderrTruncated,
                                   boolean backgroundProcessTerminated) {
    public enum TerminationReason { EXITED, TIMED_OUT, CANCELLED, LAUNCH_FAILED, INTERRUPTED }
}
