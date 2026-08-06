package cn.lunalhx.ai.infrastructure.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run artifact persistence under {@code .loom-code/runs/<runId>/} mirroring
 * loom-code {@code RunStore}: {@code task_state.json}, {@code trace.jsonl}
 * (append-only) and {@code report.json}. task_state and report are written
 * atomically via temp-file replace; trace is appended line by line.
 */
public final class FileRunStore {

    private static final Logger log = LoggerFactory.getLogger(FileRunStore.class);

    private final Path root;
    private final ObjectMapper mapper;
    private final ArtifactRedactor artifactRedactor;

    public FileRunStore(Path workspaceRoot, ObjectMapper mapper) {
        this(workspaceRoot, mapper, new ArtifactRedactor());
    }

    public FileRunStore(Path workspaceRoot, ObjectMapper mapper,
                        ArtifactRedactor artifactRedactor) {
        this.root = workspaceRoot.resolve(".loom-code").resolve("runs");
        this.mapper = mapper;
        this.artifactRedactor = artifactRedactor;
    }

    public Path root() {
        return root;
    }

    public Path runDir(String runId) {
        return root.resolve(runId);
    }

    public Path taskStatePath(String runId) {
        return runDir(runId).resolve("task_state.json");
    }

    public Path tracePath(String runId) {
        return runDir(runId).resolve("trace.jsonl");
    }

    public Path reportPath(String runId) {
        return runDir(runId).resolve("report.json");
    }

    public Path startRun(String runId, Map<String, Object> taskState) {
        try {
            Path dir = runDir(runId);
            Files.createDirectories(dir);
            writeTaskState(runId, taskState);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("cannot start run " + runId + ": " + e.getMessage(), e);
        }
    }

    public void writeTaskState(String runId, Map<String, Object> taskState) {
        try {
            com.fasterxml.jackson.databind.JsonNode redacted =
                    artifactRedactor.toRedactedTree(mapper, taskState);
            AtomicFiles.write(taskStatePath(runId),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write task_state for " + runId + ": " + e.getMessage(), e);
        }
    }

    public void appendTrace(String runId, Map<String, Object> event) {
        try {
            Path path = tracePath(runId);
            Files.createDirectories(path.getParent());
            com.fasterxml.jackson.databind.JsonNode redacted =
                    artifactRedactor.toRedactedTree(mapper, event);
            String line = mapper.writeValueAsString(redacted) + "\n";
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot append trace for " + runId + ": " + e.getMessage(), e);
        }
    }

    public void writeReport(String runId, Map<String, Object> report) {
        try {
            com.fasterxml.jackson.databind.JsonNode redacted =
                    artifactRedactor.toRedactedTree(mapper, report);
            AtomicFiles.write(reportPath(runId),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(redacted));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write report for " + runId + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadTaskState(String runId) {
        try {
            return mapper.readValue(taskStatePath(runId).toFile(), Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot load task_state for " + runId, e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadReport(String runId) {
        try {
            return mapper.readValue(reportPath(runId).toFile(), Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot load report for " + runId, e);
        }
    }

    public static Map<String, Object> newTaskState(String runId, String taskId, String userRequest) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("run_id", runId);
        state.put("task_id", taskId);
        state.put("user_request", userRequest);
        state.put("status", "running");
        state.put("stop_reason", null);
        state.put("final_answer", null);
        state.put("tool_steps", 0);
        state.put("attempts", 0);
        state.put("checkpoint_id", null);
        state.put("resume_status", "none");
        state.put("tool_calls", new java.util.ArrayList<>());
        return state;
    }
}
