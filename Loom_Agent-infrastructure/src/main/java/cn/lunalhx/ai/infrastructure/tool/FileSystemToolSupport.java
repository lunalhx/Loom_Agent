package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

abstract class FileSystemToolSupport {

    protected final AgentRuntimeProperties properties;
    private final Path workspaceRoot;

    protected FileSystemToolSupport(AgentRuntimeProperties properties) {
        this.properties = properties;
        this.workspaceRoot = Paths.get(properties.getWorkspaceRoot()).toAbsolutePath().normalize();
    }

    protected Path resolvePath(JsonNode input, String fieldName, String defaultPath) throws IOException {
        String rawPath = text(input, fieldName, defaultPath);
        if (rawPath == null || rawPath.isBlank()) {
            throw new IOException(fieldName + " 不能为空");
        }
        Path resolved = workspaceRoot.resolve(rawPath).normalize().toAbsolutePath();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IOException("路径越权：" + rawPath);
        }
        if (isBlocked(resolved)) {
            throw new IOException("路径被禁止读取：" + relative(resolved));
        }
        return resolved;
    }

    protected String text(JsonNode input, String fieldName, String defaultValue) {
        if (input == null || input.isMissingNode() || input.path(fieldName).isMissingNode() || input.path(fieldName).isNull()) {
            return defaultValue;
        }
        return input.path(fieldName).asText(defaultValue);
    }

    protected int integer(JsonNode input, String fieldName, int defaultValue) {
        if (input == null || input.isMissingNode() || input.path(fieldName).isMissingNode() || input.path(fieldName).isNull()) {
            return defaultValue;
        }
        return input.path(fieldName).asInt(defaultValue);
    }

    protected String relative(Path path) {
        return workspaceRoot.relativize(path.toAbsolutePath().normalize()).toString();
    }

    protected Path workspaceRoot() {
        return workspaceRoot;
    }

    protected boolean isAllowedRegularFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) <= properties.getFileMaxBytes() && !isBlocked(path);
        } catch (IOException e) {
            return false;
        }
    }

    protected boolean isAllowedPath(Path path) {
        return !isBlocked(path);
    }

    protected ToolResult failure(String code, String message, long startedAt) {
        return ToolResult.failure(code, message, elapsed(startedAt));
    }

    protected long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    protected boolean timedOut(long startedAt) {
        return elapsed(startedAt) > properties.getToolTimeoutMs();
    }

    private boolean isBlocked(Path path) {
        Path relative = workspaceRoot.relativize(path.toAbsolutePath().normalize());
        String normalized = relative.toString().replace('\\', '/');
        if (normalized.equals("docs/env/.env") || normalized.startsWith("docs/env/.env/")) {
            return true;
        }
        for (Path segment : relative) {
            String name = segment.toString();
            if (".git".equals(name) || ".idea".equals(name) || "target".equals(name) || "node_modules".equals(name)) {
                return true;
            }
        }
        String lower = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".key") || lower.endsWith(".pem") || lower.endsWith(".p12");
    }

}
