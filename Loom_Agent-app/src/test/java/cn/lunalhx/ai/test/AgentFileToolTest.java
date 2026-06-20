package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.infrastructure.tool.ListDirectoryTool;
import cn.lunalhx.ai.infrastructure.tool.ReadFileTool;
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

    private AgentRuntimeProperties properties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setWorkspaceRoot(temporaryFolder.getRoot().getAbsolutePath());
        properties.setSearchMaxResults(50);
        properties.setFileMaxBytes(200000L);
        properties.setToolTimeoutMs(3000L);
        return properties;
    }

}
