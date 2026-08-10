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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * loom-code {@code search}: prefer {@code rg -n --smart-case --max-count 200},
 * fall back to a case-insensitive substring search when rg is unavailable.
 */
@Component
public class SearchTool implements AgentTool {

    private static final int MAX_MATCHES = 200;

    private final WorkspacePort workspacePort;

    public SearchTool(WorkspacePort workspacePort) {
        this.workspacePort = workspacePort;
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("search")
                .description("Search the workspace with rg or a simple fallback.")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"pattern\":{\"type\":\"string\",\"minLength\":1,\"description\":\"search pattern\"}," +
                        "\"path\":{\"type\":\"string\",\"default\":\".\",\"description\":\"search path\"}" +
                        "}," +
                        "\"required\":[\"pattern\"]," +
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
            String pattern = text(call, "pattern", null);
            String rawPath = text(call, "path", ".");
            if (pattern == null || pattern.isBlank()) {
                return failure("pattern must not be empty", startedAt);
            }
            Path root = LoomToolSupport.root(workspacePort, call);
            Path path = LoomToolSupport.resolve(workspacePort, call, rawPath);

            String result = runRg(pattern, path);
            if (result == null) {
                result = fallbackSearch(pattern, root, path);
            }
            return ToolResult.success(LoomToolSupport.clip(result), false, elapsed(startedAt));
        } catch (IOException e) {
            return failure(e.getMessage(), startedAt);
        } catch (Exception e) {
            return failure(e.getMessage(), startedAt);
        }
    }

    /** Returns null if rg is unavailable; otherwise the rg output. */
    private String runRg(String pattern, Path path) {
        try {
            if (executableAvailable("rg")) {
                Process process = new ProcessBuilder("rg", "-n", "--smart-case", "--max-count", "200",
                        pattern, path.toString()).start();
                String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                process.waitFor();
                int code = process.exitValue();
                if (code == 0) {
                    return stdout.isEmpty() ? "(no matches)" : stdout;
                }
                if (code == 1) {
                    return "(no matches)";
                }
                return stderr.isEmpty() ? "(no matches)" : stderr;
            }
        } catch (Exception ignored) {
            // fall through to fallback
        }
        return null;
    }

    private boolean executableAvailable(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(":")) {
            if (Files.isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
    }

    private String fallbackSearch(String pattern, Path root, Path path) throws IOException {
        List<String> matches = new ArrayList<>();
        String lower = pattern.toLowerCase(Locale.ROOT);
        if (Files.isRegularFile(path)) {
            collectFile(root, path, lower, matches);
        } else if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.walk(path)) {
                List<Path> files = stream.filter(p -> Files.isRegularFile(p)
                                && !LoomToolSupport.isIgnoredRelative(root, p))
                        .sorted().toList();
                for (Path file : files) {
                    collectFile(root, file, lower, matches);
                    if (matches.size() >= MAX_MATCHES) {
                        break;
                    }
                }
            }
        }
        return matches.isEmpty() ? "(no matches)" : String.join("\n", matches);
    }

    private void collectFile(Path root, Path file, String lower, List<String> matches) {
        if (matches.size() >= MAX_MATCHES) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String rel = LoomToolSupport.relative(root, file);
            for (int i = 0; i < lines.size(); i++) {
                if (matches.size() >= MAX_MATCHES) {
                    return;
                }
                String line = lines.get(i);
                if (line.toLowerCase(Locale.ROOT).contains(lower)) {
                    matches.add(rel + ":" + (i + 1) + ":" + line);
                }
            }
        } catch (Exception ignored) {
            // unreadable / binary files are skipped
        }
    }

    private String text(ToolCall call, String key, String def) {
        if (call.getInput() == null || !call.getInput().has(key) || call.getInput().path(key).isNull()) {
            return def;
        }
        return call.getInput().path(key).asText(def);
    }

    private ToolResult failure(String message, long startedAt) {
        return ToolResult.failure("search_failed", message, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }
}
