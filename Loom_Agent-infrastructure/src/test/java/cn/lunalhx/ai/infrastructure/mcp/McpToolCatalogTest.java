package cn.lunalhx.ai.infrastructure.mcp;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class McpToolCatalogTest {

    private McpSchema.Tool tool(String name) {
        return new McpSchema.Tool(name, "desc " + name, new McpSchema.JsonSchema(
                "object", Map.of(), List.of(), false, Map.of(), Map.of()));
    }

    private McpSyncClient client(String serverName, McpSchema.Tool... tools) {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation(serverName, "1.0.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tools), null));
        return client;
    }

    @Test
    public void catalogPrefixesAllTools() {
        McpSyncClient auto = client("safe", tool("read"));
        McpSyncClient writes = client("db", tool("query"), tool("delete"));

        McpToolCatalog catalog = new McpToolCatalog(
                List.of(auto, writes),
                Map.of("safe", new McpServerConfig(McpApprovalMode.AUTO, List.of(), List.of()),
                        "db", new McpServerConfig(McpApprovalMode.WRITES, List.of(), List.of())));

        List<AgentTool> tools = catalog.catalog();
        assertEquals(3, tools.size());
        assertEquals("safe_read", tools.get(0).spec().getName());
        assertEquals("db_query", tools.get(1).spec().getName());
        assertEquals("db_delete", tools.get(2).spec().getName());
    }

    @Test
    public void enabledAndDisabledToolsFilter() {
        McpSyncClient client = client("git", tool("get_issue"), tool("delete_repo"), tool("list_repos"));

        McpToolCatalog catalog = new McpToolCatalog(List.of(client),
                Map.of("git", new McpServerConfig(McpApprovalMode.WRITES,
                        List.of("get_issue", "list_repos"), List.of("delete_repo"))));

        List<AgentTool> tools = catalog.catalog();
        assertEquals(2, tools.size());
        assertEquals("git_get_issue", tools.get(0).spec().getName());
        assertEquals("git_list_repos", tools.get(1).spec().getName());
    }

    @Test
    public void failingServerIsSkippedWithoutAborting() {
        McpSyncClient broken = mock(McpSyncClient.class);
        when(broken.getClientInfo()).thenReturn(new McpSchema.Implementation("broken", "1.0.0"));
        when(broken.listTools()).thenThrow(new RuntimeException("boom"));
        McpSyncClient ok = client("ok", tool("read"));

        McpToolCatalog catalog = new McpToolCatalog(List.of(broken, ok), Map.of());

        List<AgentTool> tools = catalog.catalog();
        assertEquals(1, tools.size());
        assertEquals("ok_read", tools.get(0).spec().getName());
    }

    @Test
    public void configByClientInfoNameIsUsedWhenConnectionNameDiffers() {
        McpSyncClient client = client("reported-name", tool("read"));
        McpToolCatalog catalog = new McpToolCatalog(List.of(client),
                Map.of("reported-name", new McpServerConfig(McpApprovalMode.AUTO, List.of(), List.of())));
        assertEquals("reported-name_read", catalog.catalog().get(0).spec().getName());
    }

    @Test
    public void paginationIsFollowed() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("page", "1.0.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool("a")), "cursor-1"));
        when(client.listTools("cursor-1")).thenReturn(new McpSchema.ListToolsResult(List.of(tool("b")), null));

        McpToolCatalog catalog = new McpToolCatalog(List.of(client), Map.of());
        assertEquals(2, catalog.catalog().size());
    }
}
