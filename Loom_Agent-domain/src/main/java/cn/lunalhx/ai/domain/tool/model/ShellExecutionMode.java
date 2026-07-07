package cn.lunalhx.ai.domain.tool.model;

/**
 * Execution mode for shell commands.
 * <p>SIMPLE_EXEC uses ProcessBuilder with tokenized args.
 * SHELL_EXEC passes the raw command to a shell interpreter.
 */
public enum ShellExecutionMode {
    SIMPLE_EXEC,
    SHELL_EXEC
}
