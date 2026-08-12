package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * loom-code {@code write_file}: auto-create parent dirs and overwrite the file.
 */
@Component
public class WriteFileTool implements AgentTool {

    private final WorkspacePort workspacePort;

    public WriteFileTool(WorkspacePort workspacePort) {
        this.workspacePort = workspacePort;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("write_file")
                .description("Write a text file.")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"path\":{\"type\":\"string\",\"minLength\":1,\"description\":\"file path\"}," +
                        "\"content\":{\"type\":\"string\",\"description\":\"file content\"}" +
                        "}," +
                        "\"required\":[\"path\",\"content\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .capabilityEnvelope(ToolCapabilityEnvelope.repositoryMutation())
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        String rawPath = text(call, "path", null);
        String content = text(call, "content", null);
        if (rawPath == null || rawPath.isBlank()) {
            return failure("path must not be empty", startedAt);
        }
        if (content == null) {
            return failure("missing content", startedAt);
        }
        try {
            Path root = LoomToolSupport.root(workspacePort, call);
            Path target = LoomToolSupport.resolveWriteTarget(workspacePort, call, rawPath);
            if (Files.isDirectory(target)) {
                return failure("path is a directory", startedAt);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            String message = "wrote " + LoomToolSupport.relative(root, target)
                    + " (" + content.codePointCount(0, content.length()) + " chars)";
            return ToolResult.success(LoomToolSupport.clip(message), false, elapsed(startedAt));
        } catch (IOException e) {
            return failure(e.getMessage(), startedAt);
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

    private ToolResult failure(String message, long startedAt) {
        return ToolResult.failure("write_file_failed", message, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
