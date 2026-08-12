package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * loom-code {@code run_shell}: run a shell command in the repo root via
 * {@code /bin/sh -c}, timeout 1-120s, returning exit_code/stdout/stderr.
 */
@Component
public class RunShellTool implements AgentTool {

    private final WorkspacePort workspacePort;

    public RunShellTool(WorkspacePort workspacePort) {
        this.workspacePort = workspacePort;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("run_shell")
                .description("Run a shell command in the repo root.")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"command\":{\"type\":\"string\",\"minLength\":1,\"description\":\"shell command\"}," +
                        "\"timeout\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":120,\"default\":20,\"description\":\"timeout in seconds\"}" +
                        "}," +
                        "\"required\":[\"command\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .capabilityEnvelope(ToolCapabilityEnvelope.shell())
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        String command = text(call, "command", null);
        int timeout = intValue(call, "timeout", 20);
        if (command == null || command.isBlank()) {
            return failure("command must not be empty", startedAt);
        }
        if (timeout < 1 || timeout > 120) {
            return failure("timeout must be in [1, 120]", startedAt);
        }
        try {
            Path root = LoomToolSupport.root(workspacePort, call);
            Set<String> secretEnvNames = call.getSecretEnvNames() == null
                    ? java.util.Set.of() : call.getSecretEnvNames();
            ShellRunner.ShellResult result = ShellRunner.run(command, root, timeout, secretEnvNames);
            String stdout = result.stdout().isBlank() ? "(empty)" : result.stdout().stripTrailing();
            String stderr = result.stderr().isBlank() ? "(empty)" : result.stderr().stripTrailing();
            String observation = "exit_code: " + result.exitCode() + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
            return ToolResult.success(LoomToolSupport.clip(observation), false, elapsed(startedAt));
        } catch (Exception e) {
            return failure(e.getMessage(), startedAt);
        }
    }

    private String text(ToolCall call, String key, String def) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return def;
        }
        return call.getInput().path(key).asText(def);
    }

    private int intValue(ToolCall call, String key, int def) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return def;
        }
        return call.getInput().path(key).asInt(def);
    }

    private ToolResult failure(String message, long startedAt) {
        return ToolResult.failure("run_shell_failed", message, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
