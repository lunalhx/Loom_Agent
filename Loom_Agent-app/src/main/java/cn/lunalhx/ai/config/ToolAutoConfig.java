package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.service.context.ContextRecallTool;
import cn.lunalhx.ai.domain.agent.service.subagent.RoleToolRegistryFactory;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.infrastructure.mcp.McpClientManager;
import cn.lunalhx.ai.infrastructure.skill.SkillTools;
import cn.lunalhx.ai.infrastructure.tool.BackgroundProcessManager;
import cn.lunalhx.ai.infrastructure.tool.MemorySaveTool;
import cn.lunalhx.ai.infrastructure.tool.MemorySearchTool;
import cn.lunalhx.ai.infrastructure.tool.RegexToolOutputSanitizer;
import cn.lunalhx.ai.infrastructure.tool.ShellTaskTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class ToolAutoConfig {

    @Bean
    public ToolSchemaValidator toolSchemaValidator(ObjectMapper objectMapper) {
        return new ToolSchemaValidator(objectMapper);
    }

    @Bean
    public ToolRegistry toolRegistry(List<AgentTool> tools,
                                     ObjectProvider<McpClientManager> mcpClientManagerProvider,
                                     ToolSchemaValidator schemaValidator) {
        List<AgentTool> allTools = new ArrayList<>(tools);
        McpClientManager mcpManager = mcpClientManagerProvider.getIfAvailable();
        if (mcpManager != null) {
            allTools.addAll(mcpManager.tools());
        }
        return new ToolRegistry(allTools, schemaValidator);
    }

    @Bean
    public RoleToolRegistryFactory roleToolRegistryFactory(List<AgentTool> tools,
                                                            ObjectProvider<McpClientManager> mcpClientManagerProvider,
                                                            ToolSchemaValidator schemaValidator) {
        List<AgentTool> allTools = new ArrayList<>(tools);
        McpClientManager mcpManager = mcpClientManagerProvider.getIfAvailable();
        if (mcpManager != null) {
            allTools.addAll(mcpManager.tools());
        }
        return new RoleToolRegistryFactory(allTools, schemaValidator);
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

    @Bean
    public SkillTools.ReadSkillResourceTool readSkillResourceTool(SkillRepository skillRepository) {
        return new SkillTools.ReadSkillResourceTool(skillRepository);
    }

    @Bean
    public SkillTools.CopySkillResourceTool copySkillResourceTool(SkillRepository skillRepository) {
        return new SkillTools.CopySkillResourceTool(skillRepository);
    }

    @Bean
    public SkillTools.CreateSkillTool createSkillTool(AgentRuntimeProperties agentRuntimeProperties) {
        String projectDir = agentRuntimeProperties.getSkills() != null
                ? agentRuntimeProperties.getSkills().getProjectDir() : ".agents/skills";
        return new SkillTools.CreateSkillTool(projectDir);
    }

    @Bean
    public ShellTaskTool shellTaskTool(BackgroundShellTaskRepository taskRepository,
                                        BackgroundProcessManager processManager,
                                        AgentRuntimeProperties properties) {
        return new ShellTaskTool(taskRepository, processManager, properties);
    }
}
