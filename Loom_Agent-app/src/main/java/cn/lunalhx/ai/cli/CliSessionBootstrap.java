package cn.lunalhx.ai.cli;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentSession;
import cn.lunalhx.ai.domain.agent.model.entity.ResumeResult;
import cn.lunalhx.ai.domain.agent.model.entity.TaskCheckpoint;
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
        TaskCheckpoint checkpoint = s.getCheckpoint();
        if (checkpoint == null) {
            return ResumeResult.builder()
                    .kind(ResumeResult.Kind.NO_CHECKPOINT)
                    .session(s)
                    .workingMemory(s.getWorkingMemory())
                    .message("no semantic checkpoint in session " + id)
                    .build();
        }
        List<String> invalidated = keyFilesInvalidated(checkpoint);
        if (!invalidated.isEmpty()) {
            checkpoint.setSummary(null);
            checkpoint.setKeyFiles(new LinkedHashMap<>());
            s.setCheckpoint(checkpoint);
            sessionStore.save(s);
            return ResumeResult.builder()
                    .kind(ResumeResult.Kind.PARTIAL_RESUME)
                    .session(s)
                    .checkpoint(checkpoint)
                    .workingMemory(s.getWorkingMemory())
                    .invalidatedKeyFiles(invalidated)
                    .message("key files changed: " + String.join(", ", invalidated)
                            + "; stale checkpoint summary discarded")
                    .build();
        }
        return ResumeResult.builder()
                .kind(ResumeResult.Kind.FULL_RESTORE)
                .session(s)
                .checkpoint(checkpoint)
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
                .history(new ArrayList<>())
                .workingMemory(new WorkingContextMemory())
                .checkpoint(null)
                .keyFiles(new LinkedHashMap<>())
                .runtimeIdentity(runtimeIdentity)
                .build();
        return sessionStore.save(fresh);
    }

    private List<String> keyFilesInvalidated(TaskCheckpoint checkpoint) {
        List<String> invalidated = new ArrayList<>();
        Map<String, String> keyFiles = checkpoint.getKeyFiles();
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
