package cn.lunalhx.ai.infrastructure.adapter.snapshot;

import cn.lunalhx.ai.domain.agent.adapter.port.UndoSnapshotRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceUndoLockRepository;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;

public class UndoSnapshotCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(UndoSnapshotCleanupTask.class);

    private static final String REF_PREFIX = "refs/loom-agent/undo/";

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
            int expired = snapshotRepository.expireOlderThan(
                    Instant.now().minusSeconds(config.getRetentionHours() * 3600L));
            if (expired > 0) {
                log.info("Expired {} undo snapshots", expired);
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
}
