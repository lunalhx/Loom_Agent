package cn.lunalhx.ai.infrastructure.mcp;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts connected MCP clients into loom {@link AgentTool}s.
 *
 * <p>Per-server behaviour mirrors opencode's tolerant catalog: a failing
 * server is logged and skipped, never fatal. Tools are filtered by
 * {@code enabled_tools}/{@code disabled_tools} (allowlist first, then
 * denylist) and prefixed as {@code <server>_<tool>}.
 *
 * <p>The server prefix is {@code client.getClientInfo().name()} — the exact
 * same source the Spring AI tool naming uses — so the model-visible tool name
 * always matches the registry key. Config metadata is matched by connection
 * name, falling back to the server-reported name.
 */
public class McpToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);

    private final List<McpSyncClient> clients;
    private final Map<String, McpServerConfig> configs;

    public McpToolCatalog(List<McpSyncClient> clients, Map<String, McpServerConfig> configs) {
        this.clients = clients == null ? List.of() : clients;
        this.configs = configs == null ? Map.of() : configs;
    }

    public List<AgentTool> catalog() {
        List<AgentTool> tools = new ArrayList<>();
        for (McpSyncClient client : clients) {
            String prefix = serverPrefix(client);
            List<McpSchema.Tool> remoteTools;
            try {
                remoteTools = listTools(client, prefix);
            } catch (Exception e) {
                log.warn("[mcp] server '{}' tools/list failed, skipping: {}", prefix, e.getMessage());
                continue;
            }
            if (remoteTools == null || remoteTools.isEmpty()) {
                log.info("[mcp] server '{}' exposes no tools", prefix);
                continue;
            }
            McpServerConfig config = resolveConfig(prefix);
            boolean risky = config.approvalMode() != McpApprovalMode.AUTO;
            for (McpSchema.Tool tool : remoteTools) {
                if (!allowed(config, tool.name())) {
                    log.info("[mcp] server '{}' tool '{}' filtered by config", prefix, tool.name());
                    continue;
                }
                tools.add(new McpAgentTool(client, prefix, tool, risky));
            }
        }
        return tools;
    }

    private List<McpSchema.Tool> listTools(McpSyncClient client, String serverName) {
        List<McpSchema.Tool> tools = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            McpSchema.ListToolsResult result = cursor == null
                    ? client.listTools() : client.listTools(cursor);
            if (result.tools() != null) {
                tools.addAll(result.tools());
            }
            cursor = result.nextCursor();
            if (++pages > 1000) {
                throw new IllegalStateException("server '" + serverName + "' tools/list pagination runaway");
            }
        } while (cursor != null && !cursor.isBlank());
        return tools;
    }

    /** Match config by connection name first, then by the server-reported name. */
    private McpServerConfig resolveConfig(String prefix) {
        McpServerConfig byPrefix = configs.get(prefix);
        if (byPrefix != null) {
            return byPrefix;
        }
        return McpServerConfig.defaults();
    }

    private boolean allowed(McpServerConfig config, String toolName) {
        if (!config.enabledTools().isEmpty() && !config.enabledTools().contains(toolName)) {
            return false;
        }
        return !config.disabledTools().contains(toolName);
    }

    /** Same prefix source as Spring AI {@code SyncMcpToolCallback}. */
    private String serverPrefix(McpSyncClient client) {
        try {
            McpSchema.Implementation info = client.getClientInfo();
            if (info != null && info.name() != null && !info.name().isBlank()) {
                return info.name();
            }
        } catch (Exception ignored) {
        }
        return client.getClass().getSimpleName();
    }
}
