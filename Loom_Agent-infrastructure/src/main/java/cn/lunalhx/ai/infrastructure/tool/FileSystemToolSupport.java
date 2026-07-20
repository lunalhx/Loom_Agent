package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

abstract class FileSystemToolSupport {

    private static final Set<String> SKIP_DIR_NAMES = Set.of(".git", ".idea", "target", "node_modules");
    private static final Set<String> SENSITIVE_NAMES = Set.of(
            "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa",
            "known_hosts", "authorized_keys");

    protected final AgentRuntimeProperties properties;
    private final WorkspacePort workspacePort;

    protected FileSystemToolSupport(AgentRuntimeProperties properties, WorkspacePort workspacePort) {
        this.properties = properties;
        this.workspacePort = workspacePort;
    }

    // ---- unified path resolution ----

    protected Path resolvePath(ToolCall call, String fieldName, String defaultPath) throws IOException {
        return resolveExistingPath(call, fieldName, defaultPath);
    }

    protected Path resolveExistingPath(ToolCall call, String fieldName, String defaultPath) throws IOException {
        String rawPath = text(call.getInput(), fieldName, defaultPath);
        if (rawPath == null || rawPath.isBlank()) {
            throw new IOException(fieldName + " 不能为空");
        }
        Path root = workspaceRoot(call);
        Path realPath = WorkspacePathSanitizer.existing(root, rawPath);
        validateNotSensitive(root, realPath);
        return realPath;
    }

    /**
     * Resolve a directory for traversal (find_files, code_search, list_dir).
     * Uses toRealPath to prevent ancestor symlink escapes.
     */
    protected Path resolveDirectory(ToolCall call, String fieldName, String defaultPath) throws IOException {
        String rawPath = text(call.getInput(), fieldName, defaultPath);
        if (rawPath == null || rawPath.isBlank()) {
            throw new IOException(fieldName + " 不能为空");
        }
        Path root = workspaceRoot(call);
        Path realPath = WorkspacePathSanitizer.directory(root, rawPath);
        return realPath;
    }

    protected Path resolveWritablePath(ToolCall call, String fieldName, String defaultPath) throws IOException {
        String rawPath = text(call.getInput(), fieldName, defaultPath);
        if (rawPath == null || rawPath.isBlank()) {
            throw new IOException(fieldName + " 不能为空");
        }
        Path root = workspaceRoot(call);
        Path normalized = WorkspacePathSanitizer.writable(root, rawPath);
        validateNotSensitive(root, normalized);
        return normalized;
    }

    // ---- traversal helpers ----

    /** Whether to skip this directory during tree traversal (not just final filtering). */
    protected static boolean shouldSkipDir(Path dir) {
        String name = dir.getFileName().toString();
        return SKIP_DIR_NAMES.contains(name);
    }

    /** Whether a path is blocked for traversal (skip dirs + sensitive names). */
    protected static boolean isTraversalBlocked(Path dir) {
        if (shouldSkipDir(dir)) {
            return true;
        }
        String name = dir.getFileName().toString();
        return isSensitiveFileName(name);
    }

    // ---- sensitive file helpers ----

    /**
     * Check if a file is sensitive and should not be read, searched, created, or overwritten.
     * .env.example, .env.sample, .env.template are allowed.
     */
    protected static boolean isSensitiveFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (".env".equals(lower)) {
            return true;
        }
        if (lower.startsWith(".env.")) {
            return !lower.endsWith(".example") && !lower.endsWith(".sample") && !lower.endsWith(".template");
        }
        if (lower.endsWith(".key") || lower.endsWith(".pem") || lower.endsWith(".p12")) {
            return true;
        }
        return SENSITIVE_NAMES.contains(lower);
    }

    protected void validateNotSensitive(Path root, Path path) throws IOException {
        if (isSensitivePath(path)) {
            throw new IOException("路径被禁止访问：" + root.relativize(path.toAbsolutePath().normalize()));
        }
    }

    private boolean isSensitivePath(Path path) {
        return isSensitiveFileName(path.getFileName().toString());
    }

    // ---- utility ----

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

    /**
     * Workspace-relative path with '/' separator for deterministic,
     * platform-independent output consumed by the model.
     */
    protected String relativeNormalized(ToolCall call, Path path) {
        return relative(call, path).replace('\\', '/');
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
                    && Files.size(realPath) <= runtimeProperties(call).getFileMaxBytes()
                    && !isSensitivePath(realPath)
                    && !isTraversalBlockedInPath(root, realPath);
        } catch (IOException e) {
            return false;
        }
    }

    protected boolean isAllowedPath(ToolCall call, Path path) {
        try {
            Path root = workspaceRoot(call);
            Path realPath = path.toRealPath();
            return realPath.startsWith(root)
                    && !isSensitivePath(realPath)
                    && !isTraversalBlockedInPath(root, realPath);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isTraversalBlockedInPath(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path segment : relative) {
            if (SKIP_DIR_NAMES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    // ---- UTF-8 byte check ----

    protected boolean exceedsUtf8Limit(ToolCall call, String content) {
        return content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > runtimeProperties(call).getFileMaxBytes();
    }

    protected ToolResult failure(String code, String message, long startedAt) {
        return ToolResult.failure(code, message, elapsed(startedAt));
    }

    protected long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    protected boolean timedOut(ToolCall call, long startedAt) {
        return elapsed(startedAt) > runtimeProperties(call).getToolTimeoutMs();
    }

    protected AgentRuntimeProperties runtimeProperties(ToolCall call) {
        return call != null && call.getRuntimeProperties() != null
                ? call.getRuntimeProperties() : properties;
    }

    // ---- internal ----

}
