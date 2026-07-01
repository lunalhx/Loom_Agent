package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.tool.adapter.port.CommandExecutor;
import cn.lunalhx.ai.domain.tool.model.ShellOutputLimits;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
public class LocalCommandExecutor implements CommandExecutor {

    private final BackgroundProcessManager processManager;

    public LocalCommandExecutor() {
        this.processManager = null;
    }

    public LocalCommandExecutor(BackgroundProcessManager processManager) {
        this.processManager = processManager;
    }

    @Override
    public ToolResult run(List<String> command, Path cwd, long timeoutMs, ShellOutputLimits limits, long startedAt) {
        if (processManager != null) {
            BackgroundProcessManager.SyncResult result = processManager.runSync(command, cwd, timeoutMs, limits, startedAt);
            return ToolResult.builder()
                    .success(result.success())
                    .errorCode(result.errorCode())
                    .message(result.message())
                    .observation(result.observation())
                    .truncated(result.truncated())
                    .elapsedMs(result.elapsedMs())
                    .build();
        }
        return SandboxProcessRunner.run(command, cwd, timeoutMs, limits, startedAt);
    }

}
