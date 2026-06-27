package cn.lunalhx.ai.domain.agent.adapter.port;

import java.nio.file.Path;
import java.util.Set;

public interface WorkspaceSnapshotPort {

    SnapshotResult createBeforeSnapshot(Path workspaceRoot, String runId);

    SnapshotResult createAfterSnapshot(Path workspaceRoot, String runId);

    void restoreToBeforeSnapshot(Path workspaceRoot, String runId,
                                 String beforeWorktreeOid, String beforeIndexOid,
                                 Set<String> changedPaths);

    void deleteSnapshotRefs(Path workspaceRoot, String runId);

    record SnapshotResult(String headCommit, String branch,
                          String worktreeOid, String indexOid,
                          Set<String> changedPaths,
                          int fileCount, long byteCount) {
    }
}
