package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.common.LoomPaths;
import cn.lunalhx.ai.domain.tool.model.McpClientProperties;
import cn.lunalhx.ai.infrastructure.mcp.McpClientManager;
import cn.lunalhx.ai.infrastructure.mcp.ExtensionsConfigLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;

import java.nio.file.Path;
import java.util.List;

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

    @Bean
    public ExtensionsConfigLoader extensionsConfigLoader(ExtensionsProperties extensionsProperties,
                                                           ObjectMapper objectMapper,
                                                           McpClientProperties deprecatedProperties,
                                                           LoomPaths loomPaths) {
        Path path = loomPaths.resolveWorkspacePath(extensionsProperties.getConfigPath(),
                loomPaths.extensionsConfig());
        return new ExtensionsConfigLoader(path,
                objectMapper, deprecatedProperties);
    }

    @Bean(initMethod = "initialize")
    public ExtensionsConfigRegistry extensionsConfigRegistry(ExtensionsConfigLoader loader,
                                                              ObjectMapper objectMapper) {
        return new ExtensionsConfigRegistry(loader, objectMapper);
    }

    @Bean(initMethod = "initialize", destroyMethod = "close")
    @ConditionalOnProperty(name = "loom.mcp.enabled", havingValue = "true")
    public McpClientManager mcpClientManager(McpClientProperties properties,
                                             ExtensionsConfigLoader configLoader,
                                             ExtensionsConfigRegistry extensions,
                                             McpJsonMapper jsonMapper,
                                             AgentMetrics agentMetrics) {
        log.info("MCP client manager: enabled");
        return new McpClientManager(configLoader.toMcpProperties(extensions.capture().config()),
                jsonMapper, agentMetrics);
    }

    @Bean
    public LoomConfigWatcher loomConfigWatcher() {
        return new LoomConfigWatcher();
    }

    @Bean
    @ConditionalOnProperty(name = "loom.extensions.hot-reload", havingValue = "true", matchIfMissing = true)
    public ExtensionsConfigHotReloader extensionsConfigHotReloader(
            ExtensionsConfigLoader loader,
            ObjectProvider<McpClientManager> managerProvider,
            ObjectProvider<ToolRegistry> registryProvider,
            List<AgentTool> builtInTools,
            LoomConfigWatcher watcher,
            ExtensionsConfigRegistry extensions) {
        McpClientManager manager = managerProvider.getIfAvailable();
        ToolRegistry registry = registryProvider.getIfAvailable();
        if (manager != null && registry == null) {
            throw new IllegalStateException("ToolRegistry is required for MCP hot reload");
        }
        return new ExtensionsConfigHotReloader(loader, manager, registry, builtInTools, watcher, extensions);
    }
}
