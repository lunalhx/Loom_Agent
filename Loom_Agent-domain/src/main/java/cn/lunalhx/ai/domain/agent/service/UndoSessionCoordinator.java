package cn.lunalhx.ai.domain.agent.service;

import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.AgentUndoSnapshot;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.UndoSnapshotStatus;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public class UndoSessionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(UndoSessionCoordinator.class);

    private final WorkspaceSnapshotPort snapshotPort;
    private final UndoSnapshotRepository snapshotRepository;
    private final WorkspaceUndoLockRepository lockRepository;
    private final AgentWorkspaceResolver workspaceResolver;
    private final AgentRuntimeProperties.UndoProperties config;

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

        Instant lockExpiry = Instant.now().plusSeconds(config.getRetentionHours() * 3600L);
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
                    .changedFileCount(result.fileCount())
                    .changedByteCount(result.byteCount())
                    .version(0L)
                    .createdAt(Instant.now())
                    .build();

            snapshotRepository.save(snapshot);
            log.info("Undo before-snapshot created: runId={}, workspace={}, files={}",
                    context.getRunId(), workspaceRoot, result.fileCount());

        } catch (Exception e) {
            lockRepository.release(workspaceRoot.toString(), context.getRunId());
            markRunUnavailable(context, "snapshot_create_failed", "创建快照失败: " + e.getMessage());
            log.error("Failed to create before snapshot for runId={}: {}", context.getRunId(), e.getMessage());
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
                snapshotRepository.save(snapshot);
                log.info("Undo snapshot UNAVAILABLE (HEAD/branch changed): runId={}", context.getRunId());
                releaseLock(snapshot);
                return;
            }

            if (afterResult.fileCount() == 0) {
                snapshot.setStatus(UndoSnapshotStatus.NO_CHANGES);
                snapshot.setFinalizedAt(Instant.now());
                snapshotRepository.save(snapshot);
                log.info("Undo snapshot NO_CHANGES: runId={}", context.getRunId());
                releaseLock(snapshot);
                return;
            }

            if (afterResult.fileCount() > config.getMaxChangedFiles()
                    || afterResult.byteCount() > config.getMaxChangedBytes()) {
                snapshot.setStatus(UndoSnapshotStatus.UNAVAILABLE);
                snapshot.setUnavailabilityReason("snapshot_too_large: files=" + afterResult.fileCount()
                        + ", bytes=" + afterResult.byteCount());
                snapshot.setFinalizedAt(Instant.now());
                snapshotRepository.save(snapshot);
                log.info("Undo snapshot UNAVAILABLE (too large): runId={}", context.getRunId());
                releaseLock(snapshot);
                return;
            }

            snapshot.setAfterHeadCommit(afterResult.headCommit());
            snapshot.setAfterWorktreeOid(afterResult.worktreeOid());
            snapshot.setAfterIndexOid(afterResult.indexOid());
            snapshot.setChangedPathsJson(toJson(afterResult.changedPaths()));
            snapshot.setChangedFileCount(afterResult.fileCount());
            snapshot.setChangedByteCount(afterResult.byteCount());
            snapshot.setStatus(UndoSnapshotStatus.READY);
            snapshot.setFinalizedAt(Instant.now());
            snapshot.setExpiresAt(Instant.now().plusSeconds(config.getRetentionHours() * 3600L));
            snapshotRepository.save(snapshot);

            log.info("Undo snapshot READY: runId={}, files={}, bytes={}",
                    context.getRunId(), afterResult.fileCount(), afterResult.byteCount());

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

    private String toJson(Set<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String p : paths) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(p)).append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static class WorkspaceUndoBusyException extends RuntimeException {
        public WorkspaceUndoBusyException(String workspace) {
            super("workspace_undo_busy: workspace=" + workspace);
        }
    }
}
