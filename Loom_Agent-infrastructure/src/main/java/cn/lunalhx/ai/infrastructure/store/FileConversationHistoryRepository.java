package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * File-backed Conversation History under
 * {@code .loom-code/histories/<sessionId>.json}. Append-only: existing entries
 * must remain an unchanged prefix of any later save.
 */
public final class FileConversationHistoryRepository implements ConversationHistoryRepository {

    private final Path root;
    private final ObjectMapper mapper;
    private final ArtifactRedactor artifactRedactor;

    public FileConversationHistoryRepository(Path workspaceRoot, ObjectMapper mapper) {
        this(workspaceRoot, mapper, new ArtifactRedactor());
    }

    public FileConversationHistoryRepository(Path workspaceRoot, ObjectMapper mapper,
                                             ArtifactRedactor artifactRedactor) {
        this.root = workspaceRoot.resolve(".loom-code").resolve("histories");
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
    public Optional<ConversationHistoryDocument> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Path target = path(sessionId);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(target);
            if (!raw.contains("\"schemaVersion\"")) {
                throw new IllegalArgumentException(
                        "conversation history " + sessionId
                                + " uses an incompatible (legacy) schema — "
                                + "no automatic migration is performed: " + target);
            }
            ConversationHistoryDocument document =
                    mapper.readValue(target.toFile(), ConversationHistoryDocument.class);
            validateCurrent(document, sessionId, target);
            return Optional.of(document);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "conversation history file is corrupted and will not be overwritten: " + target, e);
        }
    }

    @Override
    public ConversationHistoryDocument save(ConversationHistoryDocument document) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(document.getSessionId(), "sessionId");
        if (document.getSchemaVersion() == null) {
            document.setSchemaVersion(ConversationHistoryDocument.CURRENT_SCHEMA_VERSION);
        }
        validateCurrent(document, document.getSessionId(), path(document.getSessionId()));
        Optional<ConversationHistoryDocument> existing = Optional.empty();
        Path target = path(document.getSessionId());
        if (Files.isRegularFile(target)) {
            existing = find(document.getSessionId());
        }
        if (existing.isPresent()) {
            assertAppendOnly(existing.get(), document, target);
        }
        try {
            Files.createDirectories(root);
            JsonNode redacted = artifactRedactor.toRedactedTree(mapper, document);
            AtomicFiles.write(target,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted));
            return document;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot save conversation history: " + e.getMessage(), e);
        }
    }

    private void validateCurrent(ConversationHistoryDocument document, String sessionId, Path target) {
        if (document.getSchemaVersion() == null
                || document.getSchemaVersion() != ConversationHistoryDocument.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "conversation history " + sessionId + " uses an incompatible schema version ("
                            + document.getSchemaVersion() + "); expected "
                            + ConversationHistoryDocument.CURRENT_SCHEMA_VERSION
                            + " — no automatic migration, refusing to touch the original file: "
                            + target);
        }
    }

    private static void assertAppendOnly(ConversationHistoryDocument existing,
                                         ConversationHistoryDocument next,
                                         Path target) {
        List<ConversationHistoryEntry> prior =
                existing.getEntries() == null ? List.of() : existing.getEntries();
        List<ConversationHistoryEntry> newer =
                next.getEntries() == null ? List.of() : next.getEntries();
        if (newer.size() < prior.size()) {
            throw new IllegalArgumentException(
                    "conversation history is append-only; refusing to shrink "
                            + target);
        }
        for (int i = 0; i < prior.size(); i++) {
            if (!Objects.equals(prior.get(i), newer.get(i))) {
                throw new IllegalArgumentException(
                        "conversation history is append-only; refusing to mutate prior entry "
                                + i + " in " + target);
            }
        }
        if (next.getNextSequence() < existing.getNextSequence()) {
            throw new IllegalArgumentException(
                    "conversation history nextSequence must not decrease: " + target);
        }
    }
}
