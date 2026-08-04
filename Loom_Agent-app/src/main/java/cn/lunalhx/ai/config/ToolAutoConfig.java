package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.domain.tool.service.ToolAssembler;
import cn.lunalhx.ai.infrastructure.loom.DelegateTool;
import cn.lunalhx.ai.infrastructure.loom.ListFilesTool;
import cn.lunalhx.ai.infrastructure.loom.PatchFileTool;
import cn.lunalhx.ai.infrastructure.loom.ReadFileTool;
import cn.lunalhx.ai.infrastructure.loom.RunShellTool;
import cn.lunalhx.ai.infrastructure.loom.SearchTool;
import cn.lunalhx.ai.infrastructure.loom.WriteFileTool;
import cn.lunalhx.ai.infrastructure.tool.RegexToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Explicitly registers the seven loom-code tools in fixed order and builds the
 * {@link ToolRegistry}. No MCP or dynamic bean collection.
 */
@Configuration(proxyBeanMethods = false)
public class ToolAutoConfig {

    @Bean
    public ToolSchemaValidator toolSchemaValidator(ObjectMapper objectMapper) {
        return new ToolSchemaValidator(objectMapper);
    }

    @Bean
    public ToolOutputSanitizer toolOutputSanitizer() {
        return new RegexToolOutputSanitizer();
    }

    @Bean
    public List<AgentTool> loomCodeTools(cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort workspacePort,
                                         cn.lunalhx.ai.domain.agent.adapter.port.DelegateRunner delegateRunner) {
        return List.of(
                new ListFilesTool(workspacePort),
                new ReadFileTool(workspacePort),
                new SearchTool(workspacePort),
                new RunShellTool(workspacePort),
                new WriteFileTool(workspacePort),
                new PatchFileTool(workspacePort),
                new DelegateTool(delegateRunner));
    }

    @Bean
    public ToolRegistry toolRegistry(List<AgentTool> loomCodeTools, ToolSchemaValidator schemaValidator) {
        return new ToolRegistry(loomCodeTools, schemaValidator);
    }
}
