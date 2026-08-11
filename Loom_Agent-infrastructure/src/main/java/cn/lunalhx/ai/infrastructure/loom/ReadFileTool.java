package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ApprovalRequirement;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.tool.model.ToolEvidenceCandidate;
import cn.lunalhx.ai.domain.tool.model.ToolCapabilityEnvelope;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * loom-code {@code read_file}: read a UTF-8 file by line range with
 * {@code # path} header and right-aligned line numbers. No hard 200-line cap
 * unless requested via {@code end}.
 */
@Component
public class ReadFileTool implements AgentTool {

    private final WorkspacePort workspacePort;

    public ReadFileTool(WorkspacePort workspacePort) {
        this.workspacePort = workspacePort;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("read_file")
                .description("Read a UTF-8 file by line range.")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"path\":{\"type\":\"string\",\"minLength\":1,\"description\":\"file path\"}," +
                        "\"start\":{\"type\":\"integer\",\"minimum\":1,\"default\":1,\"description\":\"start line\"}," +
                        "\"end\":{\"type\":\"integer\",\"minimum\":1,\"default\":200,\"description\":\"end line\"}" +
                        "}," +
                        "\"required\":[\"path\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .capabilityEnvelope(ToolCapabilityEnvelope.repositoryRead())
                .approvalRequirement(ApprovalRequirement.NONE)
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            String rawPath = text(call, "path", null);
            int start = intValue(call, "start", 1);
            int end = intValue(call, "end", 200);
            if (rawPath == null || rawPath.isBlank()) {
                return failure("path must not be empty", startedAt);
            }
            if (start < 1 || end < start) {
                return failure("invalid line range", startedAt);
            }
            Path root = LoomToolSupport.root(workspacePort, call);
            Path file = LoomToolSupport.resolve(workspacePort, call, rawPath);
            if (!Files.isRegularFile(file)) {
                return failure("path is not a file", startedAt);
            }
            List<String> lines = ReadFileEvidenceSupport.readLines(file);
            StringBuilder out = new StringBuilder("# ").append(LoomToolSupport.relative(root, file)).append('\n');
            int last = Math.min(end, lines.size());
            for (int i = start; i <= last; i++) {
                String line = lines.get(i - 1);
                out.append(String.format("%4d: ", i)).append(line).append('\n');
            }
            String result = out.toString().stripTrailing();
            ToolResult toolResult = ToolResult.success(result, false, elapsed(startedAt));
            if (start <= last) {
                String relativePath = LoomToolSupport.relative(root, file);
                String normalizedScope = relativePath + "#lines=" + start + "-" + last;
                String semantics = "read_file:utf8-lines:v1";
                toolResult.setEvidenceCandidate(ToolEvidenceCandidate.builder()
                        .evidenceKey("read_file|" + normalizedScope)
                        .toolSemantics(semantics)
                        .normalizedScope(normalizedScope)
                        .repositoryRelativePath(relativePath)
                        .observedStartLine(start)
                        .observedEndLine(last)
                        .digestAlgorithm("SHA-256")
                        .stateDigest(ReadFileEvidenceSupport.digest(lines, start, last))
                        .complete(true)
                        .revalidation(EvidenceRevalidation.builder()
                                .digestAlgorithm("SHA-256")
                                .toolSemantics(semantics)
                                .repositoryRelativePath(relativePath)
                                .startLine(start)
                                .endLine(last)
                                .build())
                        .build());
            }
            return toolResult;
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

    private int intValue(ToolCall call, String key, int def) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return def;
        }
        return call.getInput().path(key).asInt(def);
    }

    private ToolResult failure(String message, long startedAt) {
        return ToolResult.failure("read_file_failed", message, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
