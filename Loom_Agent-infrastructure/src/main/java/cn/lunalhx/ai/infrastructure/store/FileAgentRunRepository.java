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

    public FileAgentRunRepository(Path workspaceRoot, ObjectMapper mapper) {
        this.root = workspaceRoot.resolve(".loom-code").resolve("runs");
        this.mapper = mapper;
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
            Path target = runFile(run.getRunId());
            Files.createDirectories(target.getParent());
            Instant now = Instant.now();
            run.setUpdatedAt(now);
            if (run.getCreatedAt() == null) {
                run.setCreatedAt(now);
            }
            AtomicFiles.write(target,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(run));
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
            return Optional.of(mapper.readValue(target.toFile(), AgentRun.class));
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
                    result.add(mapper.readValue(target.toFile(), AgentRun.class));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }
}
