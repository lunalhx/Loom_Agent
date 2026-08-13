package cn.lunalhx.ai.infrastructure.store;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationSummary;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunKind;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * File-backed {@link AgentRun} store under
 * {@code .loom-code/runs/<runId>/run.json}. run/trace/report are all derived
 * from the same loop; this store keeps the authoritative final run state.
 */
public final class FileAgentRunRepository implements AgentRunRepository {

    private final Path root;
    private final ObjectMapper mapper;
    private final ArtifactRedactor artifactRedactor;

    public FileAgentRunRepository(Path workspaceRoot, ObjectMapper mapper) {
        this(workspaceRoot, mapper, new ArtifactRedactor());
    }

    public FileAgentRunRepository(Path workspaceRoot, ObjectMapper mapper,
                                  ArtifactRedactor artifactRedactor) {
        this.root = workspaceRoot.resolve(".loom-code").resolve("runs");
        this.mapper = mapper;
        this.artifactRedactor = artifactRedactor;
    }

    public Path root() {
        return root;
    }

    public Path runFile(String runId) {
        return root.resolve(runId).resolve("run.json");
    }

    public Path runDir(String runId) {
        return root.resolve(runId);
    }

    @Override
    public AgentRun save(AgentRun run) {
        try {
            if (run.getSchemaVersion() == null) {
                run.setSchemaVersion(AgentRun.CURRENT_SCHEMA_VERSION);
            }
            Path target = runFile(run.getRunId());
            validateCurrent(run, target);
            Optional<AgentRun> existing = find(run.getRunId());
            if (existing.isPresent()
                    && existing.get().getStatus() != null
                    && existing.get().getStatus().terminal()) {
                throw new IllegalStateException("terminal Run cannot be written: " + run.getRunId());
            }
            Files.createDirectories(target.getParent());
            Instant now = Instant.now();
            run.setUpdatedAt(now);
            if (run.getCreatedAt() == null) {
                run.setCreatedAt(now);
            }
            com.fasterxml.jackson.databind.JsonNode redacted =
                    artifactRedactor.toRedactedTree(mapper, run);
            AtomicFiles.write(target,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted));
            return run;
        } catch (IOException e) {
            throw new IllegalStateException("cannot save run " + run.getRunId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<AgentRun> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        Path target = runFile(runId);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            AgentRun run = mapper.readValue(target.toFile(), AgentRun.class);
            validateCurrent(run, target);
            return Optional.of(run);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<AgentRun> findChildren(String parentRunId) {
        if (parentRunId == null || parentRunId.isBlank()) {
            return List.of();
        }
        return allRuns().stream()
                .filter(run -> parentRunId.equals(run.getParentRunId()))
                .sorted(Comparator.comparing(run -> run.getCreatedAt() != null ? run.getCreatedAt() : Instant.now()))
                .toList();
    }

    @Override
    public Optional<AgentRun> findLatestRootByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return allRuns().stream()
                .filter(run -> conversationId.equals(run.getConversationId())
                        && run.getRunKind() == AgentRunKind.ROOT)
                .max(Comparator.comparing(run -> run.getUpdatedAt() != null
                        ? run.getUpdatedAt() : run.getCreatedAt() != null ? run.getCreatedAt() : Instant.now()));
    }

    @Override
    public List<AgentRun> findByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return allRuns().stream()
                .filter(run -> conversationId.equals(run.getConversationId()))
                .sorted(Comparator.comparing(run -> run.getCreatedAt() != null ? run.getCreatedAt() : Instant.now()))
                .toList();
    }

    @Override
    public List<ConversationSummary> listConversationSummaries() {
        return List.of();
    }

    private void validateCurrent(AgentRun run, Path target) {
        if (run.getSchemaVersion() == null
                || run.getSchemaVersion() != AgentRun.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "run " + run.getRunId() + " uses an incompatible schema version ("
                            + run.getSchemaVersion() + "); expected "
                            + AgentRun.CURRENT_SCHEMA_VERSION
                            + " — no automatic migration, refusing to touch the original file: "
                            + target);
        }
    }

    private List<AgentRun> allRuns() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<AgentRun> result = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                Path target = dir.resolve("run.json");
                if (!Files.isRegularFile(target)) {
                    continue;
                }
                try {
                    AgentRun run = mapper.readValue(target.toFile(), AgentRun.class);
                    validateCurrent(run, target);
                    result.add(run);
                } catch (Exception ignored) {
                    // incompatible or corrupted runs are skipped by list helpers
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }
}
