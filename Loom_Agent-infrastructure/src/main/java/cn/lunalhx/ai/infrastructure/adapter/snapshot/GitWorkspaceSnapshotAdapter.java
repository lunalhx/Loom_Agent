package cn.lunalhx.ai.infrastructure.adapter.snapshot;

import cn.lunalhx.ai.domain.agent.adapter.port.WorkspaceSnapshotPort;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class GitWorkspaceSnapshotAdapter implements WorkspaceSnapshotPort {

    private static final Logger log = LoggerFactory.getLogger(GitWorkspaceSnapshotAdapter.class);

    private static final String REF_PREFIX = "refs/loom-agent/undo/";
    private static final Set<String> PASS_THROUGH_ENV = Set.of("PATH", "HOME", "USER", "LANG", "LC_ALL");

    private final AgentRuntimeProperties.UndoProperties config;

    public GitWorkspaceSnapshotAdapter(AgentRuntimeProperties.UndoProperties config) {
        this.config = config;
    }

    @Override
    public SnapshotResult createBeforeSnapshot(Path workspaceRoot, String runId) {
        return createSnapshot(workspaceRoot, runId, "before");
    }

    @Override
    public SnapshotResult createAfterSnapshot(Path workspaceRoot, String runId) {
        return createSnapshot(workspaceRoot, runId, "after");
    }

    private SnapshotResult createSnapshot(Path workspaceRoot, String runId, String kind) {
        Path gitDir = workspaceRoot.resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            throw new SnapshotException("not_git_repository", "workspace is not a git repository: " + workspaceRoot);
        }

        String headCommit = gitRevParse(workspaceRoot, "HEAD");
        if (headCommit == null || headCommit.isBlank()) {
            throw new SnapshotException("no_commits", "repository has no commits: " + workspaceRoot);
        }

        String branch = gitRevParse(workspaceRoot, "--abbrev-ref", "HEAD");
        if ("HEAD".equals(branch)) {
            branch = null;
        }

        checkMergeConflicts(workspaceRoot);

        String realIndexOid = gitWriteTree(workspaceRoot, null);

        Path tempIndex = createTempIndex(workspaceRoot, headCommit);

        String worktreeOid = gitWriteTree(workspaceRoot, tempIndex);

        gitUpdateRef(workspaceRoot, REF_PREFIX + runId + "/" + kind + "-worktree", worktreeOid);
        gitUpdateRef(workspaceRoot, REF_PREFIX + runId + "/" + kind + "-index", realIndexOid);

        try {
            Files.deleteIfExists(tempIndex);
        } catch (IOException ignored) {
        }

        return new SnapshotResult(headCommit, branch, worktreeOid, realIndexOid);
    }

    @Override
    public SnapshotState inspectCurrentState(Path workspaceRoot) {
        Path gitDir = workspaceRoot.resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            throw new SnapshotException("not_git_repository", "workspace is not a git repository: " + workspaceRoot);
        }

        String headCommit = gitRevParse(workspaceRoot, "HEAD");
        if (headCommit == null || headCommit.isBlank()) {
            throw new SnapshotException("no_commits", "repository has no commits: " + workspaceRoot);
        }

        String branch = gitRevParse(workspaceRoot, "--abbrev-ref", "HEAD");
        if ("HEAD".equals(branch)) {
            branch = null;
        }

        String indexOid = gitWriteTree(workspaceRoot, null);

        Path tempIndex = createTempIndex(workspaceRoot, headCommit);
        String worktreeOid;
        try {
            worktreeOid = gitWriteTree(workspaceRoot, tempIndex);
        } finally {
            try {
                Files.deleteIfExists(tempIndex);
            } catch (IOException ignored) {
            }
        }

        return new SnapshotState(headCommit, branch, worktreeOid, indexOid);
    }

    @Override
    public List<WorkspaceFileChange> compareSnapshots(Path workspaceRoot,
                                                       String beforeTreeOid, String afterTreeOid) {
        String diff = runGitCapture(workspaceRoot, config.getCommandTimeoutMs(),
                "diff", "--name-status", "--no-renames", "-z", beforeTreeOid, afterTreeOid);
        if (diff == null || diff.isBlank()) {
            return Collections.emptyList();
        }
        return parseNameStatus(diff);
    }

    static List<WorkspaceFileChange> parseNameStatus(String raw) {
        List<WorkspaceFileChange> changes = new ArrayList<>();
        String[] fields = raw.split("\0", -1);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            String status = fields[i];
            String path = fields[i + 1];
            if (status.isEmpty() || path.isEmpty()) {
                continue;
            }

            WorkspaceFileChangeType type;
            switch (status.charAt(0)) {
                case 'A': type = WorkspaceFileChangeType.ADDED; break;
                case 'D': type = WorkspaceFileChangeType.DELETED; break;
                case 'M': type = WorkspaceFileChangeType.MODIFIED; break;
                default: type = WorkspaceFileChangeType.MODIFIED; break;
            }
            changes.add(new WorkspaceFileChange(path, type));
        }
        return changes;
    }

    @Override
    public void restoreToBeforeSnapshot(Path workspaceRoot, String runId,
                                        String beforeWorktreeOid, String beforeIndexOid,
                                        List<WorkspaceFileChange> changedFiles) {
        Path gitDir = workspaceRoot.resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            throw new SnapshotException("not_git_repository", "workspace is not a git repository");
        }

        runGit(workspaceRoot, config.getCommandTimeoutMs(),
                "read-tree", beforeWorktreeOid);

        runGit(workspaceRoot, config.getCommandTimeoutMs(),
                "checkout-index", "-a", "-f");

        Set<String> currentTracked = listTrackedFiles(workspaceRoot);
        for (WorkspaceFileChange change : changedFiles) {
            if (change.changeType() == WorkspaceFileChangeType.ADDED) {
                if (!currentTracked.contains(change.path())) {
                    Path filePath = workspaceRoot.resolve(change.path()).normalize();
                    if (!filePath.startsWith(workspaceRoot)) {
                        continue;
                    }
                    try {
                        Files.deleteIfExists(filePath);
                        cleanEmptyParents(workspaceRoot, filePath.getParent());
                    } catch (IOException e) {
                        log.warn("Failed to delete new file during undo: {} — {}", change.path(), e.getMessage());
                    }
                }
            }
        }

        Path beforeIndexPath = null;
        try {
            beforeIndexPath = writeTreeToTempFile(workspaceRoot, beforeIndexOid);
            runGit(workspaceRoot, config.getCommandTimeoutMs(),
                    "read-tree", beforeIndexOid);

            String currentIndexOid = gitWriteTree(workspaceRoot, null);
            if (!beforeIndexOid.equals(currentIndexOid)) {
                List<String> paths = changedFiles.stream()
                        .map(WorkspaceFileChange::path)
                        .toList();
                restoreIndexEntries(workspaceRoot, beforeIndexOid, new java.util.LinkedHashSet<>(paths));
            }
        } finally {
            if (beforeIndexPath != null) {
                try {
                    Files.deleteIfExists(beforeIndexPath);
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public void deleteSnapshotRefs(Path workspaceRoot, String runId) {
        for (String kind : List.of("before", "after")) {
            for (String suffix : List.of("worktree", "index")) {
                String ref = REF_PREFIX + runId + "/" + kind + "-" + suffix;
                try {
                    runGit(workspaceRoot, config.getCommandTimeoutMs(),
                            "update-ref", "-d", ref);
                } catch (SnapshotException e) {
                    if (!e.getMessage().contains("not a ref")) {
                        log.debug("Could not delete ref {}: {}", ref, e.getMessage());
                    }
                }
            }
        }
    }

    // ---- internal helpers ----

    private void checkMergeConflicts(Path workspaceRoot) {
        Path mergeHead = workspaceRoot.resolve(".git/MERGE_HEAD");
        Path cherryPickHead = workspaceRoot.resolve(".git/CHERRY_PICK_HEAD");
        Path rebaseApply = workspaceRoot.resolve(".git/rebase-apply");
        Path rebaseMerge = workspaceRoot.resolve(".git/rebase-merge");
        if (Files.exists(mergeHead) || Files.exists(cherryPickHead)
                || Files.exists(rebaseApply) || Files.exists(rebaseMerge)) {
            throw new SnapshotException("merge_conflict",
                    "repository has unresolved merge conflicts, cannot create snapshot");
        }
    }

    private Path createTempIndex(Path workspaceRoot, String headCommit) {
        try {
            Path tempIndex = Files.createTempFile("loom-git-index-", ".tmp");
            String headTree = runGitCapture(workspaceRoot, config.getCommandTimeoutMs(),
                    "log", "-1", "--format=%T", headCommit);
            if (headTree == null || headTree.isBlank()) {
                throw new SnapshotException("head_tree_failed", "cannot resolve HEAD tree");
            }
            runGit(workspaceRoot, config.getCommandTimeoutMs(),
                    customEnv("GIT_INDEX_FILE", tempIndex.toAbsolutePath().toString()),
                    "read-tree", headTree.trim());
            runGit(workspaceRoot, config.getCommandTimeoutMs(),
                    customEnv("GIT_INDEX_FILE", tempIndex.toAbsolutePath().toString()),
                    "add", "-A", "--", ".");
            return tempIndex;
        } catch (IOException e) {
            throw new SnapshotException("temp_index_failed", "cannot create temp index: " + e.getMessage(), e);
        }
    }

    private String gitWriteTree(Path workspaceRoot, Path indexFile) {
        if (indexFile != null) {
            return runGitCapture(workspaceRoot, config.getCommandTimeoutMs(),
                    customEnv("GIT_INDEX_FILE", indexFile.toAbsolutePath().toString()),
                    "write-tree");
        }
        return runGitCapture(workspaceRoot, config.getCommandTimeoutMs(), "write-tree");
    }

    private String gitRevParse(Path workspaceRoot, String... args) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("rev-parse");
        cmd.addAll(Arrays.asList(args));
        try {
            return runGitCapture(workspaceRoot, config.getCommandTimeoutMs(),
                    cmd.toArray(new String[0]));
        } catch (SnapshotException e) {
            return null;
        }
    }

    private void gitUpdateRef(Path workspaceRoot, String ref, String oid) {
        runGit(workspaceRoot, config.getCommandTimeoutMs(),
                "update-ref", ref, oid);
    }

    private Set<String> listTrackedFiles(Path workspaceRoot) {
        String files = runGitCapture(workspaceRoot, config.getCommandTimeoutMs(),
                "ls-files");
        if (files == null || files.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(files.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private Path writeTreeToTempFile(Path workspaceRoot, String treeOid) {
        try {
            Path tmp = Files.createTempFile("loom-tree-", ".tmp");
            String content = runGitCapture(workspaceRoot, config.getCommandTimeoutMs(),
                    "ls-tree", "-r", treeOid);
            if (content != null) {
                Files.writeString(tmp, content);
            }
            return tmp;
        } catch (IOException e) {
            return null;
        }
    }

    private void restoreIndexEntries(Path workspaceRoot, String beforeIndexOid,
                                      java.util.Set<String> changedPaths) {
        for (String path : changedPaths) {
            try {
                String entry = runGitCapture(workspaceRoot, config.getCommandTimeoutMs(),
                        "ls-tree", beforeIndexOid, "--", path);
                if (entry != null && !entry.isBlank()) {
                    String[] parts = entry.split("\\s+");
                    if (parts.length >= 4) {
                        String mode = parts[0];
                        String oid = parts[2];
                        String name = parts[3];
                        runGit(workspaceRoot, config.getCommandTimeoutMs(),
                                "update-index", "--add", "--cacheinfo", mode, oid, name);
                    }
                }
            } catch (SnapshotException e) {
                log.debug("Could not restore index entry for {}: {}", path, e.getMessage());
            }
        }
    }

    private void cleanEmptyParents(Path workspaceRoot, Path dir) {
        while (dir != null && dir.startsWith(workspaceRoot) && !dir.equals(workspaceRoot)) {
            try {
                String[] entries = dir.toFile().list();
                if (entries != null && entries.length == 0) {
                    Files.deleteIfExists(dir);
                    dir = dir.getParent();
                } else {
                    break;
                }
            } catch (IOException e) {
                break;
            }
        }
    }

    // ---- low-level git execution ----

    private Map<String, String> customEnv(String key, String value) {
        return Map.of(key, value);
    }

    private void runGit(Path workspaceRoot, long timeoutMs, String... args) {
        runGit(workspaceRoot, timeoutMs, Collections.emptyMap(), args);
    }

    private void runGit(Path workspaceRoot, long timeoutMs, Map<String, String> extraEnv, String... args) {
        List<String> command = buildGitCommand(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);
            Map<String, String> originalEnv = Map.copyOf(builder.environment());
            builder.environment().clear();
            PASS_THROUGH_ENV.forEach(key -> {
                if (originalEnv.containsKey(key)) {
                    builder.environment().put(key, originalEnv.get(key));
                }
            });
            builder.environment().putAll(extraEnv);

            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var is = process.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        output.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                }
            }, "git-snapshot-output");
            reader.setDaemon(true);
            reader.start();

            boolean completed = process.waitFor(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                reader.join(1000L);
                throw new SnapshotException("git_timeout",
                        "git command timed out: " + String.join(" ", args));
            }
            reader.join(5000L);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new SnapshotException("git_error",
                        "git command failed with exit " + exitCode + ": " + String.join(" ", args)
                                + " — " + output.toString().trim());
            }
        } catch (SnapshotException e) {
            throw e;
        } catch (Exception e) {
            throw new SnapshotException("git_execution_error",
                    "git command execution error: " + e.getMessage(), e);
        }
    }

    private String runGitCapture(Path workspaceRoot, long timeoutMs, String... args) {
        return runGitCapture(workspaceRoot, timeoutMs, Collections.emptyMap(), args);
    }

    private String runGitCapture(Path workspaceRoot, long timeoutMs,
                                 Map<String, String> extraEnv, String... args) {
        List<String> command = buildGitCommand(args);
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);
            Map<String, String> originalEnv = Map.copyOf(builder.environment());
            builder.environment().clear();
            PASS_THROUGH_ENV.forEach(key -> {
                if (originalEnv.containsKey(key)) {
                    builder.environment().put(key, originalEnv.get(key));
                }
            });
            builder.environment().putAll(extraEnv);

            Process process = builder.start();
            byte[] bytes;
            try (var is = process.getInputStream()) {
                bytes = is.readAllBytes();
            }
            boolean completed = process.waitFor(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new SnapshotException("git_timeout",
                        "git command timed out: " + String.join(" ", args));
            }
            int exitCode = process.exitValue();
            String output = new String(bytes, StandardCharsets.UTF_8).trim();
            if (exitCode != 0) {
                throw new SnapshotException("git_error",
                        "git command failed with exit " + exitCode + ": " + String.join(" ", args)
                                + " — " + output);
            }
            return output;
        } catch (SnapshotException e) {
            throw e;
        } catch (Exception e) {
            throw new SnapshotException("git_execution_error",
                    "git command execution error: " + e.getMessage(), e);
        }
    }

    private List<String> buildGitCommand(String... args) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        return cmd;
    }

    public static class SnapshotException extends RuntimeException {
        private final String code;

        public SnapshotException(String code, String message) {
            super(message);
            this.code = code;
        }

        public SnapshotException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
