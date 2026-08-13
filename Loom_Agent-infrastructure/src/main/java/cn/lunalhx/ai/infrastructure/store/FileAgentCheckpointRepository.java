package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-backed {@link AgentCheckpoint} store under
 * {@code .loom-code/checkpoints/<runId>/<version>.json}. Checkpoints carry
 * an {@link AgentContextSnapshot} with a Conversation History anchor only.
 */
public final class FileAgentCheckpointRepository implements AgentCheckpointRepository {

    private static final ConcurrentMap<Path, ReentrantLock> CHECKPOINT_LOCKS = new ConcurrentHashMap<>();

    private final Path root;
    private final ObjectMapper mapper;
    private final ArtifactRedactor artifactRedactor;

    public FileAgentCheckpointRepository(Path workspaceRoot, ObjectMapper mapper) {
        this(workspaceRoot, mapper, new ArtifactRedactor());
    }

    public FileAgentCheckpointRepository(Path workspaceRoot, ObjectMapper mapper,
                                         ArtifactRedactor artifactRedactor) {
        this.root = workspaceRoot.resolve(".loom-code").resolve("checkpoints");
        this.mapper = mapper;
        this.artifactRedactor = artifactRedactor;
    }

    public Path root() {
        return root;
    }

    public Path path(String runId, long version) {
        return root.resolve(runId).resolve(version + ".json");
    }

    @Override
    public AgentCheckpoint save(AgentCheckpoint checkpoint) {
        return withLock(checkpoint.getRunId(), () -> {
            Path dir = root.resolve(checkpoint.getRunId());
            Files.createDirectories(dir);
            long nextVersion;
            try (var stream = Files.list(dir)) {
                nextVersion = stream
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .mapToLong(p -> {
                            String name = p.getFileName().toString();
                            try {
                                return Long.parseLong(name.substring(0, name.length() - ".json".length()));
                            } catch (NumberFormatException e) {
                                return 0L;
                            }
                        })
                        .max().orElse(0L) + 1L;
            }
            checkpoint.setVersion(nextVersion);
            checkpoint.setCreatedAt(Instant.now());
            com.fasterxml.jackson.databind.JsonNode redacted =
                    artifactRedactor.toRedactedTree(mapper, checkpoint);
            AtomicFiles.write(path(checkpoint.getRunId(), nextVersion),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted));
            return checkpoint;
        });
    }

    @Override
    public Optional<AgentCheckpoint> latest(String runId) {
        Path dir = root.resolve(runId);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        Path best = null;
        long bestVersion = -1;
        try (var stream = Files.list(dir)) {
            for (Path file : (Iterable<Path>) stream.filter(p -> p.getFileName().toString().endsWith(".json"))::iterator) {
                String name = file.getFileName().toString();
                try {
                    long version = Long.parseLong(name.substring(0, name.length() - ".json".length()));
                    if (version > bestVersion) {
                        bestVersion = version;
                        best = file;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        if (best == null) {
            return Optional.empty();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(best.toFile());
            com.fasterxml.jackson.databind.JsonNode snapshotNode = root.get("contextSnapshot");
            if (snapshotNode == null || snapshotNode.isNull()) {
                throw new IllegalArgumentException(
                        "checkpoint " + best + " uses an incompatible schema; "
                                + "no automatic migration is performed");
            }
            Integer version = snapshotNode.has("schemaVersion") && !snapshotNode.get("schemaVersion").isNull()
                    ? snapshotNode.get("schemaVersion").asInt() : null;
            if (version == null || version != AgentContextSnapshot.CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "checkpoint " + best + " uses an incompatible schema; "
                                + "no automatic migration is performed");
            }
            if (snapshotNode.has("ledgerEntries") || snapshotNode.has("ledgerNextSequence")) {
                throw new IllegalArgumentException(
                        "checkpoint " + best + " uses an incompatible schema; "
                                + "no automatic migration is performed");
            }
            AgentCheckpoint checkpoint = mapper.treeToValue(root, AgentCheckpoint.class);
            AgentContextSnapshot snapshot = checkpoint.getContextSnapshot();
            if (snapshot == null
                    || snapshot.getRunModeSnapshot() == null
                    || snapshot.getHistoryAnchor() == null) {
                throw new IllegalArgumentException(
                        "checkpoint " + best + " uses an incompatible schema; "
                                + "no automatic migration is performed");
            }
            return Optional.of(checkpoint);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "checkpoint " + best + " uses an incompatible schema; "
                            + "no automatic migration is performed", e);
        }
    }

    private Path lockPath(String runId) {
        return root.resolve(runId).resolve("checkpoint.lock");
    }

    private <T> T withLock(String runId, Locked<T> action) {
        ReentrantLock lock = CHECKPOINT_LOCKS.computeIfAbsent(
                root.resolve(runId).toAbsolutePath().normalize(),
                ignored -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(root.resolve(runId));
            try (FileChannel channel = FileChannel.open(lockPath(runId),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.run();
            }
        } catch (FileLockInterruptionException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cannot lock checkpoint " + runId + ": interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot save checkpoint: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface Locked<T> {
        T run() throws IOException;
    }
}
