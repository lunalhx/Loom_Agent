package cn.lunalhx.ai.domain.tool.service;

/** Shell tools whose lost durable results are unknown external effects. */
public final class ShellTools {

    public static final String RUN_SHELL = "run_shell";

    private ShellTools() {
    }

    public static boolean isShell(String toolName) {
        return RUN_SHELL.equals(toolName);
    }
}
