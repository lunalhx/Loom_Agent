package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.tool.model.McpClientProperties;
import cn.lunalhx.ai.infrastructure.mcp.McpClientManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(McpAutoConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "loom.mcp")
    public McpClientProperties mcpClientProperties() {
        return new McpClientProperties();
    }

    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        log.info("MCP: using Jackson 2 JSON mapper");
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean(initMethod = "initialize", destroyMethod = "close")
    @ConditionalOnProperty(name = "loom.mcp.enabled", havingValue = "true")
    public McpClientManager mcpClientManager(McpClientProperties properties,
                                             McpJsonMapper jsonMapper,
                                             AgentMetrics agentMetrics) {
        log.info("MCP client manager: enabled");
        return new McpClientManager(properties, jsonMapper, agentMetrics);
    }
}
