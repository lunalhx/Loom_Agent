package cn.lunalhx.ai.infrastructure.mcp;

import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class McpAgentToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpSchema.Tool remoteTool(String name) {
        return new McpSchema.Tool(name, "remote " + name + " description",
                new McpSchema.JsonSchema("object", Map.of("path", Map.of("type", "string")),
                        List.of("path"), false, Map.of(), Map.of()));
    }

    @Test
    public void prefixedNameSanitizesNonWordCharacters() {
        assertEquals("github_get_issue", McpAgentTool.prefixedName("github", "get_issue"));
        assertEquals("my_server_my_tool_x", McpAgentTool.prefixedName("my.server", "my tool/x"));
        assertEquals("a_b", McpAgentTool.prefixedName("a", "b"));
    }

    @Test
    public void specCarriesPrefixedNameDescriptionSchemaAndRisky() {
        McpSyncClient client = mock(McpSyncClient.class);
        McpAgentTool tool = new McpAgentTool(client, "github", remoteTool("get_issue"), true);

        ToolSpec spec = tool.spec();
        assertEquals("github_get_issue", spec.getName());
        assertEquals("remote get_issue description", spec.getDescription());
        assertTrue(spec.isRisky());
        assertTrue(spec.getInputSchema().contains("\"path\""));
        assertTrue(spec.getInputSchema().contains("\"type\":\"object\""));
    }

    @Test
    public void callRendersTextContentAsObservation() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("line one"),
                                new McpSchema.TextContent("line two")), false));
        McpAgentTool tool = new McpAgentTool(client, "github", remoteTool("get_issue"), false);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("path", "README.md");
        ToolCall call = ToolCall.builder().name("github_get_issue").input(input).build();

        ToolResult result = tool.call(call);
        assertTrue(result.isSuccess());
        assertEquals("line one\nline two", result.getObservation());
    }

    @Test
    public void callWithIsErrorReturnsFailure() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("boom")), true));
        McpAgentTool tool = new McpAgentTool(client, "github", remoteTool("get_issue"), false);

        ToolResult result = tool.call(ToolCall.builder().name("github_get_issue").input(MAPPER.createObjectNode()).build());
        assertFalse(result.isSuccess());
        assertTrue(result.getObservation().contains("boom"));
    }

    @Test
    public void callExceptionMapsToFailure() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenThrow(new RuntimeException("connection lost"));
        McpAgentTool tool = new McpAgentTool(client, "github", remoteTool("get_issue"), false);

        ToolResult result = tool.call(ToolCall.builder().name("github_get_issue").input(MAPPER.createObjectNode()).build());
        assertFalse(result.isSuccess());
        assertTrue(result.getObservation().contains("connection lost"));
    }

    @Test
    public void emptyOrNullArgsPassEmptyMap() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false));
        McpAgentTool tool = new McpAgentTool(client, "github", remoteTool("get_issue"), false);

        ToolCall call = ToolCall.builder().name("github_get_issue").input(null).build();
        ToolResult result = tool.call(call);
        assertTrue(result.isSuccess());
    }
}
