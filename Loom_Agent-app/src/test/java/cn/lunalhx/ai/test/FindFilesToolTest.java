package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.tool.FindFilesTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FindFilesToolTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void simpleGlobShouldFindFiles() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.writeString(root.resolve("helloworld.py"), "print('hello')", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("examples"));
        Files.writeString(root.resolve("examples/hello_demo.py"), "print('demo')", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("other.txt"), "other", StandardCharsets.UTF_8);

        FindFilesTool tool = tool();
        ToolResult result = tool.call(call(findInput("*hello*.py")));

        assertTrue(result.isSuccess());
        assertFalse(result.isTruncated());
        assertTrue(result.getObservation().contains("helloworld.py"));
        assertTrue(result.getObservation().contains("examples/hello_demo.py"));
        assertFalse(result.getObservation().contains("other.txt"));
    }

    @Test
    public void globShouldBeCaseInsensitiveByDefault() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.writeString(root.resolve("HelloWorld.py"), "data", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("HELLO.py"), "data", StandardCharsets.UTF_8);

        FindFilesTool tool = tool();
        ToolResult result = tool.call(call(findInput("*hello*.py")));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("HelloWorld.py"));
        assertTrue(result.getObservation().contains("HELLO.py"));
    }

    @Test
    public void globWithSlashShouldMatchRelativePaths() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("test"));
        Files.writeString(root.resolve("src/main/java/App.java"), "class App {}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("test/App.java"), "class App {}", StandardCharsets.UTF_8);

        FindFilesTool tool = tool();
        ToolResult result = tool.call(call(findInput("src/**/*.java")));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("src/main/java/App.java"));
        assertFalse(result.getObservation().contains("test/App.java"));
    }

    @Test
    public void maxDepthShouldLimitSearch() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.createDirectories(root.resolve("a/b/c/d"));
        Files.writeString(root.resolve("a/b/c/d/deep.py"), "data", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("shallow.py"), "data", StandardCharsets.UTF_8);

        FindFilesTool tool = tool();
        ObjectNode input = findInput("*.py");
        input.put("maxDepth", 2);
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("shallow.py"));
        assertFalse(result.getObservation().contains("deep.py"));
    }

    @Test
    public void limitShouldTruncateResults() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        for (int i = 0; i < 10; i++) {
            Files.writeString(root.resolve("file" + i + ".txt"), "data", StandardCharsets.UTF_8);
        }

        FindFilesTool tool = tool();
        ObjectNode input = findInput("*.txt");
        input.put("limit", 3);
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        assertTrue(result.isTruncated());
        assertEquals(3, result.getObservation().split("F ").length - 1);
    }

    @Test
    public void noMatchShouldReturnEmptySuccess() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.writeString(root.resolve("a.txt"), "data", StandardCharsets.UTF_8);

        FindFilesTool tool = tool();
        ToolResult result = tool.call(call(findInput("*.java")));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("未找到匹配文件"));
        assertFalse(result.getObservation().contains("a.txt"));
    }

    @Test
    public void shouldSkipGitDirectory() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[core]", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("readme.md"), "# readme", StandardCharsets.UTF_8);

        FindFilesTool tool = tool();
        ToolResult result = tool.call(call(findInput("*")));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("readme.md"));
        assertFalse(result.getObservation().contains(".git/config"));
    }

    @Test
    public void shouldSkipTargetDirectory() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/classes.txt"), "class", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("src.txt"), "src", StandardCharsets.UTF_8);

        FindFilesTool tool = tool();
        ToolResult result = tool.call(call(findInput("*.txt")));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("src.txt"));
        assertFalse(result.getObservation().contains("target/classes.txt"));
    }

    @Test
    public void shouldSkipNodeModulesDirectory() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.createDirectories(root.resolve("node_modules/lodash"));
        Files.writeString(root.resolve("node_modules/lodash/index.js"), "export {}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("app.js"), "import {}", StandardCharsets.UTF_8);

        FindFilesTool tool = tool();
        ToolResult result = tool.call(call(findInput("*.js")));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("app.js"));
        assertFalse(result.getObservation().contains("lodash"));
    }

    @Test
    public void shouldNotFollowSymlinkDirectories() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Path realDir = root.resolve("real");
        Files.createDirectories(realDir);
        Files.writeString(realDir.resolve("inside.txt"), "data", StandardCharsets.UTF_8);
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, realDir);

        FindFilesTool tool = tool();
        ToolResult result = tool.call(call(findInput("*.txt")));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("real/inside.txt"));
        // Symlink directory traversal should not produce link/inside.txt
    }

    @Test
    public void emptyPatternShouldReturnError() throws Exception {
        FindFilesTool tool = tool();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("pattern", "");
        ToolResult result = tool.call(call(input));

        assertFalse(result.isSuccess());
        assertEquals("invalid_pattern", result.getErrorCode());
    }

    @Test
    public void nonexistentSearchRootShouldReturnError() throws Exception {
        FindFilesTool tool = tool();
        ObjectNode input = findInput("*.java");
        input.put("path", "nonexistent");
        ToolResult result = tool.call(call(input));

        assertFalse(result.isSuccess());
        assertEquals("not_found", result.getErrorCode());
    }

    @Test
    public void pathEscapeShouldReturnError() throws Exception {
        FindFilesTool tool = tool();
        ObjectNode input = findInput("*.java");
        input.put("path", "../outside");
        ToolResult result = tool.call(call(input));

        assertFalse(result.isSuccess());
        assertEquals("not_found", result.getErrorCode());
    }

    @Test
    public void policyIsAlwaysReadOnly() throws Exception {
        FindFilesTool tool = tool();
        ToolPolicyDecision policy = tool.policy(call(findInput("*.java")));

        assertEquals(ToolPermissionLevel.READ_ONLY, policy.getPermissionLevel());
    }

    @Test
    public void caseSensitiveOptionShouldWork() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Files.writeString(root.resolve("Hello.py"), "data", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("hello.py"), "data", StandardCharsets.UTF_8);

        FindFilesTool tool = tool();
        ObjectNode input = findInput("H*.py");
        input.put("caseSensitive", true);
        ToolResult result = tool.call(call(input));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("Hello.py"));
        assertFalse(result.getObservation().contains("hello.py"));
    }

    private FindFilesTool tool() {
        return new FindFilesTool(properties());
    }

    private ObjectNode findInput(String pattern) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("pattern", pattern);
        return input;
    }

    private ToolCall call(ObjectNode input) throws Exception {
        return ToolCall.builder()
                .name("find_files")
                .input(input)
                .workspaceRoot(temporaryFolder.getRoot().toPath().toRealPath())
                .build();
    }

    private AgentRuntimeProperties properties() {
        AgentRuntimeProperties props = new AgentRuntimeProperties();
        props.setWorkspaceRoot(temporaryFolder.getRoot().toPath().toString());
        props.setToolTimeoutMs(3000L);
        props.setSearchMaxResults(50);
        return props;
    }
}
