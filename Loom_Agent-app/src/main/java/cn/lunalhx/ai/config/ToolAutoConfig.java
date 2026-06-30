package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.service.ContextRecallTool;
import cn.lunalhx.ai.domain.agent.service.RoleToolRegistryFactory;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.infrastructure.mcp.McpClientManager;
import cn.lunalhx.ai.infrastructure.tool.MemorySaveTool;
import cn.lunalhx.ai.infrastructure.tool.MemorySearchTool;
import cn.lunalhx.ai.infrastructure.tool.RegexToolOutputSanitizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class ToolAutoConfig {

    @Bean
    public ToolRegistry toolRegistry(List<AgentTool> tools,
                                     ObjectProvider<McpClientManager> mcpClientManagerProvider) {
        List<AgentTool> allTools = new ArrayList<>(tools);
        McpClientManager mcpManager = mcpClientManagerProvider.getIfAvailable();
        if (mcpManager != null) {
            allTools.addAll(mcpManager.tools());
        }
        return new ToolRegistry(allTools);
    }

    @Bean
    public RoleToolRegistryFactory roleToolRegistryFactory(List<AgentTool> tools,
                                                            ObjectProvider<McpClientManager> mcpClientManagerProvider) {
        List<AgentTool> allTools = new ArrayList<>(tools);
        McpClientManager mcpManager = mcpClientManagerProvider.getIfAvailable();
        if (mcpManager != null) {
            allTools.addAll(mcpManager.tools());
        }
        return new RoleToolRegistryFactory(allTools);
    }

    @Bean
    public ToolOutputSanitizer toolOutputSanitizer() {
        return new RegexToolOutputSanitizer();
    }

    @Bean
    public ContextRecallTool contextRecallTool(
            cn.lunalhx.ai.domain.agent.adapter.port.context.ContextArtifactRepository contextArtifactRepository,
            cn.lunalhx.ai.domain.agent.adapter.port.context.ContextBlobStore contextBlobStore) {
        return new ContextRecallTool(contextArtifactRepository, contextBlobStore);
    }

    @Bean
    @ConditionalOnBean(AgentMemoryRepository.class)
    public MemorySaveTool memorySaveTool(AgentMemoryRepository memoryRepository) {
        return new MemorySaveTool(memoryRepository);
    }

    @Bean
    @ConditionalOnBean(AgentMemoryRepository.class)
    public MemorySearchTool memorySearchTool(AgentMemoryRepository memoryRepository) {
        return new MemorySearchTool(memoryRepository);
    }
}
