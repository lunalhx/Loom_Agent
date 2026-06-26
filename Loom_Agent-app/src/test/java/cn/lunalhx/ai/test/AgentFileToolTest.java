package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.tool.GitOpTool;
import cn.lunalhx.ai.infrastructure.tool.ListDirectoryTool;
import cn.lunalhx.ai.infrastructure.tool.ReadFileTool;
import cn.lunalhx.ai.infrastructure.tool.ReplaceInFileTool;
import cn.lunalhx.ai.infrastructure.tool.RunShellTool;
import cn.lunalhx.ai.infrastructure.tool.WriteFileTool;
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

public class AgentFileToolTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void listDirShouldHideBlockedDirectories() throws Exception {
        Files.createDirectories(temporaryFolder.getRoot().toPath().resolve("target/classes"));
        Files.createDirectories(temporaryFolder.getRoot().toPath().resolve("src/main"));
        Files.writeString(temporaryFolder.getRoot().toPath().resolve("target/classes/Hidden.java"), "hidden", StandardCharsets.UTF_8);
        Files.writeString(temporaryFolder.getRoot().toPath().resolve("src/main/App.java"), "class App {}", StandardCharsets.UTF_8);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", ".");
        input.put("maxDepth", 3);
        ToolResult result = new ListDirectoryTool(properties()).call(call("list_dir", input));

