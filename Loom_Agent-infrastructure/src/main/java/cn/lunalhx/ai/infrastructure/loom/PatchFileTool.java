package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ApprovalRequirement;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * loom-code {@code patch_file}: replace one exact text block; {@code old_text}
 * must be non-empty and occur exactly once.
 */
@Component
public class PatchFileTool implements AgentTool {

    private final WorkspacePort workspacePort;

    public PatchFileTool(WorkspacePort workspacePort) {
        this.workspacePort = workspacePort;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("patch_file")
                .description("Replace one exact text block in a file.")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"path\":{\"type\":\"string\",\"minLength\":1,\"description\":\"file path\"}," +
                        "\"old_text\":{\"type\":\"string\",\"minLength\":1,\"description\":\"exact text to replace\"}," +
                        "\"new_text\":{\"type\":\"string\",\"description\":\"replacement text\"}" +
                        "}," +
                        "\"required\":[\"path\",\"old_text\",\"new_text\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .capabilityEnvelope(ToolCapabilityEnvelope.repositoryMutation())
                .approvalRequirement(ApprovalRequirement.SESSION_POLICY)
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        String rawPath = text(call, "path", null);
        String oldText = text(call, "old_text", null);
        String newText = text(call, "new_text", null);
        if (rawPath == null || rawPath.isBlank()) {
            return failure("path must not be empty", startedAt);
        }
        if (oldText == null || oldText.isEmpty()) {
            return failure("old_text must not be empty", startedAt);
        }
        if (newText == null) {
            return failure("missing new_text", startedAt);
        }
        try {
            Path root = LoomToolSupport.root(workspacePort, call);
            Path file = LoomToolSupport.resolve(workspacePort, call, rawPath);
            if (!Files.isRegularFile(file)) {
                return failure("path is not a file", startedAt);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldText);
            if (count != 1) {
                return failure("old_text must occur exactly once, found " + count, startedAt);
            }
            Files.writeString(file, content.replace(oldText, newText), StandardCharsets.UTF_8);
            return ToolResult.success("patched " + LoomToolSupport.relative(root, file), false, elapsed(startedAt));
        } catch (IOException e) {
            return failure(e.getMessage(), startedAt);
        } catch (Exception e) {
            return failure(e.getMessage(), startedAt);
        }
    }

    /** Literal substring counting — never regex split. */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private String text(ToolCall call, String key, String def) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return def;
        }
        return call.getInput().path(key).asText(def);
    }

    private ToolResult failure(String message, long startedAt) {
        return ToolResult.failure("patch_file_failed", message, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
