package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.memory.adapter.port.DurableMemoryRepository;
import cn.lunalhx.ai.domain.memory.model.MemoryEntry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Workspace-scoped durable memory store:
 * {@code .loom-code/memory/index.json} (entry index) plus
 * {@code .loom-code/memory/topics/<topic>.json} topic files. No Markdown
 * memory files are created.
 */
public final class FileDurableMemoryRepository implements DurableMemoryRepository {

    private final Path root;
    private final ObjectMapper mapper;
    private final ArtifactRedactor artifactRedactor;

    public FileDurableMemoryRepository(Path workspaceRoot, ObjectMapper mapper) {
        this(workspaceRoot, mapper, new ArtifactRedactor());
    }

    public FileDurableMemoryRepository(Path workspaceRoot, ObjectMapper mapper,
                                       ArtifactRedactor artifactRedactor) {
        this.root = workspaceRoot.resolve(".loom-code").resolve("memory");
        this.mapper = mapper;
        this.artifactRedactor = artifactRedactor;
    }

    public Path root() {
        return root;
    }

    private Path indexFile() {
        return root.resolve("index.json");
    }

    private Path topicFile(String topic) {
        return root.resolve("topics").resolve(safeTopic(topic) + ".json");
    }

    private String safeTopic(String topic) {
        String sanitized = topic == null ? "misc" : topic.strip().replaceAll("[^A-Za-z0-9_-]", "_");
        return sanitized.isBlank() ? "misc" : sanitized;
    }

    @Override
    public synchronized MemoryEntry save(MemoryEntry entry) {
        try {
            if (entry.getId() == null || entry.getId().isBlank()) {
                entry.setId(UUID.randomUUID().toString());
            }
            if (entry.getSchemaVersion() == null) {
                entry.setSchemaVersion(MemoryEntry.CURRENT_SCHEMA_VERSION);
            }
            Instant now = Instant.now();
            entry.setUpdatedAt(now);
            if (entry.getCreatedAt() == null) {
                entry.setCreatedAt(now);
            }
            List<MemoryEntry> topic = readTopic(entry.getTopic());
            topic.removeIf(e -> entry.getId().equals(e.getId()));
            topic.add(entry);
            writeTopic(entry.getTopic(), topic);
            writeIndex();
            return entry;
        } catch (IOException e) {
            throw new IllegalStateException("cannot save durable memory: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized MemoryEntry upsert(MemoryEntry entry) {
        Optional<MemoryEntry> existing = findByTopicAndSubject(entry.getTopic(), entry.getSubject());
        if (existing.isPresent()) {
            entry.setId(existing.get().getId());
            entry.setCreatedAt(existing.get().getCreatedAt());
        }
        return save(entry);
    }

    @Override
    public Optional<MemoryEntry> findByTopicAndSubject(String topic, String subject) {
        return readTopic(topic).stream()
                .filter(e -> java.util.Objects.equals(e.getSubject(), subject))
                .max(Comparator.comparing(e -> e.getUpdatedAt() != null ? e.getUpdatedAt() : Instant.now()));
    }

    @Override
    public List<MemoryEntry> findByTopic(String topic) {
        return new ArrayList<>(readTopic(topic));
    }

    @Override
    public List<MemoryEntry> findAll() {
        List<MemoryEntry> all = new ArrayList<>();
        Path topics = root.resolve("topics");
        if (!Files.isDirectory(topics)) {
            return all;
        }
        try (var stream = Files.list(topics)) {
            for (Path file : (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".json"))::iterator) {
                all.addAll(readEntries(file));
            }
        } catch (IOException ignored) {
        }
        return all;
    }

    @Override
    public List<MemoryEntry> findAllNewestFirst() {
        return findAll().stream()
                .sorted(Comparator.comparing((MemoryEntry e) ->
                                e.getUpdatedAt() != null ? e.getUpdatedAt() : e.getCreatedAt() != null
                                        ? e.getCreatedAt() : Instant.now())
                        .reversed())
                .toList();
    }

    @Override
    public synchronized void delete(String id) {
        for (String topic : List.of("project_conventions", "key_decisions", "dependency_facts", "user_preferences", "misc")) {
            List<MemoryEntry> entries = readTopic(topic);
            boolean changed = entries.removeIf(e -> id.equals(e.getId()));
            if (changed) {
                try {
                    writeTopic(topic, entries);
                    writeIndex();
                } catch (IOException ignored) {
                }
                return;
            }
        }
    }

    private List<MemoryEntry> readTopic(String topic) {
        return readEntries(topicFile(topic));
    }

    private List<MemoryEntry> readEntries(Path file) {
        List<MemoryEntry> result = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return result;
        }
        try {
            ObjectMapper lenient = mapper.copy()
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            @SuppressWarnings("unchecked")
            List<MemoryEntry> parsed = lenient.readValue(file.toFile(),
                    lenient.getTypeFactory().constructCollectionType(List.class, MemoryEntry.class));
            if (parsed != null) {
                result.addAll(parsed);
            }
        } catch (IOException ignored) {
            // corrupted topic file: keep empty rather than crashing the agent
        }
        return result;
    }

    private void writeTopic(String topic, List<MemoryEntry> entries) throws IOException {
        Files.createDirectories(root.resolve("topics"));
        com.fasterxml.jackson.databind.JsonNode redacted =
                artifactRedactor.toRedactedTree(mapper, entries);
        AtomicFiles.write(topicFile(topic),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted));
    }

    private void writeIndex() throws IOException {
        List<MemoryEntry> all = findAll();
        Files.createDirectories(root);
        com.fasterxml.jackson.databind.JsonNode redacted =
                artifactRedactor.toRedactedTree(mapper, all);
        AtomicFiles.write(indexFile(),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted));
    }
}
