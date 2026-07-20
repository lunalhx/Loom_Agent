package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.service.context.ContextRecallTool;
import cn.lunalhx.ai.domain.agent.service.subagent.RoleToolRegistryFactory;
import cn.lunalhx.ai.domain.memory.adapter.port.AgentMemoryRepository;
import cn.lunalhx.ai.domain.memory.service.MemorySearchService;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.domain.tool.service.ToolAssembler;
import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.BackgroundShellTaskRepository;
import cn.lunalhx.ai.domain.tool.adapter.port.TaskLogReader;
import cn.lunalhx.ai.domain.tool.service.BackgroundTaskCancelService;
import cn.lunalhx.ai.infrastructure.mcp.McpClientManager;
import cn.lunalhx.ai.infrastructure.skill.SkillTools;
import cn.lunalhx.ai.infrastructure.tool.MemorySaveTool;
import cn.lunalhx.ai.infrastructure.tool.MemorySearchTool;
import cn.lunalhx.ai.infrastructure.tool.RegexToolOutputSanitizer;
import cn.lunalhx.ai.infrastructure.tool.ShellTaskTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        return new ToolRegistry(collectAllTools(tools, mcpClientManagerProvider), schemaValidator);
    }

    @Bean
    public RoleToolRegistryFactory roleToolRegistryFactory(ToolRegistry toolRegistry,
                                                            ToolSchemaValidator schemaValidator) {
        return new RoleToolRegistryFactory(toolRegistry, schemaValidator);
    }

    private List<AgentTool> collectAllTools(List<AgentTool> tools,
                                            ObjectProvider<McpClientManager> mcpClientManagerProvider) {
        McpClientManager mcpManager = mcpClientManagerProvider.getIfAvailable();
        return ToolAssembler.assemble(tools, mcpManager == null ? List.of() : mcpManager.tools());
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
    public MemorySearchTool memorySearchTool(AgentMemoryRepository memoryRepository,
                                              ObjectProvider<MemorySearchService> searchServiceProvider) {
        return new MemorySearchTool(memoryRepository, searchServiceProvider.getIfAvailable());
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
                                         BackgroundTaskCancelService cancelService,
                                         TaskLogReader logReader,
                                         AgentRuntimeProperties properties) {
        return new ShellTaskTool(taskRepository, cancelService, logReader, properties);
    }
}
