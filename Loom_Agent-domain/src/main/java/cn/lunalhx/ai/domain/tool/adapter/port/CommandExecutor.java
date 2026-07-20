package cn.lunalhx.ai.domain.tool.adapter.port;

import cn.lunalhx.ai.domain.tool.model.ShellOutputLimits;
import cn.lunalhx.ai.domain.tool.model.ToolResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface CommandExecutor {

    ToolResult run(List<String> command, Path cwd, Map<String, String> extraEnv,
                   long timeoutMs, ShellOutputLimits limits, long startedAt);

    default ToolResult run(List<String> command, Path cwd, long timeoutMs,
                           ShellOutputLimits limits, long startedAt) {
        return run(command, cwd, Map.of(), timeoutMs, limits, startedAt);
    }

}
