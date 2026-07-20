package cn.lunalhx.ai.infrastructure.context;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import cn.lunalhx.ai.domain.common.LoomPaths;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class LocalFileContextBlobStore implements ContextBlobStore {

    private static final String URI_PREFIX = "loom-agent:context-artifact:";

    private final Path storageRoot;

    public LocalFileContextBlobStore(String storageRoot) {
        this.storageRoot = Path.of(StringUtils.defaultIfBlank(storageRoot,
                LoomPaths.system().contextArtifacts().toString())).toAbsolutePath().normalize();
    }

    @Override
    public String write(String rootRunId, String artifactId, String content) {
        try {
            String safeRootRunId = safeSegment(rootRunId);
            String safeArtifactId = safeSegment(artifactId);
            Path root = canonicalStorageRoot();
            Path dir = root.resolve(safeRootRunId).normalize();
            Files.createDirectories(dir);
            Path realDir = requireInside(root, dir.toRealPath(), "rootRunId");
            Path file = requireInside(realDir, realDir.resolve(safeArtifactId + ".txt").normalize(), "artifactId");
            if (Files.isSymbolicLink(file)) {
                throw new IllegalArgumentException("artifact path cannot be a symbolic link");
            }
            Path temp = Files.createTempFile(realDir, ".context-artifact-", ".tmp");
            try {
                Files.writeString(temp, StringUtils.defaultString(content), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return URI_PREFIX + safeRootRunId + "/" + safeArtifactId;
        } catch (Exception e) {
            throw new IllegalStateException("write context artifact failed", e);
        }
    }

    @Override
    public String read(String storageUri) {
        try {
            Path root = canonicalStorageRoot();
            Path file = resolveFile(storageUri, root);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return "";
            }
            Path realFile = requireInside(root, file.toRealPath(), "storageUri");
            return Files.readString(realFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("read context artifact failed", e);
        }
    }

    @Override
    public void delete(String storageUri) {
        try {
            Path root = canonicalStorageRoot();
            Path file = resolveFile(storageUri, root);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                requireInside(root, file.getParent().toRealPath(), "storageUri");
            }
            Files.deleteIfExists(file);
        } catch (Exception e) {
            throw new IllegalStateException("delete context artifact failed: " + storageUri, e);
        }
    }

    private Path resolveFile(String storageUri, Path root) {
        if (storageUri.startsWith(URI_PREFIX)) {
            String relative = storageUri.substring(URI_PREFIX.length());
            String[] parts = relative.split("/", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid logical URI: " + storageUri);
            }
            String safeRootRunId = safeSegment(parts[0]);
            String safeArtifactId = safeSegment(parts[1]);
            return requireInside(root, root.resolve(safeRootRunId).resolve(safeArtifactId + ".txt").normalize(),
                    "storageUri");
        }
        Path absolute = Path.of(storageUri).toAbsolutePath().normalize();
        if (absolute.startsWith(storageRoot)) {
            absolute = root.resolve(storageRoot.relativize(absolute)).normalize();
        }
        return requireInside(root, absolute, "storageUri");
    }

    private Path canonicalStorageRoot() throws Exception {
        Files.createDirectories(storageRoot);
        return storageRoot.toRealPath();
    }

    private Path requireInside(Path root, Path path, String field) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException(field + " out of context storage root");
        }
        return normalized;
    }

    private String safeSegment(String value) {
        return StringUtils.defaultString(value).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

}
