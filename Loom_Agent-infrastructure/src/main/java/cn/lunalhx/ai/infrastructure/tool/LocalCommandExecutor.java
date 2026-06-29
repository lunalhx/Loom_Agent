package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.adapter.port.CommandExecutor;
import cn.lunalhx.ai.domain.tool.model.ShellOutputLimits;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
public class LocalCommandExecutor implements CommandExecutor {

    @Override
    public ToolResult run(List<String> command, Path cwd, long timeoutMs, ShellOutputLimits limits, long startedAt) {
        return SandboxProcessRunner.run(command, cwd, timeoutMs, limits, startedAt);
    }

}
