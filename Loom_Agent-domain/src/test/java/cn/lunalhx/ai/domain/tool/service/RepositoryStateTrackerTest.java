package cn.lunalhx.ai.domain.tool.service;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepositoryStateTrackerTest {
    @Test
    public void tracksSymlinkItselfGitLogicalStateAndExcludesRuntimeArtifacts() throws Exception {
        Path workspace = Files.createTempDirectory("repo-state");
        Files.writeString(workspace.resolve("source.txt"), "before");
        Files.createSymbolicLink(workspace.resolve("link.txt"), Path.of("source.txt"));
        Files.createDirectories(workspace.resolve(".loom-code"));
        Files.writeString(workspace.resolve(".loom-code/runtime.txt"), "ignored");
        Files.createDirectories(workspace.resolve(".git/refs/heads"));
        Files.writeString(workspace.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        Files.writeString(workspace.resolve(".git/index"), "index-one");
        Files.writeString(workspace.resolve(".git/refs/heads/main"), "abc\n");
        Map<String, String> before = RepositoryStateTracker.snapshot(workspace);

        Files.writeString(workspace.resolve(".git/index"), "index-two");
        Files.writeString(workspace.resolve(".loom-code/runtime.txt"), "still ignored");
        Map<String, String> after = RepositoryStateTracker.snapshot(workspace);
        var diff = RepositoryStateTracker.diff(before, after);

        assertTrue(before.containsKey("link.txt"));
        assertTrue(before.containsKey("git:HEAD"));
        assertFalse(before.containsKey(".loom-code/runtime.txt"));
        assertTrue(diff.affectedPaths().contains("git:index"));
        assertFalse(diff.affectedPaths().contains(".loom-code/runtime.txt"));
    }
}
