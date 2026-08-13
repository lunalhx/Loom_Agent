package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.ResumeResult;
import cn.lunalhx.ai.domain.agent.model.state.WorkingContextMemory;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import org.apache.commons.codec.digest.DigestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Independently testable Session open/resume coordination used by the CLI.
 * Does not start Runs or call the model.
 */
public final class CliSessionBootstrap {
    private final AgentSessionRepository sessionStore;
    private final String workspace;
    private final String runtimeIdentity;

    public CliSessionBootstrap(AgentSessionRepository sessionStore, String workspace,
                               String runtimeIdentity) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.runtimeIdentity = runtimeIdentity;
    }

    public ResumeResult resume(String id) {
        Optional<AgentSession> loaded;
        try {
            loaded = sessionStore.find(id);
        } catch (IllegalArgumentException e) {
            throw new CliSessionService.OptionsException(e.getMessage());
        }
        if (loaded.isEmpty()) {
            throw new CliSessionService.OptionsException("session not found: " + id);
        }
        AgentSession s = loaded.get();
        if (!workspace.equals(s.getWorkspaceRoot())) {
            return ResumeResult.builder()
                    .kind(ResumeResult.Kind.WORKSPACE_MISMATCH)
                    .session(s)
                    .message("session " + id + " belongs to workspace "
                            + s.getWorkspaceRoot() + ", refusing to switch to " + workspace)
                    .build();
        }
        List<String> invalidated = keyFilesInvalidated(s.getKeyFiles());
        if (!invalidated.isEmpty()) {
            discardInvalidatedWorkingMemory(s, invalidated);
            Map<String, String> keyFiles = new LinkedHashMap<>(
                    s.getKeyFiles() == null ? Map.of() : s.getKeyFiles());
            invalidated.forEach(keyFiles::remove);
            s.setKeyFiles(keyFiles);
            sessionStore.save(s);
            return ResumeResult.builder()
                    .kind(ResumeResult.Kind.PARTIAL_RESUME)
                    .session(s)
                    .workingMemory(s.getWorkingMemory())
                    .invalidatedKeyFiles(invalidated)
                    .message("key files changed: " + String.join(", ", invalidated)
                            + "; stale file summaries discarded")
                    .build();
        }
        return ResumeResult.builder()
                .kind(ResumeResult.Kind.FULL_RESTORE)
                .session(s)
                .workingMemory(s.getWorkingMemory())
                .message("full restore")
                .build();
    }

    public AgentSession createFreshSession(String id, CollaborationMode mode) {
        AgentSession fresh = AgentSession.builder()
                .id(id)
                .schemaVersion(AgentSession.CURRENT_SCHEMA_VERSION)
                .workspaceRoot(workspace)
                .collaborationMode(Objects.requireNonNull(mode, "collaboration mode must not be null"))
                .createdAt(Instant.now())
                .workingMemory(new WorkingContextMemory())
                .keyFiles(new LinkedHashMap<>())
                .runtimeIdentity(runtimeIdentity)
                .build();
        return sessionStore.save(fresh);
    }

    private static void discardInvalidatedWorkingMemory(AgentSession session, List<String> invalidated) {
        WorkingContextMemory wm = session.getWorkingMemory();
        if (wm == null || invalidated.isEmpty()) {
            return;
        }
        WorkingContextMemory cleaned = new WorkingContextMemory();
        cleaned.setTaskSummary(wm.taskSummary());
        for (String file : wm.recentFiles()) {
            if (!invalidated.contains(file)) {
                cleaned.recordRecentFile(file);
            }
        }
        for (WorkingContextMemory.FileSummary summary : wm.fileSummaries().values()) {
            if (!invalidated.contains(summary.path())) {
                cleaned.putFileSummary(summary);
            }
        }
        for (WorkingContextMemory.MemoryNote note : wm.notes()) {
            cleaned.addNote(note);
        }
        session.setWorkingMemory(cleaned);
    }

    private List<String> keyFilesInvalidated(Map<String, String> keyFiles) {
        List<String> invalidated = new ArrayList<>();
        if (keyFiles == null || keyFiles.isEmpty()) {
            return invalidated;
        }
        for (Map.Entry<String, String> e : keyFiles.entrySet()) {
            String current = sha256(Path.of(workspace).resolve(e.getKey()));
            if (current == null || !current.equals(e.getValue())) {
                invalidated.add(e.getKey());
            }
        }
        return invalidated;
    }

    private static String sha256(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return DigestUtils.sha256Hex(Files.readAllBytes(file));
        } catch (Exception e) {
            return null;
        }
    }
}
