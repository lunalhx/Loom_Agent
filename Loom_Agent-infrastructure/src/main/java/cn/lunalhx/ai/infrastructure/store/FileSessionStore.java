package cn.lunalhx.ai.infrastructure.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session JSON persistence under {@code .loom-code/sessions/<sessionId>.json},
 * mirroring loom-code {@code SessionStore}.
 *
 * <p>Each session keeps history, memory, checkpoints, workspaceRoot, created
 * time and runtime identity. {@code --resume latest} selects the most recently
 * modified valid session in the current workspace; workspace mismatch is a hard
 * error and corrupted sessions surface a clear error without overwriting the
 * original file.
 */
public final class FileSessionStore {

    private static final Logger log = LoggerFactory.getLogger(FileSessionStore.class);

    private final Path root;
    private final ObjectMapper mapper;

    public FileSessionStore(Path workspaceRoot, ObjectMapper mapper) {
        this.root = workspaceRoot.resolve(".loom-code").resolve("sessions");
        this.mapper = mapper;
    }

    public Path root() {
        return root;
    }

    public Path path(String sessionId) {
        return root.resolve(sessionId + ".json");
    }

    public Map<String, Object> save(Map<String, Object> session) {
        try {
            Files.createDirectories(root);
            Path target = path((String) session.get("id"));
            byte[] payload = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(session);
            AtomicFiles.write(target, payload);
            return session;
        } catch (IOException e) {
            throw new IllegalStateException("cannot save session: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> load(String sessionId) {
        Path target = path(sessionId);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("session not found: " + sessionId);
        }
        try {
            return mapper.readValue(target.toFile(), Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "session file is corrupted and will not be overwritten: " + target, e);
        }
    }

    public String latest(String workspaceRoot) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        Path best = null;
        long bestMtime = -1;
        try (var stream = Files.list(root)) {
            for (Path file : (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".json"))::iterator) {
                try {
                    Map<String, Object> session = mapper.readValue(file.toFile(), Map.class);
                    if (!workspaceRoot.equals(session.get("workspace_root"))) {
                        continue;
                    }
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    if (mtime > bestMtime) {
                        bestMtime = mtime;
                        best = file;
                    }
                } catch (Exception ignored) {
                    // corrupted sessions are skipped by latest()
                }
            }
        } catch (IOException ignored) {
        }
        if (best == null) {
            return null;
        }
        String name = best.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }

    public static Map<String, Object> newSession(String workspaceRoot) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", Instant.now().toString().replace(":", "").replace("-", "")
                + "-" + UUID.randomUUID().toString().substring(0, 6));
        session.put("created_at", Instant.now().toString());
        session.put("workspace_root", workspaceRoot);
        session.put("history", new ArrayList<>());
        session.put("memory", defaultMemory());
        session.put("checkpoints", new LinkedHashMap<>());
        session.put("runtime_identity", new LinkedHashMap<>());
        session.put("resume_state", new LinkedHashMap<>());
        return session;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> defaultMemory() {
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("task", "");
        memory.put("notes", new ArrayList<>());
        memory.put("file_summaries", new LinkedHashMap<>());
        memory.put("recent_files", new ArrayList<>());
        return memory;
    }

    public static List<Map<String, Object>> history(Map<String, Object> session) {
        Object raw = session.get("history");
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(new LinkedHashMap<>((Map<String, Object>) map));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }
}
