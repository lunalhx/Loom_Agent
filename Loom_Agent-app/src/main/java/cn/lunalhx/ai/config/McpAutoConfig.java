package cn.lunalhx.ai.config;

import cn.lunalhx.ai.infrastructure.mcp.McpToolCatalog;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.customizer.McpSyncClientCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Loom MCP bridge assembly. The transport layer (stdio connections,
 * {@code McpSyncClient} beans) is provided by the Spring AI MCP client
 * auto-configuration; this class only:
 *
 * <ul>
 *   <li>overrides the client-reported name with the bare connection name so
 *       the Spring AI tool prefix (and ours) is the clean server name</li>
 *   <li>exposes the {@link McpToolCatalog} that converts connected clients
 *       into loom {@code AgentTool}s using the {@code loom.mcp.servers} metadata</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "loom.mcp", name = "enabled", havingValue = "true")
public class McpAutoConfig {

    @Bean
    public McpSyncClientCustomizer loomMcpSyncClientCustomizer() {
        return (name, spec) -> {
            McpSchema.Implementation info = new McpSchema.Implementation(name, "1.0.0");
            spec.clientInfo(info);
        };
    }

    @Bean
    public McpToolCatalog mcpToolCatalog(List<McpSyncClient> mcpSyncClients, McpProperties properties) {
        return new McpToolCatalog(
                mcpSyncClients == null ? List.of() : mcpSyncClients,
                properties.getServers() == null ? Map.of() : properties.getServers());
    }
}
