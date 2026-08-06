package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

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
        try {
            Files.createDirectories(root);
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
            ObjectMapper lenient = mapper.copy()
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            AgentSession session = lenient.readValue(target.toFile(), AgentSession.class);
            if (session.getSchemaVersion() == null
                    || session.getSchemaVersion() != AgentSession.CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "session " + sessionId + " uses an incompatible schema version ("
                                + session.getSchemaVersion() + "); expected "
                                + AgentSession.CURRENT_SCHEMA_VERSION
                                + " — no automatic migration, refusing to touch the original file");
            }
            return Optional.of(session);
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
                    AgentSession session = mapper.readValue(file.toFile(), AgentSession.class);
                    if (session.getSchemaVersion() == null
                            || session.getSchemaVersion() != AgentSession.CURRENT_SCHEMA_VERSION) {
                        continue;
                    }
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
            return Optional.of(mapper.readValue(best.toFile(), AgentSession.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String sessionId) {
        try {
            Files.deleteIfExists(path(sessionId));
        } catch (IOException ignored) {
        }
    }
}
