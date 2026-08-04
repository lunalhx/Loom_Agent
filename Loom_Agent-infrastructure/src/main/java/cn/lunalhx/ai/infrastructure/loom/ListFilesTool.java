package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * loom-code {@code list_files}: list a single directory level, directories
 * first, case-insensitive name sort, max 200 entries.
 */
@Component
public class ListFilesTool implements AgentTool {

    private static final int MAX_ENTRIES = 200;

    private final WorkspacePort workspacePort;

    public ListFilesTool(WorkspacePort workspacePort) {
        this.workspacePort = workspacePort;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("list_files")
                .description("List files in the workspace.")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{\"path\":{\"type\":\"string\",\"default\":\".\",\"description\":\"directory\"}}," +
                        "\"required\":[]," +
                        "\"additionalProperties\":false" +
                        "}")
                .risky(false)
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            String rawPath = call.getInput() != null && call.getInput().has("path")
                    ? call.getInput().path("path").asText(".") : ".";
            Path root = LoomToolSupport.root(workspacePort, call);
            Path dir = LoomToolSupport.resolve(workspacePort, call, rawPath);
            if (!Files.isDirectory(dir)) {
                return failure("path is not a directory", startedAt);
            }
            List<Path> entries = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.forEach(entries::add);
            }
            entries.removeIf(LoomToolSupport::isIgnored);
            entries.sort(Comparator.comparing((Path p) -> Files.isDirectory(p))
                    .thenComparing(p -> p.getFileName().toString().toLowerCase()));

            StringBuilder out = new StringBuilder();
            int shown = 0;
            for (Path entry : entries) {
                if (shown >= MAX_ENTRIES) {
                    break;
                }
                String kind = Files.isDirectory(entry) ? "[D]" : "[F]";
                out.append(kind).append(' ').append(LoomToolSupport.relative(root, entry)).append('\n');
                shown++;
            }
            String result = out.toString().isEmpty() ? "(empty)" : out.toString().stripTrailing();
            return ToolResult.success(LoomToolSupport.clip(result), false, elapsed(startedAt));
        } catch (IOException e) {
            return failure(e.getMessage(), startedAt);
        } catch (Exception e) {
            return failure(e.getMessage(), startedAt);
        }
    }

    private ToolResult failure(String message, long startedAt) {
        return ToolResult.failure("list_files_failed", message, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
