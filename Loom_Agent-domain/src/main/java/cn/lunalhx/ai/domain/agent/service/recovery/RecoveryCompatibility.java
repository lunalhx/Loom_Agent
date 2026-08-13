package cn.lunalhx.ai.domain.agent.service.recovery;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentCheckpoint;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContextSnapshot;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryAnchor;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryDocument;
import cn.lunalhx.ai.domain.agent.model.entity.ConversationHistoryEntry;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfileKind;
import cn.lunalhx.ai.domain.tool.model.FrozenAuthorizationSnapshot;
import cn.lunalhx.ai.domain.tool.model.WorkspaceRef;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed Recovery Compatibility check. Called before a recovery Attempt
 * is created. Does not repair, migrate, or select an older AgentCheckpoint.
 */
public final class RecoveryCompatibility {

    private final AgentCheckpointRepository checkpoints;
    private final ConversationHistoryRepository history;
    private final Path currentWorkspace;

    public RecoveryCompatibility(AgentCheckpointRepository checkpoints,
                                 ConversationHistoryRepository history,
                                 Path currentWorkspace) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.history = Objects.requireNonNull(history, "history");
        this.currentWorkspace = Objects.requireNonNull(currentWorkspace, "currentWorkspace")
                .toAbsolutePath().normalize();
    }

    /**
     * @return empty when the Run may create a recovery Attempt; otherwise the
     *         accurate Recovery Blocked reason
     */
    public Optional<String> blockedReason(AgentRun run) {
        if (run == null || StringUtils.isBlank(run.getRunId())) {
            return Optional.of("latest AgentCheckpoint is missing");
        }
        AgentCheckpoint latest;
        try {
            Optional<AgentCheckpoint> found = checkpoints.latest(run.getRunId());
            if (found.isEmpty() || found.get().getContextSnapshot() == null) {
                return Optional.of("latest AgentCheckpoint is missing");
            }
            latest = found.get();
        } catch (RuntimeException e) {
            return Optional.of("latest AgentCheckpoint is corrupt or uses an unsupported schema");
        }
        AgentContextSnapshot snapshot = latest.getContextSnapshot();
        try {
            snapshot.ensureCurrentShape();
        } catch (RuntimeException e) {
            return Optional.of("latest AgentCheckpoint is corrupt or uses an unsupported schema");
        }
        if (!workspaceMatches(snapshot.getWorkspace())) {
            return Optional.of("workspace identity does not match");
        }
        String sessionId = StringUtils.defaultIfBlank(run.getSessionId(), snapshot.getSessionId());
        Optional<String> historyReason = historyBlockedReason(sessionId, snapshot.getHistoryAnchor());
        if (historyReason.isPresent()) {
            return historyReason;
        }
        FrozenAuthorizationSnapshot frozen = snapshot.getFrozenAuthorization();
        if (frozen == null) {
            return Optional.of("frozen authorization is missing");
        }
        try {
            frozen.toPolicy();
        } catch (RuntimeException e) {
            return Optional.of("frozen authorization is incompatible");
        }
        if (snapshot.getFrozenToolContracts() == null) {
            return Optional.of("frozen tool contracts are missing");
        }
        if (snapshot.getSkillCatalogSnapshot() == null) {
            return Optional.of("frozen skill catalog is missing");
        }
        if (Boolean.TRUE.equals(snapshot.getFullAccess())
                || snapshot.getExecutionProfileKind() == ExecutionProfileKind.DANGER_FULL_ACCESS
                || frozen.profileKind() == ExecutionProfileKind.DANGER_FULL_ACCESS) {
            return Optional.of("Full Access runs are not recoverable after process restart");
        }
        return Optional.empty();
    }

    private boolean workspaceMatches(WorkspaceRef workspace) {
        if (workspace == null || StringUtils.isBlank(workspace.getLocation())) {
            return false;
        }
        Path frozen = Path.of(workspace.getLocation()).toAbsolutePath().normalize();
        return canonical(currentWorkspace).equals(canonical(frozen));
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private Optional<String> historyBlockedReason(String sessionId, ConversationHistoryAnchor anchor) {
        if (anchor == null) {
            return Optional.of("Conversation History anchor is missing");
        }
        if (StringUtils.isBlank(sessionId)) {
            return Optional.of("Conversation History is missing");
        }
        Optional<ConversationHistoryDocument> document;
        try {
            document = history.find(sessionId);
        } catch (RuntimeException e) {
            return Optional.of("Conversation History is missing or corrupt");
        }
        if (document.isEmpty()) {
            return Optional.of("Conversation History is missing");
        }
        ConversationHistoryDocument found = document.get();
        if (found.getNextSequence() < anchor.getNextSequence()) {
            return Optional.of("Conversation History anchor is invalid");
        }
        if (anchor.getNextSequence() > 0 && anchor.getLastEntryId() == null) {
            return Optional.of("Conversation History anchor is invalid");
        }
        if (anchor.getLastEntryId() != null) {
            long expectedSequence = anchor.getNextSequence() - 1;
            ConversationHistoryEntry lastEntry = found.getEntries() == null ? null
                    : found.getEntries().stream()
                    .filter(entry -> entry.sequence() == expectedSequence)
                    .findFirst()
                    .orElse(null);
            if (lastEntry == null || !anchor.getLastEntryId().equals(lastEntry.entryId())) {
                return Optional.of("Conversation History anchor is invalid");
            }
        }
        return Optional.empty();
    }
}
