package cn.lunalhx.ai.domain.tool.service;

/** Tools that must persist an execution-window marker before adapter invocation. */
public final class ExecutionWindowTools {

    private ExecutionWindowTools() {
    }

    public static boolean requiresWindow(String toolName) {
        return ObservationTools.isObservation(toolName)
                || FileMutationTools.isFileMutation(toolName)
                || ShellTools.isShell(toolName);
    }
}
