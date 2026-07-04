package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.tool.CodeSearchTool;
import cn.lunalhx.ai.infrastructure.tool.FindFilesTool;
import cn.lunalhx.ai.infrastructure.tool.ListDirectoryTool;
import cn.lunalhx.ai.infrastructure.tool.LocalWorkspacePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Verifies deterministic ordering and limit semantics for
 * code_search, find_files, and list_dir.
 *
 * <h3>Trade-off: determinism vs. resource bounds</h3>
 * <p>
 * All three tools collect up to {@code searchMaxResults} (global cap, default 50)
 * during filesystem traversal, sort the collected entries by deterministic keys,
 * then apply the user-requested limit.  This guarantees that for the same file
 * tree the output is identical regardless of filesystem entry order — as long as
 * the number of matching entries does not exceed the global cap.
 * </p>
 * <p>
 * When the tree contains more matching entries than {@code searchMaxResults},
 * the collected set depends on native traversal order, which may differ across
 * filesystems, JVM versions, or directory mutations.  That is the documented
 * trade-off: deterministic within the cap, best-effort beyond it.
 * </p>
 */
public class ToolDeterministicOrderingTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── code_search ──────────────────────────────────────────────

    @Test
    public void codeSearchShouldSortByPathThenLine() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.createDirectories(root.resolve("b"));
        Files.createDirectories(root.resolve("a"));
        // Create files in reverse alphabetical order
        Files.writeString(root.resolve("b/search.py"), "line0\nneedle_in_b\nline2\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("a/search.py"), "line0\nneedle_in_a\nline2\n", StandardCharsets.UTF_8);

        CodeSearchTool tool = codeSearchTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "needle");
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        String obs = result.getObservation();
        // a/search.py sorts before b/search.py
        int posA = obs.indexOf("a/search.py");
        int posB = obs.indexOf("b/search.py");
        assertTrue("a/search.py should appear before b/search.py", posA >= 0 && posB >= 0 && posA < posB);
    }

    @Test
    public void codeSearchSameFileShouldPreserveLineOrder() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.writeString(root.resolve("lines.py"),
                "ignore\nneedle1\nignore\nneedle2\nignore\nneedle3\n",
                StandardCharsets.UTF_8);

        CodeSearchTool tool = codeSearchTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "needle");
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        String obs = result.getObservation();
        assertTrue(obs.contains("lines.py:2: needle1"));
        assertTrue(obs.contains("lines.py:4: needle2"));
        assertTrue(obs.contains("lines.py:6: needle3"));
        int p1 = obs.indexOf("lines.py:2:");
        int p2 = obs.indexOf("lines.py:4:");
        int p3 = obs.indexOf("lines.py:6:");
        assertTrue("line 2 before line 4", p1 < p2);
        assertTrue("line 4 before line 6", p2 < p3);
    }

    @Test
    public void codeSearchDeterministicRegardlessOfCreationOrder() throws Exception {
        // Two identical file trees created in different orders should produce the same output
        String output1 = searchWithCreationOrder(List.of("zebra.py", "alpha.py", "middle.py"));
        String output2 = searchWithCreationOrder(List.of("alpha.py", "middle.py", "zebra.py"));
        assertEquals("output should be identical regardless of file creation order", output1, output2);
    }

    private String searchWithCreationOrder(List<String> orderedNames) throws Exception {
        Path root = temporaryFolder.newFolder("order-" + orderedNames.hashCode()).toPath();
        for (String name : orderedNames) {
            Files.writeString(root.resolve(name), "needle\n", StandardCharsets.UTF_8);
        }
        CodeSearchTool tool = codeSearchTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "needle");
        input.put("limit", 10);
        ToolResult result = tool.call(call(input, root));
        assertTrue(result.isSuccess());
        return result.getObservation();
    }

    @Test
    public void codeSearchLimitShouldApplyAfterSort() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        // Create files: match in z.py first, then a.py — without sorting, z would appear first
        Files.writeString(root.resolve("z.py"), "needle\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("m.py"), "needle\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("a.py"), "needle\n", StandardCharsets.UTF_8);

        CodeSearchTool tool = codeSearchTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "needle");
        input.put("limit", 2);
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        assertTrue(result.isTruncated());
        String obs = result.getObservation();
        assertTrue("a.py should be present (sorts first)", obs.contains("a.py"));
        assertTrue("m.py should be present (sorts second)", obs.contains("m.py"));
        assertFalse("z.py should be truncated (sorts third, limit=2)", obs.contains("z.py"));
    }

    @Test
    public void codeSearchShouldSkipSensitiveFiles() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.writeString(root.resolve("ok.py"), "needle\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".env"), "needle\n", StandardCharsets.UTF_8);

        CodeSearchTool tool = codeSearchTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "needle");
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("ok.py"));
        assertFalse(result.getObservation().contains(".env"));
    }

    @Test
    public void codeSearchShouldHandleSymlinkFile() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.writeString(root.resolve("real.py"), "needle\n", StandardCharsets.UTF_8);
        Path link = root.resolve("link.py");
        Files.createSymbolicLink(link, root.resolve("real.py"));

        CodeSearchTool tool = codeSearchTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "needle");
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        // Symlinked file is not a regular file, so it should be skipped
        assertFalse("symlink file should be skipped (not regular file)",
                result.getObservation().contains("link.py"));
        assertTrue("real file should be found", result.getObservation().contains("real.py"));
    }

    @Test
    public void codeSearchTruncatedFlagWhenHittingGlobalCap() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        // Create more files than searchMaxResults
        for (int i = 0; i < 60; i++) {
            Files.writeString(root.resolve("f" + i + ".py"), "needle\n", StandardCharsets.UTF_8);
        }

        CodeSearchTool tool = codeSearchTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "needle");
        input.put("limit", 100); // user asks for more than global cap
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        assertTrue("should be truncated when global cap is hit", result.isTruncated());
    }

    // ── list_dir ─────────────────────────────────────────────────

    @Test
    public void listDirDeterministicRegardlessOfCreationOrder() throws Exception {
        String output1 = listDirWithCreationOrder(List.of("z_dir", "a_dir", "m_file.txt"));
        String output2 = listDirWithCreationOrder(List.of("m_file.txt", "a_dir", "z_dir"));
        assertEquals("list_dir output should be identical regardless of creation order",
                output1, output2);
    }

    private String listDirWithCreationOrder(List<String> orderedNames) throws Exception {
        Path root = temporaryFolder.newFolder("ld-order-" + orderedNames.hashCode()).toPath();
        for (String name : orderedNames) {
            if (name.endsWith("_dir")) {
                Files.createDirectories(root.resolve(name));
            } else {
                Files.writeString(root.resolve(name), "data", StandardCharsets.UTF_8);
            }
        }
        ListDirectoryTool tool = listDirTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", ".");
        input.put("maxDepth", 1);
        ToolResult result = tool.call(call(input, root));
        assertTrue(result.isSuccess());
        return result.getObservation();
    }

    @Test
    public void listDirOutputsAlphabeticallySorted() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.createDirectories(root.resolve("sub_z"));
        Files.createDirectories(root.resolve("sub_a"));
        Files.writeString(root.resolve("zebra.txt"), "data", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("alpha.txt"), "data", StandardCharsets.UTF_8);

        ListDirectoryTool tool = listDirTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", ".");
        input.put("maxDepth", 1);
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        String obs = result.getObservation();
        int pA = obs.indexOf("alpha.txt");
        int pZ = obs.indexOf("zebra.txt");
        int pSA = obs.indexOf("sub_a");
        int pSZ = obs.indexOf("sub_z");
        assertTrue("alpha.txt < zebra.txt", pA >= 0 && pZ >= 0 && pA < pZ);
        assertTrue("sub_a < sub_z", pSA >= 0 && pSZ >= 0 && pSA < pSZ);
    }

    @Test
    public void listDirLimitShouldApplyAfterSort() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        // Create files in reverse alphabetical order: z, y, x, ..., a
        for (char c = 'z'; c >= 'a'; c--) {
            Files.writeString(root.resolve(c + ".txt"), "data", StandardCharsets.UTF_8);
        }

        // Use a small global cap
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setWorkspaceRoot(root.toRealPath().toString());
        props.setToolTimeoutMs(3000L);
        props.setSearchMaxResults(5);

        ListDirectoryTool tool = new ListDirectoryTool(props, new LocalWorkspacePort());
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", ".");
        input.put("maxDepth", 1);
        ToolResult result = tool.call(call(input, root));

        assertTrue(result.isSuccess());
        assertTrue("should be truncated when cap is hit", result.isTruncated());

        // Verify collected entries are sorted alphabetically and capped at 5.
        // With 26 files and a cap of 5, we collect the first 5 in traversal order
        // then sort them. We can't assert specific filenames, but we can verify:
        // 1. At most 5 entries appear
        // 2. They are in alphabetical order
        String obs = result.getObservation();
        String[] lines = obs.split("\n");
        assertTrue("should have at most 5 entries", lines.length <= 5);

        // Verify sorted order: each line should be <= the next line
        for (int i = 0; i < lines.length - 1; i++) {
            assertTrue("entries should be sorted: " + lines[i] + " <= " + lines[i + 1],
                    lines[i].compareTo(lines[i + 1]) <= 0);
        }
    }

    @Test
    public void listDirShouldSkipBlockedDirectories() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("target"));
        Files.createDirectories(root.resolve("node_modules"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(".git/config"), "data", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src/App.java"), "data", StandardCharsets.UTF_8);

        ListDirectoryTool tool = listDirTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", ".");
        input.put("maxDepth", 2);
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        String obs = result.getObservation();
        assertTrue("src should be visible", obs.contains("src"));
        assertFalse(".git should be blocked", obs.contains(".git"));
        assertFalse("target should be blocked", obs.contains("target"));
        assertFalse("node_modules should be blocked", obs.contains("node_modules"));
    }

    @Test
    public void listDirShouldSkipSensitiveFiles() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.writeString(root.resolve("ok.txt"), "data", StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".env"), "SECRET=1", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("id_rsa"), "key", StandardCharsets.UTF_8);

        ListDirectoryTool tool = listDirTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", ".");
        input.put("maxDepth", 1);
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        String obs = result.getObservation();
        assertTrue("ok.txt should be visible", obs.contains("ok.txt"));
        assertFalse(".env should be hidden", obs.contains(".env"));
        assertFalse("id_rsa should be hidden", obs.contains("id_rsa"));
    }

    @Test
    public void listDirSymlinkShouldAppearAsFile() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Path realDir = root.resolve("real_dir");
        Files.createDirectories(realDir);
        Files.writeString(realDir.resolve("inside.txt"), "data", StandardCharsets.UTF_8);
        Path link = root.resolve("link_to_dir");
        Files.createSymbolicLink(link, realDir);

        ListDirectoryTool tool = listDirTool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", ".");
        input.put("maxDepth", 1);
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        String obs = result.getObservation();
        // Symlink to directory appears as "F" (not traversed into, no FOLLOW_LINKS)
        assertTrue("symlink should appear as a file entry", obs.contains("link_to_dir"));
        assertTrue("real_dir should appear as directory", obs.contains("real_dir"));
        // Symlink is not traversed, so inside.txt should not appear at depth 1
        assertFalse("symlinked directory content should not be listed",
                obs.contains("inside.txt"));
    }

    // ── helpers ──────────────────────────────────────────────────

    private CodeSearchTool codeSearchTool() {
        return new CodeSearchTool(properties(), new LocalWorkspacePort());
    }

    private FindFilesTool findFilesTool() {
        return new FindFilesTool(properties(), new LocalWorkspacePort());
    }

    private ListDirectoryTool listDirTool() {
        return new ListDirectoryTool(properties(), new LocalWorkspacePort());
    }

    private ToolCall call(ObjectNode input) throws Exception {
        return call(input, temporaryFolder.getRoot().toPath());
    }

    private ToolCall call(ObjectNode input, Path workspace) throws Exception {
        return ToolCall.builder()
                .name("test")
                .input(input)
                .workspaceRoot(workspace.toRealPath())
                .build();
    }

    private AgentRuntimeProperties properties() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setWorkspaceRoot(temporaryFolder.getRoot().toPath().toString());
        props.setToolTimeoutMs(3000L);
        props.setSearchMaxResults(50);
        props.setFileMaxBytes(200000L);
        return props;
    }
}
