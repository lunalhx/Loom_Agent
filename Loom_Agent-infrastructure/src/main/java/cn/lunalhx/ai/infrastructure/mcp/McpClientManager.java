package cn.lunalhx.ai.infrastructure.mcp;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.tool.model.McpClientProperties;
import cn.lunalhx.ai.domain.tool.model.ToolPermissionLevel;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private static final int MAX_TOOL_LIST_PAGES = 100;

    private final McpClientProperties properties;
    private final McpJsonMapper jsonMapper;
    private final AgentMetrics metrics;

    private final Map<String, ServerConnection> connections = new LinkedHashMap<>();
    private final List<McpAgentTool> tools = new ArrayList<>();

    public McpClientManager(McpClientProperties properties, McpJsonMapper jsonMapper, AgentMetrics metrics) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.metrics = metrics;
    }

    public void initialize() {
        if (!properties.isEnabled()) {
            log.info("MCP client is disabled");
            return;
        }

        McpClientFactory factory = new McpClientFactory(properties, jsonMapper);
        List<McpAgentTool> allTools = new ArrayList<>();

        for (var entry : properties.getServers().entrySet()) {
            String alias = entry.getKey();
            McpClientProperties.ServerConfig config = entry.getValue();

            if (!config.isEnabled()) {
                log.info("MCP server '{}' is disabled in config, skipping", alias);
                continue;
            }

            try {
                log.info("MCP server '{}': connecting (transport={})", alias, config.getTransport());
                McpSyncClient client = factory.create(alias);
                client.initialize();
                log.info("MCP server '{}': initialized, server={} v{}",
                        alias,
                        client.getServerInfo() != null ? client.getServerInfo().name() : "unknown",
                        client.getServerInfo() != null ? client.getServerInfo().version() : "?");

                List<McpSchema.Tool> discovered = discoverTools(client, alias);
                List<McpAgentTool> serverTools = buildAgentTools(alias, config, client, discovered);
                allTools.addAll(serverTools);

                connections.put(alias, new ServerConnection(client, config));
                log.info("MCP server '{}': {} tools enabled", alias, serverTools.size());
                metrics.recordMcpServerInit(alias, config.getTransport().name(), "success");

            } catch (Exception e) {
                log.error("MCP server '{}': initialization failed", alias, e);
                metrics.recordMcpServerInit(alias, config.getTransport().name(), "failure");
                if (config.isRequired()) {
                    closeAll();
                    throw new IllegalStateException("Required MCP server '" + alias + "' failed to start: " + e.getMessage(), e);
                }
                log.warn("MCP server '{}' is optional, skipping after failure", alias);
            }
        }

        McpToolNameGenerator.checkConflicts(allTools.stream()
                .map(t -> t.spec().getName())
                .collect(Collectors.toList()));

        this.tools.addAll(allTools);
        log.info("MCP client initialized: {} servers, {} tools total", connections.size(), tools.size());
    }

    private List<McpSchema.Tool> discoverTools(McpSyncClient client, String serverAlias) {
        List<McpSchema.Tool> allTools = new ArrayList<>();
        String cursor = null;
        int pageCount = 0;
        Set<String> seenCursors = new java.util.HashSet<>();

        do {
            if (pageCount >= MAX_TOOL_LIST_PAGES) {
                log.warn("MCP server '{}': tool list pagination limit reached ({} pages)", serverAlias, MAX_TOOL_LIST_PAGES);
                break;
            }
            McpSchema.ListToolsResult result = client.listTools(cursor);
            if (result.tools() != null) {
                allTools.addAll(result.tools());
            }
            cursor = result.nextCursor();
            pageCount++;

            if (cursor != null && !seenCursors.add(cursor)) {
                log.warn("MCP server '{}': duplicate cursor detected, breaking pagination loop", serverAlias);
                break;
            }
        } while (cursor != null && !cursor.isBlank());

        return allTools;
    }

    private List<McpAgentTool> buildAgentTools(String serverAlias,
                                                McpClientProperties.ServerConfig config,
                                                McpSyncClient client,
                                                List<McpSchema.Tool> discovered) {
        McpResultMapper resultMapper = new McpResultMapper(properties.getMaxResultChars());
        int maxTools = properties.getMaxToolsPerServer();

        List<McpSchema.Tool> filtered = filterTools(serverAlias, config, discovered);
        if (filtered.size() > maxTools) {
            log.warn("MCP server '{}': {} tools discovered, limiting to {}", serverAlias, filtered.size(), maxTools);
            filtered = filtered.subList(0, maxTools);
        }

        List<McpAgentTool> agentTools = new ArrayList<>();
        for (McpSchema.Tool tool : filtered) {
            String localName = McpToolNameGenerator.generate(serverAlias, tool.name());
            ToolPermissionLevel permission = resolvePermission(config, tool);
            McpAgentTool agentTool = new McpAgentTool(
                    serverAlias, tool.name(), localName, client,
                    resultMapper, jsonMapper, tool, permission,
                    properties.getMaxDescriptionChars(), properties.getMaxSchemaChars(),
                    metrics
            );
            agentTools.add(agentTool);
        }
        return agentTools;
    }

    private List<McpSchema.Tool> filterTools(String serverAlias,
                                              McpClientProperties.ServerConfig config,
                                              List<McpSchema.Tool> discovered) {
        Set<String> enabledOnly = config.getEnabledTools() != null
                ? Set.copyOf(config.getEnabledTools())
                : Set.of();
        Set<String> disabled = config.getDisabledTools() != null
                ? Set.copyOf(config.getDisabledTools())
                : Set.of();

        List<McpSchema.Tool> filtered = new ArrayList<>();
        for (McpSchema.Tool tool : discovered) {
            if (!enabledOnly.isEmpty() && !enabledOnly.contains(tool.name())) {
                log.debug("MCP server '{}': tool '{}' not in enabled-tools, skipping", serverAlias, tool.name());
                continue;
            }
            if (disabled.contains(tool.name())) {
                log.info("MCP server '{}': tool '{}' in disabled-tools, skipping", serverAlias, tool.name());
                continue;
            }
            filtered.add(tool);
        }
        return filtered;
    }

    static ToolPermissionLevel resolvePermission(McpClientProperties.ServerConfig config,
                                                  McpSchema.Tool tool) {
        String defaultPerm = config.getDefaultPermission() != null
                ? config.getDefaultPermission().toUpperCase()
                : "WRITE_CONFIRM";
        ToolPermissionLevel level = parsePermission(defaultPerm);

        // Tool-level override
        if (config.getToolPermissions() != null) {
            String override = config.getToolPermissions().get(tool.name());
            if (override != null) {
                level = parsePermission(override.toUpperCase());
            }
        }

        // destructiveHint can only elevate permission (not lower it)
        McpSchema.ToolAnnotations annotations = tool.annotations();
        if (annotations != null && Boolean.TRUE.equals(annotations.destructiveHint())) {
            if (level == ToolPermissionLevel.READ_ONLY || level == ToolPermissionLevel.WRITE_CONFIRM) {
                level = ToolPermissionLevel.HIGH_RISK_CONFIRM;
            }
        }

        // readOnlyHint cannot lower Loom-configured permission
        // (so we ignore it here since Loom config always wins)

        return level;
    }

    private static ToolPermissionLevel parsePermission(String value) {
        return switch (value) {
            case "READ_ONLY" -> ToolPermissionLevel.READ_ONLY;
            case "WRITE_CONFIRM" -> ToolPermissionLevel.WRITE_CONFIRM;
            case "HIGH_RISK_CONFIRM" -> ToolPermissionLevel.HIGH_RISK_CONFIRM;
            case "HIGH_RISK_DENY" -> ToolPermissionLevel.HIGH_RISK_DENY;
            default -> ToolPermissionLevel.WRITE_CONFIRM;
        };
    }

    public List<McpAgentTool> tools() {
        return Collections.unmodifiableList(tools);
    }

    public void close() {
        closeAll();
    }

    private void closeAll() {
        List<String> aliases = new ArrayList<>(connections.keySet());
        Collections.reverse(aliases);
        for (String alias : aliases) {
            ServerConnection conn = connections.get(alias);
            try {
                log.info("MCP server '{}': closing", alias);
                conn.client.closeGracefully();
            } catch (Exception e) {
                log.warn("MCP server '{}': error during close: {}", alias, e.getMessage());
            }
        }
        connections.clear();
        tools.clear();
    }

    private record ServerConnection(McpSyncClient client, McpClientProperties.ServerConfig config) {
    }
}
