package cn.lunalhx.ai.domain.agent.adapter.port;

import java.nio.file.Path;
import java.util.List;

public interface WorkspaceSnapshotPort {

    SnapshotResult createBeforeSnapshot(Path workspaceRoot, String runId);

    SnapshotResult createAfterSnapshot(Path workspaceRoot, String runId);

    SnapshotState inspectCurrentState(Path workspaceRoot);

    List<WorkspaceFileChange> compareSnapshots(Path workspaceRoot, String beforeTreeOid, String afterTreeOid);

    void restoreToBeforeSnapshot(Path workspaceRoot, String runId,
                                 String beforeWorktreeOid, String beforeIndexOid,
                                 List<WorkspaceFileChange> changedFiles);

    void deleteSnapshotRefs(Path workspaceRoot, String runId);

    record SnapshotResult(String headCommit, String branch,
                          String worktreeOid, String indexOid) {
    }

    record SnapshotState(String headCommit, String branch,
                         String worktreeOid, String indexOid) {
    }

    record WorkspaceFileChange(String path, WorkspaceFileChangeType changeType) {
    }

    enum WorkspaceFileChangeType {
        ADDED,
        MODIFIED,
        DELETED
    }
}
