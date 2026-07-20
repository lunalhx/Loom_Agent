package cn.lunalhx.ai.infrastructure.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class WorkspacePathSanitizer {

    private WorkspacePathSanitizer() {
    }

    public static Path existing(Path workspaceRoot, String rawPath) throws IOException {
        Path root = canonicalRoot(workspaceRoot);
        Path candidate = candidate(root, rawPath);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("路径不存在：" + rawPath);
        }
        return requireInside(root, candidate.toRealPath(), rawPath);
    }

    public static Path directory(Path workspaceRoot, String rawPath) throws IOException {
        Path path = existing(workspaceRoot, rawPath);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("路径不存在或不是目录：" + rawPath);
        }
        return path;
    }

    public static Path writable(Path workspaceRoot, String rawPath) throws IOException {
        Path root = canonicalRoot(workspaceRoot);
        Path target = candidate(root, rawPath);
        Path ancestor = target;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new IOException("无法确定父目录：" + rawPath);
        }
        Path realAncestor = requireInside(root, ancestor.toRealPath(), rawPath);
        Path rebuilt = realAncestor.resolve(ancestor.relativize(target)).normalize().toAbsolutePath();
        return requireInside(root, rebuilt, rawPath);
    }

    private static Path canonicalRoot(Path workspaceRoot) throws IOException {
        if (workspaceRoot == null) {
            throw new IOException("工作区不能为空");
        }
        return workspaceRoot.toRealPath();
    }

    private static Path candidate(Path root, String rawPath) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IOException("路径不能为空");
        }
        Path raw = Path.of(rawPath);
        return (raw.isAbsolute() ? raw : root.resolve(raw)).normalize().toAbsolutePath();
    }

    private static Path requireInside(Path root, Path path, String rawPath) throws IOException {
        Path normalized = path.normalize().toAbsolutePath();
        if (!normalized.startsWith(root)) {
            throw new IOException("路径越权：" + rawPath);
        }
        return normalized;
    }
}
