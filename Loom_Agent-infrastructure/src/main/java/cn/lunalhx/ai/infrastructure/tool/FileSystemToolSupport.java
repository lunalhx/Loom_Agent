package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

abstract class FileSystemToolSupport {

    protected final AgentRuntimeProperties properties;
    private final WorkspacePort workspacePort;

    protected FileSystemToolSupport(AgentRuntimeProperties properties) {
        this(properties, new LocalWorkspacePort());
    }

    protected FileSystemToolSupport(AgentRuntimeProperties properties, WorkspacePort workspacePort) {
        this.properties = properties;
        this.workspacePort = workspacePort;
    }

    protected Path resolvePath(ToolCall call, String fieldName, String defaultPath) throws IOException {
        return resolveExistingPath(call, fieldName, defaultPath);
    }

    protected Path resolveExistingPath(ToolCall call, String fieldName, String defaultPath) throws IOException {
        String rawPath = text(call.getInput(), fieldName, defaultPath);
        if (rawPath == null || rawPath.isBlank()) {
            throw new IOException(fieldName + " 不能为空");
        }
        Path root = workspaceRoot(call);
        Path candidate = toCandidate(root, rawPath);
        if (!Files.exists(candidate)) {
            throw new IOException("路径不存在：" + rawPath);
        }
        Path realPath = candidate.toRealPath();
        validateInsideWorkspace(root, realPath, rawPath);
        validateNotBlocked(root, realPath);
        return realPath;
    }

    protected Path resolveWritablePath(ToolCall call, String fieldName, String defaultPath) throws IOException {
        String rawPath = text(call.getInput(), fieldName, defaultPath);
        if (rawPath == null || rawPath.isBlank()) {
            throw new IOException(fieldName + " 不能为空");
        }
        Path root = workspaceRoot(call);
        Path candidate = toCandidate(root, rawPath);
        Path parent = candidate.getParent();
        if (parent == null || !Files.exists(parent)) {
            throw new IOException("父目录不存在：" + rawPath);
        }
        Path realParent = parent.toRealPath();
        validateInsideWorkspace(root, realParent, rawPath);
        Path normalized = realParent.resolve(candidate.getFileName()).normalize().toAbsolutePath();
        validateInsideWorkspace(root, normalized, rawPath);
        validateNotBlocked(root, normalized);
        return normalized;
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

    protected String relative(ToolCall call, Path path) {
        try {
            return workspaceRoot(call).relativize(path.toAbsolutePath().normalize()).toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected Path workspaceRoot(ToolCall call) throws IOException {
        if (call == null || call.getWorkspaceRoot() == null) {
            return workspacePort.requireLocalRoot(call);
        }
        return workspacePort.requireLocalRoot(call);
    }

    protected boolean isAllowedRegularFile(ToolCall call, Path path) {
        try {
            Path root = workspaceRoot(call);
            Path realPath = path.toRealPath();
            return Files.isRegularFile(realPath)
                    && realPath.startsWith(root)
                    && Files.size(realPath) <= properties.getFileMaxBytes()
                    && !isBlocked(root, realPath);
        } catch (IOException e) {
            return false;
        }
    }

    protected boolean isAllowedPath(ToolCall call, Path path) {
        try {
            Path root = workspaceRoot(call);
            Path realPath = path.toRealPath();
            return realPath.startsWith(root) && !isBlocked(root, realPath);
        } catch (IOException e) {
            return false;
        }
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

    private Path toCandidate(Path root, String rawPath) {
        Path raw = Path.of(rawPath);
        return raw.isAbsolute()
                ? raw.normalize().toAbsolutePath()
                : root.resolve(raw).normalize().toAbsolutePath();
    }

    private void validateInsideWorkspace(Path root, Path path, String rawPath) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new IOException("路径越权：" + rawPath);
        }
    }

    private void validateNotBlocked(Path root, Path path) throws IOException {
        if (isBlocked(root, path)) {
            throw new IOException("路径被禁止访问：" + root.relativize(path.toAbsolutePath().normalize()));
        }
    }

    private boolean isBlocked(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
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
