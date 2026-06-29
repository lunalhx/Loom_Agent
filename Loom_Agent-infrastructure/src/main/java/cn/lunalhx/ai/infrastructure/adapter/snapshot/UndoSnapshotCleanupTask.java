package cn.lunalhx.ai.infrastructure.adapter.snapshot;

import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.model.entity.AgentUndoSnapshot;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.UndoSnapshotStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

public class UndoSnapshotCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(UndoSnapshotCleanupTask.class);

    private final UndoSnapshotRepository snapshotRepository;
    private final WorkspaceUndoLockRepository lockRepository;
    private final WorkspaceSnapshotPort snapshotPort;
    private final AgentRuntimeProperties.UndoProperties config;

    public UndoSnapshotCleanupTask(UndoSnapshotRepository snapshotRepository,
                                    WorkspaceUndoLockRepository lockRepository,
                                    WorkspaceSnapshotPort snapshotPort,
                                    AgentRuntimeProperties.UndoProperties config) {
        this.snapshotRepository = snapshotRepository;
        this.lockRepository = lockRepository;
        this.snapshotPort = snapshotPort;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${loom.agent.undo.cleanup-interval-ms:3600000}")
    public void cleanup() {
        log.debug("Starting undo snapshot cleanup");
        try {
            Instant now = Instant.now();
            List<AgentUndoSnapshot> expired = snapshotRepository.findExpired(now);
            for (AgentUndoSnapshot snapshot : expired) {
                UndoSnapshotStatus from = snapshot.getStatus();
                int rows = snapshotRepository.expireByStatus(
                        snapshot.getSnapshotId(), from, UndoSnapshotStatus.EXPIRED);
                if (rows > 0) {
                    log.info("Expired undo snapshot: runId={}, was={}", snapshot.getRunId(), from);
                    deleteRefs(snapshot);
                }
            }
            if (!expired.isEmpty()) {
                log.info("Expired {} undo snapshots", expired.size());
            }
        } catch (Exception e) {
            log.warn("Failed to expire old snapshots: {}", e.getMessage());
        }

        try {
            int staleLocks = lockRepository.deleteStaleBefore(Instant.now());
            if (staleLocks > 0) {
                log.info("Cleaned up {} stale workspace undo locks", staleLocks);
            }
        } catch (Exception e) {
            log.warn("Failed to clean stale locks: {}", e.getMessage());
        }
    }

    private void deleteRefs(AgentUndoSnapshot snapshot) {
        if (snapshot.getWorkspace() == null) {
            return;
        }
        try {
            Path workspacePath = Paths.get(snapshot.getWorkspace());
            snapshotPort.deleteSnapshotRefs(workspacePath, snapshot.getRunId());
        } catch (Exception e) {
            log.debug("Failed to delete refs for expired snapshot runId={}: {}",
                    snapshot.getRunId(), e.getMessage());
        }
    }
}
