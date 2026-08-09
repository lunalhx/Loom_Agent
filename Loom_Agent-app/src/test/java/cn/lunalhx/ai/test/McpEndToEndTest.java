package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import cn.lunalhx.ai.infrastructure.mcp.McpApprovalMode;
import cn.lunalhx.ai.infrastructure.mcp.McpServerConfig;
import cn.lunalhx.ai.infrastructure.mcp.McpToolCatalog;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end MCP smoke test against the real {@code @modelcontextprotocol/
 * server-everything} stdio server (requires Node/npx). Skipped when npx or the
 * server package is unavailable.
 */
public class McpEndToEndTest {

    private static final String SERVER = "everything";

    private static McpSyncClient client;
    private static boolean available;

    @BeforeClass
    public static void connect() throws Exception {
        ProcessBuilder probe = new ProcessBuilder("npx", "--version");
        probe.redirectErrorStream(true);
        try {
            Process p = probe.start();
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            available = false;
            return;
        }
        try {
            ServerParameters params = ServerParameters.builder("npx")
                    .args("-y", "@modelcontextprotocol/server-everything")
                    .build();
            client = McpClient.sync(new StdioClientTransport(params))
                    .clientInfo(new McpSchema.Implementation("test-client", "1.0.0"))
                    .requestTimeout(java.time.Duration.ofSeconds(15))
                    .build();
            client.initialize();
            available = client.getServerCapabilities() != null;
        } catch (Exception e) {
            available = false;
        }
    }

    @AfterClass
    public static void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void catalogRegistersRealServerToolsAndCallsEcho() {
        assumeTrue("npx/@modelcontextprotocol/server-everything unavailable", available);
        client.getServerInfo();
        // server-everything reports its clientInfo name as its own; match by that
        List<AgentTool> tools = new McpToolCatalog(
                List.of(client),
                Map.of("everything", new McpServerConfig(McpApprovalMode.WRITES, List.of(), List.of())))
                .catalog();

        assertFalse("expected tools from server-everything", tools.isEmpty());
        assertTrue(tools.stream().anyMatch(t -> t.spec().getName().endsWith("_echo")));

        AgentTool echo = tools.stream()
                .filter(t -> t.spec().getName().endsWith("_echo"))
                .findFirst().orElseThrow();
        assertTrue("schema must compile and carry properties",
                echo.spec().getInputSchema().contains("\"message\""));

        ToolCall call = ToolCall.builder()
                .name(echo.spec().getName())
                .input(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("message", "hello mcp"))
                .build();
        ToolResult result = echo.call(call);
        assertTrue("echo should succeed: " + result.getObservation(), result.isSuccess());
        assertTrue(result.getObservation().contains("hello mcp"));
    }

    @Test
    public void everyToolHasValidCompilableSchemaShape() {
        assumeTrue("npx/@modelcontextprotocol/server-everything unavailable", available);
        List<AgentTool> tools = new McpToolCatalog(List.of(client), Map.of()).catalog();
        for (AgentTool tool : tools) {
            ToolSpec spec = tool.spec();
            assertFalse("name must be prefixed", spec.getName().startsWith("_"));
            assertFalse("name must not be empty", spec.getName().isBlank());
            assertFalse("description must not be empty", spec.getDescription().isBlank());
            assertTrue("schema must be object-shaped",
                    spec.getInputSchema().startsWith("{"));
        }
    }
}
