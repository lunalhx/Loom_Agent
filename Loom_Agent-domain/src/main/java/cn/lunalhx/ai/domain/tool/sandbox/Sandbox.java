package cn.lunalhx.ai.domain.tool.sandbox;

import cn.lunalhx.ai.domain.tool.model.BackgroundLaunchMode;
import cn.lunalhx.ai.domain.tool.model.ShellOutputLimits;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface Sandbox {

    PathMapping workspaceMapping();

    Path sessionTemp();

    Path uploads();

    Path outputs();

    ToolResult execute(List<String> command, Path cwd, Map<String, String> extraEnv,
                       long timeoutMs, ShellOutputLimits limits, long startedAt);

    SandboxBackgroundResult startBackground(List<String> command, Path cwd,
                                            Map<String, String> extraEnv, long timeoutMs,
                                            String runId, String workspaceDisplayName,
                                            BackgroundLaunchMode launchMode);

    boolean hasActiveProcesses();

    void cancelProcesses();
}
