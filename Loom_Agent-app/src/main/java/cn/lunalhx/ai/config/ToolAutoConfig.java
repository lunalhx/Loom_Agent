package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.context.SanitizationPolicy;
import cn.lunalhx.ai.domain.agent.service.context.SecretRedactor;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolOutputSanitizer;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator;
import cn.lunalhx.ai.infrastructure.loom.DelegateTool;
import cn.lunalhx.ai.infrastructure.loom.ListFilesTool;
import cn.lunalhx.ai.infrastructure.loom.PatchFileTool;
import cn.lunalhx.ai.infrastructure.loom.ReadFileTool;
import cn.lunalhx.ai.infrastructure.loom.ReadSkillResourceTool;
import cn.lunalhx.ai.infrastructure.loom.RunShellTool;
import cn.lunalhx.ai.infrastructure.loom.SearchTool;
import cn.lunalhx.ai.infrastructure.loom.WriteFileTool;
import cn.lunalhx.ai.infrastructure.tool.NoopToolOutputSanitizer;
import cn.lunalhx.ai.infrastructure.tool.RedactingToolOutputSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Default wiring: a real redacting sanitizer backed by the shared policy.
     * {@link NoopToolOutputSanitizer} is only used when the explicit
     * {@code secretRedaction=false} feature flag is set, or in dedicated tests.
     */
    @Bean
    public ToolOutputSanitizer toolOutputSanitizer(AgentRuntimeProperties agent,
                                                   ModelRuntimeProperties model) {
        if (agent.getFeatureFlags() != null && !agent.getFeatureFlags().secretRedaction()) {
            return new NoopToolOutputSanitizer();
        }
        Set<String> providerKeys = new LinkedHashSet<>();
        ModelRuntimeProperties.ProviderConfig active = model.getProviders() == null
                ? null : model.getProviders().get(model.getProvider());
        if (active != null && active.getApiKey() != null && !active.getApiKey().isBlank()) {
            providerKeys.add(active.getApiKey());
        }
        SanitizationPolicy policy = SanitizationPolicy.withEnvDiscovery(
                new java.util.LinkedHashSet<>(agent.getSecretEnvNames()), providerKeys);
        return new RedactingToolOutputSanitizer(SecretRedactor.fromPolicy(policy));
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
                new ReadSkillResourceTool(),
                new DelegateTool(delegateRunner));
    }

    @Bean
    public ToolRegistry toolRegistry(List<AgentTool> loomCodeTools, ToolSchemaValidator schemaValidator) {
        return new ToolRegistry(loomCodeTools, schemaValidator);
    }
}
