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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
        ToolResult result = new ListDirectoryTool(properties()).call(ToolCall.builder().name("list_dir").input(input).build());

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
        ToolResult result = new ReadFileTool(properties()).call(ToolCall.builder().name("read_file").input(input).build());

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

        ToolPolicyDecision policy = tool.policy(ToolCall.builder().name("replace_in_file").input(input).build());
        assertTrue(policy.getPermissionLevel() == ToolPermissionLevel.WRITE_CONFIRM);

        ToolResult result = tool.call(ToolCall.builder().name("replace_in_file").input(input).build());

        assertTrue(result.isSuccess());
        assertTrue(Files.readString(temporaryFolder.getRoot().toPath().resolve("Demo.java")).contains("int n = 2"));
    }

    @Test
    public void runShellShouldClassifyMavenTestAndDangerousCommand() {
        RunShellTool tool = new RunShellTool(properties());

        ObjectNode mavenInput = objectMapper.createObjectNode();
        mavenInput.put("command", "mvn -pl Loom_Agent-app -am test");
        ToolPolicyDecision mavenPolicy = tool.policy(ToolCall.builder().name("run_shell").input(mavenInput).build());
        assertTrue(mavenPolicy.getPermissionLevel() == ToolPermissionLevel.WRITE_CONFIRM);

        ObjectNode dangerousInput = objectMapper.createObjectNode();
        dangerousInput.put("command", "rm -rf .");
        ToolPolicyDecision dangerousPolicy = tool.policy(ToolCall.builder().name("run_shell").input(dangerousInput).build());
        assertTrue(dangerousPolicy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY);
    }

    @Test
    public void gitOpShouldClassifyReadWriteAndHighRiskOperations() {
        GitOpTool tool = new GitOpTool(properties());

        ObjectNode statusInput = objectMapper.createObjectNode();
        statusInput.put("operation", "status");
        ToolPolicyDecision statusPolicy = tool.policy(ToolCall.builder().name("git_op").input(statusInput).build());
        assertTrue(statusPolicy.getPermissionLevel() == ToolPermissionLevel.READ_ONLY);

        ObjectNode addInput = objectMapper.createObjectNode();
        addInput.put("operation", "add");
        addInput.put("path", "Demo.java");
        ToolPolicyDecision addPolicy = tool.policy(ToolCall.builder().name("git_op").input(addInput).build());
        assertTrue(addPolicy.getPermissionLevel() == ToolPermissionLevel.WRITE_CONFIRM);

        ObjectNode pushInput = objectMapper.createObjectNode();
        pushInput.put("operation", "push");
        ToolPolicyDecision pushPolicy = tool.policy(ToolCall.builder().name("git_op").input(pushInput).build());
        assertTrue(pushPolicy.getPermissionLevel() == ToolPermissionLevel.HIGH_RISK_DENY);
    }

    private AgentRuntimeProperties properties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(temporaryFolder.getRoot().getAbsolutePath());
        properties.setSearchMaxResults(50);
        properties.setFileMaxBytes(200000L);
        properties.setToolTimeoutMs(3000L);
        properties.setShellTimeoutMs(3000L);
        properties.setShellMaxOutputChars(12000);
        return properties;
    }

}
