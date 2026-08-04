package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

/**
 * Shared path resolution and process-execution helpers for the seven loom-code
 * tools. Mirrors loom-code {@code workspace.resolve_workspace_path}: relative
 * paths and absolute paths inside the workspace are accepted; `..` and
 * symlink escapes are rejected after resolving real paths.
 */
public final class LoomToolSupport {

    public static final Set<String> IGNORED_PATH_NAMES = Set.of(
            ".git", ".loom-code", "__pycache__", ".pytest_cache", ".ruff_cache", ".venv", "venv");

    private static final int MAX_TOOL_OUTPUT = 4000;

    private LoomToolSupport() {
    }

    public static Path root(WorkspacePort workspacePort, ToolCall call) throws IOException {
        return workspacePort.requireLocalRoot(call);
    }

    /** Resolve a raw path (relative or in-workspace absolute) to a real path inside the workspace. */
    public static Path resolve(WorkspacePort workspacePort, ToolCall call, String rawPath) throws IOException {
        Path root = root(workspacePort, call);
        if (rawPath == null || rawPath.isBlank()) {
            throw new IOException("path must not be empty");
        }
        Path raw = Path.of(rawPath);
        Path candidate = raw.isAbsolute() ? raw : root.resolve(raw);
        Path resolved;
        try {
            resolved = candidate.toRealPath();
        } catch (IOException e) {
            resolved = candidate.toAbsolutePath().normalize();
        }
        if (!resolved.startsWith(root)) {
            throw new IOException("path escapes workspace");
        }
        return resolved;
    }

    /** Like {@link #resolve} but tolerates a not-yet-existing target (write/patch on new path). */
    public static Path resolveWriteTarget(WorkspacePort workspacePort, ToolCall call, String rawPath) throws IOException {
        Path root = root(workspacePort, call);
        if (rawPath == null || rawPath.isBlank()) {
            throw new IOException("path must not be empty");
        }
        Path raw = Path.of(rawPath);
        Path candidate = (raw.isAbsolute() ? raw : root.resolve(raw)).normalize().toAbsolutePath();
        if (candidate.startsWith(root)) {
            return candidate;
        }
        // resolve the deepest existing ancestor to detect symlink escapes
        Path ancestor = candidate;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new IOException("path escapes workspace");
        }
        Path realAncestor = ancestor.toRealPath();
        Path rebuilt = realAncestor.resolve(ancestor.relativize(candidate)).normalize().toAbsolutePath();
        if (!rebuilt.startsWith(root)) {
            throw new IOException("path escapes workspace");
        }
        return rebuilt;
    }

    public static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public static boolean isIgnored(Path path) {
        return IGNORED_PATH_NAMES.contains(path.getFileName().toString());
    }

    public static boolean isIgnoredRelative(Path root, Path path) {
        Path rel = root.relativize(path.toAbsolutePath().normalize());
        for (Path part : rel) {
            if (IGNORED_PATH_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /** Clip tool output to loom-code {@code MAX_TOOL_OUTPUT} chars. */
    public static String clip(String text) {
        String s = text == null ? "" : text;
        if (s.length() <= MAX_TOOL_OUTPUT) {
            return s;
        }
        return s.substring(0, MAX_TOOL_OUTPUT) + "\n...[truncated " + (s.length() - MAX_TOOL_OUTPUT) + " chars]";
    }
}
