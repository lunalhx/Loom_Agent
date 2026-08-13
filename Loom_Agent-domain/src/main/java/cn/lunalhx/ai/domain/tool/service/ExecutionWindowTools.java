package cn.lunalhx.ai.domain.tool.service;

import cn.lunalhx.ai.domain.tool.model.ToolSpec;

import java.util.List;

/** Tools that must persist an execution-window marker before adapter invocation. */
public final class ExecutionWindowTools {

    private ExecutionWindowTools() {
    }

    public static boolean requiresWindow(String toolName, List<ToolSpec> contracts) {
        return ObservationTools.isObservation(toolName)
                || FileMutationTools.isFileMutation(toolName)
                || ShellTools.isShell(toolName)
                || DelegateTools.isDelegate(toolName)
                || UnverifiableExternalTools.isUnverifiableExternal(toolName, contracts);
    }
}
