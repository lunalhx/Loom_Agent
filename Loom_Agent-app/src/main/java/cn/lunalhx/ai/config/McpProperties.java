package cn.lunalhx.ai.config;

import cn.lunalhx.ai.infrastructure.mcp.McpServerConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "loom.mcp")
public class McpProperties {

    private boolean enabled = false;

    /**
     * loom-specific metadata per MCP server, keyed by the connection name in
     * {@code spring.ai.mcp.client.stdio.connections.<name>}.
     */
    private Map<String, McpServerConfig> servers = Map.of();
}
