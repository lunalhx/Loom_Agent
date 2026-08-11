package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.TaskCheckpoint;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-backed {@link AgentSession} store under
 * {@code .loom-code/sessions/<sessionId>.json}.
 *
 * <p>Schema is versioned on the session itself. Legacy/unknown schemas are
 * rejected with a clear error and the original file is never overwritten.
 */
public final class FileAgentSessionRepository implements AgentSessionRepository {

    private final Path root;
    private final ObjectMapper mapper;
    private final ArtifactRedactor artifactRedactor;
    private static final ConcurrentMap<Path, ReentrantLock> SESSION_LOCKS = new ConcurrentHashMap<>();

    public FileAgentSessionRepository(Path workspaceRoot, ObjectMapper mapper) {
        this(workspaceRoot, mapper, new ArtifactRedactor());
    }

    public FileAgentSessionRepository(Path workspaceRoot, ObjectMapper mapper,
                                      ArtifactRedactor artifactRedactor) {
        this.root = workspaceRoot.resolve(".loom-code").resolve("sessions");
        this.mapper = mapper;
        this.artifactRedactor = artifactRedactor;
    }

    public Path root() {
        return root;
    }

    public Path path(String sessionId) {
        return root.resolve(sessionId + ".json");
    }

    @Override
    public AgentSession save(AgentSession session) {
        ReentrantLock lock = sessionLock(session.getId());
        lock.lock();
        try {
            try {
                Files.createDirectories(root);
                try (FileChannel channel = FileChannel.open(lockPath(session.getId()),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    return saveUnlocked(session);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot lock session " + session.getId()
                        + " for save: " + e.getMessage(), e);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean saveIfUnchanged(AgentSession session, Instant expectedUpdatedAt) {
        ReentrantLock lock = sessionLock(session.getId());
        lock.lock();
        try {
            try {
                Files.createDirectories(root);
                try (FileChannel channel = FileChannel.open(lockPath(session.getId()),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    Optional<AgentSession> current = find(session.getId());
                    if (current.isEmpty()
                            || !Objects.equals(current.get().getUpdatedAt(), expectedUpdatedAt)) {
                        return false;
                    }
                    saveUnlocked(session);
                    return true;
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot lock session " + session.getId()
                        + " for compare-and-set: " + e.getMessage(), e);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AutoCloseable acquireExclusive(String sessionId) {
        ReentrantLock lock = sessionLock(sessionId);
        lock.lock();
        FileChannel channel = null;
        try {
            Files.createDirectories(root);
            channel = FileChannel.open(lockPath(sessionId),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = channel.lock();
            return new SessionLease(lock, channel, fileLock);
        } catch (IOException | RuntimeException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            lock.unlock();
            throw new IllegalStateException("cannot acquire exclusive session lock " + sessionId
                    + ": " + e.getMessage(), e);
        }
    }

    private ReentrantLock sessionLock(String sessionId) {
        return SESSION_LOCKS.computeIfAbsent(path(sessionId).toAbsolutePath().normalize(),
                ignored -> new ReentrantLock());
    }

    private Path lockPath(String sessionId) {
        return path(sessionId).resolveSibling(sessionId + ".lock");
    }

    private AgentSession saveUnlocked(AgentSession session) {
        try {
            validateCurrent(session, session.getId(), path(session.getId()));
            Files.createDirectories(root);
            Optional<AgentSession> current = Files.isRegularFile(path(session.getId()))
                    ? Optional.of(readCurrent(path(session.getId()), session.getId())) : Optional.empty();
            if (current.isPresent() && session.getUpdatedAt() != null
                    && current.get().getUpdatedAt() != null
                    && current.get().getUpdatedAt().isAfter(session.getUpdatedAt())) {
                throw new IllegalStateException("cannot overwrite a newer Session state");
            }
            session.setUpdatedAt(Instant.now());
            if (session.getCreatedAt() == null) {
                session.setCreatedAt(session.getUpdatedAt());
            }
            com.fasterxml.jackson.databind.JsonNode redacted =
                    artifactRedactor.toRedactedTree(mapper, session);
            AtomicFiles.write(path(session.getId()),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted));
            return session;
        } catch (IOException e) {
            throw new IllegalStateException("cannot save session: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<AgentSession> find(String sessionId) {
        Path target = path(sessionId);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(target);
            // Legacy .loom-code sessions are flat maps without the typed
            // schemaVersion marker; they are rejected explicitly, never migrated.
            if (!raw.contains("\"schemaVersion\"")) {
                throw new IllegalArgumentException(
                        "session " + sessionId + " uses an incompatible (legacy) schema — "
                                + "no automatic migration is performed, refusing to touch the original file: "
                                + target);
            }
            return Optional.of(readCurrent(target, sessionId));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "session file is corrupted and will not be overwritten: " + target, e);
        }
    }

    @Override
    public Optional<AgentSession> findLatest(String workspaceRoot) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        Path best = null;
        long bestMtime = -1;
        try (var stream = Files.list(root)) {
            for (Path file : (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".json"))::iterator) {
                try {
                    AgentSession session = readCurrent(file, file.getFileName().toString());
                    if (!workspaceRoot.equals(session.getWorkspaceRoot())) {
                        continue;
                    }
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    if (mtime > bestMtime) {
                        bestMtime = mtime;
                        best = file;
                    }
                } catch (Exception ignored) {
                    // corrupted or legacy sessions are skipped by findLatest
                }
            }
        } catch (IOException ignored) {
        }
        if (best == null) {
            return Optional.empty();
        }
        String name = best.getFileName().toString();
        try {
            return Optional.of(readCurrent(best, best.getFileName().toString()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private AgentSession readCurrent(Path target, String sessionId) throws IOException {
        AgentSession session = mapper.readValue(target.toFile(), AgentSession.class);
        validateCurrent(session, sessionId, target);
        return session;
    }

    private void validateCurrent(AgentSession session, String sessionId, Path target) {
        if (session.getSchemaVersion() == null
                || session.getSchemaVersion() != AgentSession.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "session " + sessionId + " uses an incompatible schema version ("
                            + session.getSchemaVersion() + "); expected "
                            + AgentSession.CURRENT_SCHEMA_VERSION
                            + " — no automatic migration, refusing to touch the original file: "
                            + target);
        }
        if (session.getCollaborationMode() == null) {
            throw new IllegalArgumentException(
                    "session " + sessionId + " has no collaboration mode; "
                            + "the current schema requires an explicit mode — "
                            + "no automatic migration, refusing to touch the original file: "
                            + target);
        }
        if (session.getCheckpoint() != null
                && (session.getCheckpoint().getSchemaVersion() == null
                || session.getCheckpoint().getSchemaVersion() != TaskCheckpoint.CURRENT_SCHEMA_VERSION
                || session.getCheckpoint().getRunModeSnapshot() == null)) {
            throw new IllegalArgumentException(
                    "session " + sessionId + " contains an incompatible checkpoint schema; "
                            + "no automatic migration, refusing to touch the original file: " + target);
        }
    }

    @Override
    public void delete(String sessionId) {
        ReentrantLock lock = sessionLock(sessionId);
        lock.lock();
        try {
            try {
                Files.createDirectories(root);
                try (FileChannel channel = FileChannel.open(lockPath(sessionId),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    Files.deleteIfExists(path(sessionId));
                }
            } catch (IOException ignored) {
            }
        } finally {
            lock.unlock();
        }
    }

    private static final class SessionLease implements AutoCloseable {

        private final ReentrantLock lock;
        private final FileChannel channel;
        private final FileLock fileLock;
        private boolean closed;

        private SessionLease(ReentrantLock lock, FileChannel channel, FileLock fileLock) {
            this.lock = lock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                fileLock.release();
                channel.close();
            } catch (IOException e) {
                throw new IllegalStateException("cannot release exclusive session lock", e);
            } finally {
                lock.unlock();
            }
        }
    }
}
