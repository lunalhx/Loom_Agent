package cn.lunalhx.ai.infrastructure.context;

import cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class LocalFileContextBlobStore implements ContextBlobStore {

    private final Path storageRoot;

    public LocalFileContextBlobStore(String storageRoot) {
        this.storageRoot = Path.of(StringUtils.defaultIfBlank(storageRoot,
                System.getProperty("java.io.tmpdir") + "/loom-agent/context-artifacts")).toAbsolutePath().normalize();
    }

    @Override
    public String write(String rootRunId, String artifactId, String content) {
        try {
            String safeRootRunId = safeSegment(rootRunId);
            String safeArtifactId = safeSegment(artifactId);
            Path dir = storageRoot.resolve(safeRootRunId).normalize();
            if (!dir.startsWith(storageRoot)) {
                throw new IllegalArgumentException("invalid rootRunId");
            }
            Files.createDirectories(dir);
            Path file = dir.resolve(safeArtifactId + ".txt").normalize();
            if (!file.startsWith(dir)) {
                throw new IllegalArgumentException("invalid artifactId");
            }
            Files.writeString(file, StringUtils.defaultString(content), StandardCharsets.UTF_8);
            return file.toString();
        } catch (Exception e) {
            throw new IllegalStateException("write context artifact failed", e);
        }
    }

    @Override
    public String read(String storageUri) {
        try {
            Path file = Path.of(storageUri).toAbsolutePath().normalize();
            if (!file.startsWith(storageRoot)) {
                throw new IllegalArgumentException("storageUri out of context storage root");
            }
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            throw new IllegalStateException("read context artifact failed", e);
        }
    }

    private String safeSegment(String value) {
        return StringUtils.defaultString(value).replaceAll("[^a-zA-Z0-9._-]", "_");
    }

}