        assertTrue(result.isSuccess());
        assertTrue(result.getObservation().contains("src"));
        assertFalse(result.getObservation().contains("target"));
    }

    @Test
    public void readFileShouldRejectEnvFile() throws Exception {
        Files.createDirectories(temporaryFolder.getRoot().toPath().resolve("docs/env"));
        Files.writeString(temporaryFolder.getRoot().toPath().resolve("docs/env/.env"), "SECRET=1", StandardCharsets.UTF_8);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", "docs/env/.env");
        ToolResult result = new ReadFileTool(properties()).call(call("read_file", input));

        assertFalse(result.isSuccess());
        assertTrue(result.getObservation().contains("read_file_failed"));
    }

    @Test
    public void replaceInFileShouldRequireApprovalAndReplaceText() throws Exception {
        Files.writeString(temporaryFolder.getRoot().toPath().resolve("Demo.java"), "class Demo { int n = 1; }", StandardCharsets.UTF_8);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", "Demo.java");
        input.put("oldText", "int n = 1");
        input.put("newText", "int n = 2");
        input.put("expectedOccurrences", 1);
        ReplaceInFileTool tool = new ReplaceInFileTool(properties());

        ToolPolicyDecision policy = tool.policy(call("replace_in_file", input));
        assertTrue(policy.getPermissionLevel() == ToolPermissionLevel.WRITE_CONFIRM);

        ToolResult result = tool.call(call("replace_in_file", input));

        assertTrue(result.isSuccess());
        assertTrue(Files.readString(temporaryFolder.getRoot().toPath().resolve("Demo.java")).contains("int n = 2"));
    }

    @Test
    public void runShellShouldClassifyMavenTestAndDangerousCommand() throws Exception {
        RunShellTool tool = new RunShellTool(properties());

        ObjectNode mavenInput = objectMapper.createObjectNode();
        mavenInput.put("command", "mvn -pl Loom_Agent-app -am test");
        ToolPolicyDecision mavenPolicy = tool.policy(call("run_shell", mavenInput));
        assertTrue(mavenPolicy.getPermissionLevel() == ToolPermissionLevel.WRITE_CONFIRM);

        ObjectNode dangerousInput = objectMapper.createObjectNode();
        dangerousInput.put("command", "rm -rf .");
        ToolPolicyDecision dangerousPolicy = tool.policy(call("run_shell", dangerousInput));
        assertTrue(dangerousPolicy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY);
    }

    @Test
    public void runShellShouldPreserveInterruptFlagAndReturnInterruptedError() throws Exception {
        RunShellTool tool = new RunShellTool(properties());

        Thread.currentThread().interrupt();
        try {
            ObjectNode input = objectMapper.createObjectNode();
            input.put("command", "pwd");
            ToolResult result = tool.call(call("run_shell", input));

            assertFalse(result.isSuccess());
            assertEquals("process_interrupted", result.getErrorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void gitOpShouldClassifyReadWriteAndHighRiskOperations() throws Exception {
        GitOpTool tool = new GitOpTool(properties());

        ObjectNode statusInput = objectMapper.createObjectNode();
        statusInput.put("operation", "status");
        ToolPolicyDecision statusPolicy = tool.policy(call("git_op", statusInput));
        assertTrue(statusPolicy.getPermissionLevel() == ToolPermissionLevel.READ_ONLY);

        ObjectNode addInput = objectMapper.createObjectNode();
        addInput.put("operation", "add");
        addInput.put("path", "Demo.java");
        ToolPolicyDecision addPolicy = tool.policy(call("git_op", addInput));
        assertTrue(addPolicy.getPermissionLevel() == ToolPermissionLevel.WRITE_CONFIRM);

        ObjectNode pushInput = objectMapper.createObjectNode();
        pushInput.put("operation", "push");
        ToolPolicyDecision pushPolicy = tool.policy(call("git_op", pushInput));
        assertTrue(pushPolicy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY);
    }

    @Test
    public void readFileShouldRejectPathEscapingWorkspace() throws Exception {
        Path workspace = Files.createDirectories(temporaryFolder.getRoot().toPath().resolve("workspace"));
        Files.writeString(temporaryFolder.getRoot().toPath().resolve("outside.txt"), "secret", StandardCharsets.UTF_8);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", "../outside.txt");
        ToolResult result = new ReadFileTool(properties(workspace)).call(call("read_file", input, workspace));

        assertFalse(result.isSuccess());
        assertTrue(result.getObservation().contains("路径越权"));
    }

    @Test
    public void writeFileCreateShouldRejectPathEscapingWorkspace() throws Exception {
        Path workspace = Files.createDirectories(temporaryFolder.getRoot().toPath().resolve("workspace"));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("path", "../escape.txt");
        input.put("content", "nope");
        input.put("mode", "create");
        ToolResult result = new WriteFileTool(properties(workspace)).call(call("write_file", input, workspace));

        assertFalse(result.isSuccess());
        assertFalse(Files.exists(temporaryFolder.getRoot().toPath().resolve("escape.txt")));
        assertTrue(result.getObservation().contains("路径越权"));
    }

    @Test
    public void runShellShouldRejectCwdEscapingWorkspace() throws Exception {
        Path workspace = Files.createDirectories(temporaryFolder.getRoot().toPath().resolve("workspace"));

        ObjectNode input = objectMapper.createObjectNode();
        input.put("command", "pwd");
        input.put("cwd", "..");
        ToolResult result = new RunShellTool(properties(workspace)).call(call("run_shell", input, workspace));

        assertFalse(result.isSuccess());
        assertTrue(result.getObservation().contains("路径越权"));
    }

    @Test
    public void gitOpShouldRejectPathEscapingWorkspace() throws Exception {
        Path workspace = Files.createDirectories(temporaryFolder.getRoot().toPath().resolve("workspace"));
        Files.writeString(temporaryFolder.getRoot().toPath().resolve("outside.txt"), "outside", StandardCharsets.UTF_8);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("operation", "diff");
        input.put("path", "../outside.txt");
        ToolResult result = new GitOpTool(properties(workspace)).call(call("git_op", input, workspace));

        assertFalse(result.isSuccess());
        assertTrue(result.getObservation().contains("路径越权"));
    }

    private ToolCall call(String name, ObjectNode input) throws Exception {
        return call(name, input, temporaryFolder.getRoot().toPath());
    }

    private ToolCall call(String name, ObjectNode input, Path workspace) throws Exception {
        return ToolCall.builder()
                .name(name)
                .input(input)
                .workspaceRoot(workspace.toRealPath())
                .build();
    }

    private AgentRuntimeProperties properties() {
        return properties(temporaryFolder.getRoot().toPath());
    }

    private AgentRuntimeProperties properties(Path workspaceRoot) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(workspaceRoot.toString());
        properties.setSearchMaxResults(50);
        properties.setFileMaxBytes(200000L);
        properties.setToolTimeoutMs(3000L);
        properties.setShellTimeoutMs(3000L);
        properties.setShellMaxOutputChars(12000);
        return properties;
    }

}
