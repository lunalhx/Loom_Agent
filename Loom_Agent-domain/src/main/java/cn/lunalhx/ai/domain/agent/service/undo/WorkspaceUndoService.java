package cn.lunalhx.ai.domain.agent.service.undo;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentRun;
import cn.lunalhx.ai.domain.agent.model.entity.AgentUndoSnapshot;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunStatus;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.UndoSnapshotStatus;
import cn.lunalhx.ai.domain.agent.service.workspace.AgentWorkspaceResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WorkspaceUndoService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceUndoService.class);

    private final UndoSnapshotRepository snapshotRepository;
    private final WorkspaceUndoLockRepository lockRepository;
    private final WorkspaceSnapshotPort snapshotPort;
    private final AgentRunRepository runRepository;
    private final AgentWorkspaceResolver workspaceResolver;
    private final AgentRuntimeProperties.UndoProperties config;
    private final ObjectMapper objectMapper;

    public WorkspaceUndoService(UndoSnapshotRepository snapshotRepository,
                                WorkspaceUndoLockRepository lockRepository,
                                WorkspaceSnapshotPort snapshotPort,
                                AgentRunRepository runRepository,
                                AgentWorkspaceResolver workspaceResolver,
                                AgentRuntimeProperties.UndoProperties config) {
        this.snapshotRepository = snapshotRepository;
        this.lockRepository = lockRepository;
        this.snapshotPort = snapshotPort;
        this.runRepository = runRepository;
        this.workspaceResolver = workspaceResolver;
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    public UndoStatusResult queryStatus(String runId) {
        Optional<AgentRun> runOpt = runRepository.find(runId);
        if (runOpt.isEmpty()) {
            return UndoStatusResult.notFound(runId);
        }

        Optional<AgentUndoSnapshot> snapshotOpt = snapshotRepository.findByRunId(runId);
        if (snapshotOpt.isEmpty()) {
            return UndoStatusResult.notAvailable(runId, "undo_not_available",
                    "该 run 没有撤销快照");
        }

        AgentUndoSnapshot snapshot = snapshotOpt.get();
        return buildStatusResult(snapshot);
    }

    public UndoExecuteResult executeUndo(String runId, long expectedSnapshotVersion) {
        Optional<AgentRun> runOpt = runRepository.find(runId);
        if (runOpt.isEmpty()) {
            return UndoExecuteResult.failure(runId, "undo_not_available", "run 不存在");
        }

        AgentRun run = runOpt.get();
        if (!isTerminal(run.getStatus())) {
            return UndoExecuteResult.failure(runId, "undo_not_available", "run 尚未结束，无法撤销");
        }

        Optional<AgentUndoSnapshot> snapshotOpt = snapshotRepository.findByRunId(runId);
        if (snapshotOpt.isEmpty()) {
            return UndoExecuteResult.failure(runId, "undo_not_available", "没有可撤销的快照");
        }

        AgentUndoSnapshot snapshot = snapshotOpt.get();

        if (snapshot.getStatus() == UndoSnapshotStatus.UNDONE) {
            return UndoExecuteResult.failure(runId, "undo_already_applied", "该 run 已经撤销过了");
        }

        if (snapshot.getStatus() != UndoSnapshotStatus.READY) {
            return UndoExecuteResult.failure(runId, "undo_not_available",
                    "快照状态为 " + snapshot.getStatus() + "，无法撤销");
        }

        if (snapshot.getVersion() != null && snapshot.getVersion() != expectedSnapshotVersion) {
            return UndoExecuteResult.failure(runId, "undo_not_available",
                    "快照版本不一致，请重新查询后再试");
        }

        Optional<AgentUndoSnapshot> latestSnapshot =
                snapshotRepository.findLatestByWorkspace(snapshot.getWorkspace());
        if (latestSnapshot.isPresent() && !runId.equals(latestSnapshot.get().getRunId())) {
            return UndoExecuteResult.failure(runId, "undo_not_latest",
                    "该 run 不是工作区最新的可撤销 run，请先撤销更新的 run");
        }

        Path workspacePath = Paths.get(snapshot.getWorkspace());

        if (!verifyPreconditions(snapshot, workspacePath)) {
            return UndoExecuteResult.failure(runId, "workspace_changed_after_run",
                    "工作区在 run 完成后已有改动，无法安全撤销");
        }

        boolean locked = snapshotRepository.updateStatus(
                snapshot.getSnapshotId(), UndoSnapshotStatus.READY, UndoSnapshotStatus.UNDOING);
        if (!locked) {
            return UndoExecuteResult.failure(runId, "undo_not_available",
                    "快照状态已被其他请求修改，请重试");
        }

        List<WorkspaceSnapshotPort.WorkspaceFileChange> changedFiles = parseChangedPaths(snapshot.getChangedPathsJson());

        try {
            snapshotPort.restoreToBeforeSnapshot(workspacePath, runId,
                    snapshot.getBeforeWorktreeOid(), snapshot.getBeforeIndexOid(), changedFiles);

            AgentUndoSnapshot updated = snapshotRepository.findByRunId(runId).orElse(snapshot);
            updated.setStatus(UndoSnapshotStatus.UNDONE);
            updated.setUndoneAt(Instant.now());
            snapshotRepository.save(updated);

            snapshotPort.deleteSnapshotRefs(workspacePath, runId);

            log.info("Undo completed successfully: runId={}, workspace={}, restoredFiles={}",
                    runId, snapshot.getWorkspace(), changedFiles.size());

            return UndoExecuteResult.success(runId, changedFiles.size());

        } catch (Exception e) {
            log.error("Undo failed for runId={}: {}", runId, e.getMessage(), e);
            return attemptRollback(snapshot, workspacePath, changedFiles, e);
        } finally {
            lockRepository.release(snapshot.getWorkspace(), snapshot.getRunId());
        }
    }

    private boolean verifyPreconditions(AgentUndoSnapshot snapshot, Path workspacePath) {
        long lockTimeoutSec = Math.max(60, config.getCommandTimeoutMs() / 1000 * 3);
        Instant lockExpiry = Instant.now().plusSeconds(lockTimeoutSec);
        boolean acquired = lockRepository.acquire(snapshot.getWorkspace(), snapshot.getRunId(), lockExpiry);
        if (!acquired) {
            log.warn("Cannot acquire undo lock for verification: runId={}", snapshot.getRunId());
            return false;
        }

        try {
            WorkspaceSnapshotPort.SnapshotState currentState =
                    snapshotPort.inspectCurrentState(workspacePath);

            if (!snapshot.getAfterHeadCommit().equals(currentState.headCommit())) {
                log.warn("HEAD changed after run: runId={}, expected={}, actual={}",
                        snapshot.getRunId(), snapshot.getAfterHeadCommit(), currentState.headCommit());
                return false;
            }
            if (!StringUtils.equals(snapshot.getBranch(), currentState.branch())) {
                log.warn("Branch changed after run: runId={}, expected={}, actual={}",
                        snapshot.getRunId(), snapshot.getBranch(), currentState.branch());
                return false;
            }
            if (!snapshot.getAfterWorktreeOid().equals(currentState.worktreeOid())) {
                log.warn("Worktree changed after run: runId={}, expected={}, actual={}",
                        snapshot.getRunId(), snapshot.getAfterWorktreeOid(), currentState.worktreeOid());
                return false;
            }
            if (!snapshot.getAfterIndexOid().equals(currentState.indexOid())) {
                log.warn("Index changed after run: runId={}, expected={}, actual={}",
                        snapshot.getRunId(), snapshot.getAfterIndexOid(), currentState.indexOid());
                return false;
            }

            return true;
        } catch (Exception e) {
            log.warn("Precondition verification failed for runId={}: {}", snapshot.getRunId(), e.getMessage());
            return false;
        }
    }

    private UndoExecuteResult attemptRollback(AgentUndoSnapshot snapshot, Path workspacePath,
                                               List<WorkspaceSnapshotPort.WorkspaceFileChange> changedFiles,
                                               Exception originalError) {
        try {
            snapshotPort.restoreToBeforeSnapshot(workspacePath, snapshot.getRunId(),
                    snapshot.getAfterWorktreeOid(), snapshot.getAfterIndexOid(), changedFiles);

            snapshotRepository.updateStatus(snapshot.getSnapshotId(),
                    UndoSnapshotStatus.UNDOING, UndoSnapshotStatus.READY);

            return UndoExecuteResult.failure(snapshot.getRunId(), "undo_failed_rolled_back",
                    "撤销失败，已回滚到撤销前状态: " + originalError.getMessage());
        } catch (Exception rollbackError) {
            log.error("Undo rollback also failed for runId={}: {}", snapshot.getRunId(),
                    rollbackError.getMessage(), rollbackError);

            snapshot.setStatus(UndoSnapshotStatus.FAILED);
            snapshot.setErrorInfo("Original: " + originalError.getMessage()
                    + "; Rollback: " + rollbackError.getMessage());
            snapshotRepository.save(snapshot);

            return UndoExecuteResult.failure(snapshot.getRunId(), "undo_recovery_required",
                    "撤销失败且自动恢复也失败，请手动恢复。原始错误: " + originalError.getMessage()
                            + "；回滚错误: " + rollbackError.getMessage());
        }
    }

    private UndoStatusResult buildStatusResult(AgentUndoSnapshot snapshot) {
        boolean canUndo = snapshot.getStatus() == UndoSnapshotStatus.READY;
        List<WorkspaceSnapshotPort.WorkspaceFileChange> changes = parseChangedPaths(snapshot.getChangedPathsJson());

        List<ChangedFileEntry> files = new ArrayList<>();
        for (var change : changes) {
            files.add(new ChangedFileEntry(change.path(), change.changeType().name()));
        }

        return new UndoStatusResult(
                snapshot.getRunId(),
                snapshot.getStatus().name(),
                canUndo,
                snapshot.getVersion() != null ? snapshot.getVersion() : 0,
                files,
                snapshot.getChangedFileCount() != null ? snapshot.getChangedFileCount() : 0,
                snapshot.getUnavailabilityReason(),
                snapshot.getExpiresAt() != null ? snapshot.getExpiresAt().toString() : null
        );
    }

    private List<WorkspaceSnapshotPort.WorkspaceFileChange> parseChangedPaths(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, String>> raw = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, String>>>() {});
            List<WorkspaceSnapshotPort.WorkspaceFileChange> result = new ArrayList<>();
            for (Map<String, String> entry : raw) {
                String path = entry.get("path");
                String typeStr = entry.get("changeType");
                if (path == null || path.isBlank()) {
                    continue;
                }
                WorkspaceSnapshotPort.WorkspaceFileChangeType changeType =
                        WorkspaceSnapshotPort.WorkspaceFileChangeType.MODIFIED;
                if (typeStr != null) {
                    try {
                        changeType = WorkspaceSnapshotPort.WorkspaceFileChangeType.valueOf(typeStr);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                result.add(new WorkspaceSnapshotPort.WorkspaceFileChange(path, changeType));
            }
            return result;
        } catch (JsonProcessingException e) {
            return parseLegacyStringArray(json);
        }
    }

    private List<WorkspaceSnapshotPort.WorkspaceFileChange> parseLegacyStringArray(String json) {
        String content = json.trim();
        if (!content.startsWith("[") || !content.endsWith("]")) {
            return Collections.emptyList();
        }
        content = content.substring(1, content.length() - 1);
        if (content.isBlank()) {
            return Collections.emptyList();
        }
        List<WorkspaceSnapshotPort.WorkspaceFileChange> result = new ArrayList<>();
        for (String part : content.split(",")) {
            String path = part.trim();
            if (path.startsWith("\"") && path.endsWith("\"")) {
                path = path.substring(1, path.length() - 1);
                path = path.replace("\\\"", "\"").replace("\\\\", "\\");
            }
            if (!path.isBlank()) {
                result.add(new WorkspaceSnapshotPort.WorkspaceFileChange(
                        path, WorkspaceSnapshotPort.WorkspaceFileChangeType.MODIFIED));
            }
        }
        return result;
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.BUDGET_EXCEEDED;
    }

    public record ChangedFileEntry(String path, String changeType) {}

    public record UndoStatusResult(String runId, String status, boolean canUndo,
                                    long snapshotVersion, List<ChangedFileEntry> changedFiles,
                                    int changedFileCount, String reasonCode, String expiresAt) {
        public static UndoStatusResult notFound(String runId) {
            return new UndoStatusResult(runId, "NOT_FOUND", false, 0,
                    Collections.emptyList(), 0, "run_not_found", null);
        }

        public static UndoStatusResult notAvailable(String runId, String code, String reason) {
            return new UndoStatusResult(runId, "UNAVAILABLE", false, 0,
                    Collections.emptyList(), 0, code + ": " + reason, null);
        }
    }

    public record UndoExecuteResult(String runId, boolean success, String code,
                                     String message, int restoredFileCount) {
        public static UndoExecuteResult success(String runId, int count) {
            return new UndoExecuteResult(runId, true, null, "撤销成功", count);
        }

        public static UndoExecuteResult failure(String runId, String code, String message) {
            return new UndoExecuteResult(runId, false, code, message, 0);
        }
    }
}
