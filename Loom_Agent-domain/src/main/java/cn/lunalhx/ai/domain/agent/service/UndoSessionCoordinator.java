package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentUndoSnapshot;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.UndoSnapshotStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UndoSessionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(UndoSessionCoordinator.class);

    private final WorkspaceSnapshotPort snapshotPort;
    private final UndoSnapshotRepository snapshotRepository;
    private final WorkspaceUndoLockRepository lockRepository;
    private final AgentWorkspaceResolver workspaceResolver;
    private final AgentRuntimeProperties.UndoProperties config;
    private final ObjectMapper objectMapper;

    public UndoSessionCoordinator(WorkspaceSnapshotPort snapshotPort,
                                  UndoSnapshotRepository snapshotRepository,
                                  WorkspaceUndoLockRepository lockRepository,
                                  AgentWorkspaceResolver workspaceResolver,
                                  AgentRuntimeProperties.UndoProperties config) {
        this.snapshotPort = snapshotPort;
        this.snapshotRepository = snapshotRepository;
        this.lockRepository = lockRepository;
        this.workspaceResolver = workspaceResolver;
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    public void onRunStart(AgentContext context) {
        if (!config.isEnabled()) {
            return;
        }
        if (StringUtils.isNotBlank(context.getParentRunId())) {
            return;
        }

        Path workspaceRoot = context.getResolvedWorkspace();
        if (workspaceRoot == null) {
            markRunUnavailable(context, "no_workspace", "无法解析工作区路径");
            return;
        }

        Path gitDir = workspaceRoot.resolve(".git");
        if (!java.nio.file.Files.isDirectory(gitDir)) {
            markRunUnavailable(context, "not_git_repository", "工作区不是 Git 仓库");
            return;
        }

        long lockTimeoutSec = Math.max(60, config.getCommandTimeoutMs() / 1000 * 3);
        Instant lockExpiry = Instant.now().plusSeconds(lockTimeoutSec);
        boolean acquired = lockRepository.acquire(workspaceRoot.toString(), context.getRunId(), lockExpiry);
        if (!acquired) {
            throw new WorkspaceUndoBusyException(workspaceRoot.toString());
        }

        try {
            WorkspaceSnapshotPort.SnapshotResult result =
                    snapshotPort.createBeforeSnapshot(workspaceRoot, context.getRunId());

            AgentUndoSnapshot snapshot = AgentUndoSnapshot.builder()
                    .snapshotId("undo-" + UUID.randomUUID())
                    .runId(context.getRunId())
                    .conversationId(context.getConversationId())
                    .workspace(workspaceRoot.toString())
                    .status(UndoSnapshotStatus.OPEN)
                    .beforeHeadCommit(result.headCommit())
                    .branch(result.branch())
                    .beforeWorktreeOid(result.worktreeOid())
                    .beforeIndexOid(result.indexOid())
                    .changedFileCount(0)
                    .changedByteCount(0L)
                    .version(0L)
                    .createdAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(config.getRetentionHours() * 3600L))
                    .build();

            snapshotRepository.save(snapshot);
            log.info("Undo before-snapshot created: runId={}, workspace={}",
                    context.getRunId(), workspaceRoot);

        } catch (Exception e) {
            lockRepository.release(workspaceRoot.toString(), context.getRunId());
            markRunUnavailable(context, "snapshot_create_failed", "创建快照失败: " + e.getMessage());
            log.error("Failed to create before snapshot for runId={}: {}", context.getRunId(), e.getMessage());
        }
    }

    public void suspendSnapshot(AgentContext context) {
        if (!config.isEnabled()) {
            return;
        }
        if (StringUtils.isNotBlank(context.getParentRunId())) {
            return;
        }

        AgentUndoSnapshot snapshot = snapshotRepository.findByRunId(context.getRunId()).orElse(null);
        if (snapshot == null || snapshot.getStatus() != UndoSnapshotStatus.OPEN) {
            return;
        }

        Path workspaceRoot = context.getResolvedWorkspace();
        if (workspaceRoot == null) {
            return;
        }

        try {
            WorkspaceSnapshotPort.SnapshotResult afterResult =
                    snapshotPort.createAfterSnapshot(workspaceRoot, context.getRunId());

            List<WorkspaceSnapshotPort.WorkspaceFileChange> changes =
                    snapshotPort.compareSnapshots(workspaceRoot,
                            snapshot.getBeforeWorktreeOid(), afterResult.worktreeOid());

            snapshot.setAfterWorktreeOid(afterResult.worktreeOid());
            snapshot.setAfterIndexOid(afterResult.indexOid());
            snapshot.setAfterHeadCommit(afterResult.headCommit());
            snapshot.setChangedPathsJson(toChangedPathsJson(changes));
            snapshot.setChangedFileCount(changes.size());
            snapshot.setChangedByteCount(estimateByteCount(changes, workspaceRoot));
            snapshot.setStatus(UndoSnapshotStatus.SUSPENDED);
            snapshot.setExpiresAt(Instant.now().plusSeconds(config.getRetentionHours() * 3600L));
            snapshotRepository.save(snapshot);

            log.info("Undo snapshot SUSPENDED: runId={}, files={}", context.getRunId(), changes.size());

        } catch (Exception e) {
            log.warn("Failed to suspend snapshot for runId={}: {}", context.getRunId(), e.getMessage());
        } finally {
            releaseLock(snapshot);
        }
    }

    public String onRunResume(AgentContext context) {
        if (!config.isEnabled()) {
            return null;
        }
        if (StringUtils.isNotBlank(context.getParentRunId())) {
            return null;
        }

        AgentUndoSnapshot snapshot = snapshotRepository.findByRunId(context.getRunId()).orElse(null);
        if (snapshot == null || snapshot.getStatus() != UndoSnapshotStatus.SUSPENDED) {
            return null;
        }

        Path workspaceRoot = context.getResolvedWorkspace();
        if (workspaceRoot == null) {
            return null;
        }

        long lockTimeoutSec = Math.max(60, config.getCommandTimeoutMs() / 1000 * 3);
        Instant lockExpiry = Instant.now().plusSeconds(lockTimeoutSec);
        boolean acquired = lockRepository.acquire(workspaceRoot.toString(), context.getRunId(), lockExpiry);
        if (!acquired) {
            return "workspace_undo_busy";
        }

        try {
            WorkspaceSnapshotPort.SnapshotState currentState =
                    snapshotPort.inspectCurrentState(workspaceRoot);

            if (!snapshot.getAfterHeadCommit().equals(currentState.headCommit())
                    || !StringUtils.equals(snapshot.getBranch(), currentState.branch())
                    || !snapshot.getAfterWorktreeOid().equals(currentState.worktreeOid())
                    || !snapshot.getAfterIndexOid().equals(currentState.indexOid())) {
                snapshot.setStatus(UndoSnapshotStatus.UNAVAILABLE);
                snapshot.setUnavailabilityReason("workspace_changed_while_suspended");
                snapshot.setFinalizedAt(Instant.now());
                snapshotRepository.save(snapshot);
                releaseLock(snapshot);
                log.info("Undo snapshot UNAVAILABLE (workspace changed while suspended): runId={}",
                        context.getRunId());
                return null;
            }

            snapshot.setStatus(UndoSnapshotStatus.OPEN);
            snapshot.setExpiresAt(Instant.now().plusSeconds(config.getRetentionHours() * 3600L));
            snapshotRepository.save(snapshot);

            log.info("Undo snapshot resumed: runId={}", context.getRunId());
            return null;

        } catch (Exception e) {
            releaseLock(snapshot);
            log.error("Failed to resume snapshot for runId={}: {}", context.getRunId(), e.getMessage());
            return null;
        }
    }

    public void finalizeSnapshot(AgentContext context) {
        if (!config.isEnabled()) {
            return;
        }
        if (StringUtils.isNotBlank(context.getParentRunId())) {
            return;
        }

        AgentUndoSnapshot snapshot = snapshotRepository.findByRunId(context.getRunId()).orElse(null);
        if (snapshot == null || snapshot.getStatus() != UndoSnapshotStatus.OPEN) {
            return;
        }

        Path workspaceRoot = context.getResolvedWorkspace();
        if (workspaceRoot == null) {
            markUnavailable(context.getRunId(), "no_workspace", "无法解析工作区路径");
            releaseLock(snapshot);
            return;
        }

        try {
            WorkspaceSnapshotPort.SnapshotResult afterResult =
                    snapshotPort.createAfterSnapshot(workspaceRoot, context.getRunId());

            boolean headChanged = !snapshot.getBeforeHeadCommit().equals(afterResult.headCommit());
            boolean branchChanged = !StringUtils.equals(snapshot.getBranch(), afterResult.branch());

            if (headChanged || branchChanged) {
                snapshot.setStatus(UndoSnapshotStatus.UNAVAILABLE);
                snapshot.setUnavailabilityReason("HEAD or branch changed during run");
                snapshot.setFinalizedAt(Instant.now());
                snapshot.setExpiresAt(Instant.now().plusSeconds(config.getRetentionHours() * 3600L));
                snapshotRepository.save(snapshot);
                log.info("Undo snapshot UNAVAILABLE (HEAD/branch changed): runId={}", context.getRunId());
                releaseLock(snapshot);
                return;
            }

            List<WorkspaceSnapshotPort.WorkspaceFileChange> changes =
                    snapshotPort.compareSnapshots(workspaceRoot,
                            snapshot.getBeforeWorktreeOid(), afterResult.worktreeOid());

            if (changes.isEmpty()) {
                snapshot.setStatus(UndoSnapshotStatus.NO_CHANGES);
                snapshot.setFinalizedAt(Instant.now());
                snapshotRepository.save(snapshot);
                log.info("Undo snapshot NO_CHANGES: runId={}", context.getRunId());
                releaseLock(snapshot);
                snapshotPort.deleteSnapshotRefs(workspaceRoot, context.getRunId());
                return;
            }

            int fileCount = changes.size();
            long byteCount = estimateByteCount(changes, workspaceRoot);

            if (fileCount > config.getMaxChangedFiles()
                    || byteCount > config.getMaxChangedBytes()) {
                snapshot.setStatus(UndoSnapshotStatus.UNAVAILABLE);
                snapshot.setUnavailabilityReason("snapshot_too_large: files=" + fileCount
                        + ", bytes=" + byteCount);
                snapshot.setFinalizedAt(Instant.now());
                snapshot.setExpiresAt(Instant.now().plusSeconds(config.getRetentionHours() * 3600L));
                snapshotRepository.save(snapshot);
                log.info("Undo snapshot UNAVAILABLE (too large): runId={}", context.getRunId());
                releaseLock(snapshot);
                return;
            }

            snapshot.setAfterHeadCommit(afterResult.headCommit());
            snapshot.setAfterWorktreeOid(afterResult.worktreeOid());
            snapshot.setAfterIndexOid(afterResult.indexOid());
            snapshot.setChangedPathsJson(toChangedPathsJson(changes));
            snapshot.setChangedFileCount(fileCount);
            snapshot.setChangedByteCount(byteCount);
            snapshot.setStatus(UndoSnapshotStatus.READY);
            snapshot.setFinalizedAt(Instant.now());
            snapshot.setExpiresAt(Instant.now().plusSeconds(config.getRetentionHours() * 3600L));
            snapshotRepository.save(snapshot);

            log.info("Undo snapshot READY: runId={}, files={}, bytes={}",
                    context.getRunId(), fileCount, byteCount);

        } catch (Exception e) {
            snapshot.setStatus(UndoSnapshotStatus.UNAVAILABLE);
            snapshot.setUnavailabilityReason("finalization failed: " + e.getMessage());
            snapshot.setFinalizedAt(Instant.now());
            snapshotRepository.save(snapshot);
            log.error("Failed to finalize snapshot for runId={}: {}", context.getRunId(), e.getMessage());
        } finally {
            releaseLock(snapshot);
        }
    }

    public void markUnavailable(String runId, String reason, String detail) {
        snapshotRepository.findByRunId(runId).ifPresent(snapshot -> {
            snapshot.setStatus(UndoSnapshotStatus.UNAVAILABLE);
            snapshot.setUnavailabilityReason(reason + ": " + detail);
            snapshot.setFinalizedAt(Instant.now());
            snapshotRepository.save(snapshot);
        });
    }

    /**
     * Agent loop 在到达终止事件前异常退出时调用：释放工作区 undo 锁并将快照标记为不可用，
     * 避免崩溃残留的锁阻塞同工作区的后续 run（workspace_undo_busy）。
     */
    public void onRunFailed(AgentContext context) {
        if (!config.isEnabled()) {
            return;
        }
        if (context == null || StringUtils.isBlank(context.getRunId())) {
            return;
        }
        if (StringUtils.isNotBlank(context.getParentRunId())) {
            return;
        }
        markUnavailable(context.getRunId(), "run_failed", "Agent loop failed before terminal event");
        AgentUndoSnapshot snapshot = snapshotRepository.findByRunId(context.getRunId()).orElse(null);
        if (snapshot != null) {
            releaseLock(snapshot);
        } else if (context.getResolvedWorkspace() != null) {
            lockRepository.release(context.getResolvedWorkspace().toString(), context.getRunId());
        }
    }

    private void markRunUnavailable(AgentContext context, String reason, String detail) {
        AgentUndoSnapshot snapshot = AgentUndoSnapshot.builder()
                .snapshotId("undo-" + UUID.randomUUID())
                .runId(context.getRunId())
                .conversationId(context.getConversationId())
                .workspace(context.getResolvedWorkspace() != null
                        ? context.getResolvedWorkspace().toString() : null)
                .status(UndoSnapshotStatus.UNAVAILABLE)
                .unavailabilityReason(reason + ": " + detail)
                .changedFileCount(0)
                .changedByteCount(0L)
                .version(0L)
                .createdAt(Instant.now())
                .finalizedAt(Instant.now())
                .build();
        snapshotRepository.save(snapshot);
    }

    private void releaseLock(AgentUndoSnapshot snapshot) {
        if (snapshot.getWorkspace() != null) {
            lockRepository.release(snapshot.getWorkspace(), snapshot.getRunId());
        }
    }

    private String toChangedPathsJson(List<WorkspaceSnapshotPort.WorkspaceFileChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return "[]";
        }
        List<Map<String, String>> list = new ArrayList<>();
        for (var c : changes) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("path", c.path());
            entry.put("changeType", c.changeType().name());
            list.add(entry);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize changed paths to JSON", e);
            return "[]";
        }
    }

    private long estimateByteCount(List<WorkspaceSnapshotPort.WorkspaceFileChange> changes,
                                    Path workspaceRoot) {
        long total = 0;
        for (var change : changes) {
            try {
                total += java.nio.file.Files.size(workspaceRoot.resolve(change.path()));
            } catch (java.io.IOException ignored) {
            }
        }
        return total;
    }

    public static class WorkspaceUndoBusyException extends RuntimeException {
        public WorkspaceUndoBusyException(String workspace) {
            super("workspace_undo_busy: workspace=" + workspace);
        }
    }
}
