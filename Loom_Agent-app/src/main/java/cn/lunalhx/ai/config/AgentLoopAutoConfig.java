package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.PlanSubmissionHandler;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.conversation.ConversationExecutionGuard;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopFactory;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopRuntimeDependencies;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.execution.AgentLoopStateDependencies;
import cn.lunalhx.ai.domain.agent.service.context.ContextManager;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationHistoryAppendService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.service.PermissionPrompt;
import cn.lunalhx.ai.infrastructure.mcp.McpToolCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
public class AgentLoopAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopAutoConfig.class);

    @Bean
    public PermissionPrompt approvalPrompt(AgentRuntimeProperties agent) {
        return new cn.lunalhx.ai.cli.CliSessionService.InteractiveApprovalPrompt(
                "ask".equalsIgnoreCase(agent.getApprovalPolicy()));
    }

    @Bean
    public PlanSubmissionHandler planSubmissionHandler(AgentSessionRepository sessionRepository,
                                                       AgentRunRepository runRepository,
                                                       ObjectMapper mapper) {
        return new cn.lunalhx.ai.cli.FilePlanSubmissionHandler(
                sessionRepository, runRepository, mapper);
    }

    @Bean
    public AgentLoopFactory agentLoopFactory(ModelGateway modelGateway,
                                             AgentLoopStateDependencies state,
                                             AgentLoopRuntimeDependencies runtime,
                                             ConversationHistoryAppendService ledgerAppendService,
                                             ContextManager contextManager,
                                             ConversationExecutionGuard executionGuard,
                                             ObjectProvider<PermissionPrompt> approvalPromptProvider,
                                             PlanSubmissionHandler planSubmissionHandler) {
        return new AgentLoopFactory(modelGateway, state, runtime, ledgerAppendService,
                contextManager, executionGuard, approvalPromptProvider.getIfAvailable(),
                planSubmissionHandler);
    }

    @Bean
    public AgentLoopService agentLoopService(AgentLoopFactory factory,
                                             ToolRegistry registry,
                                             ThreadPoolExecutor executor,
                                             ObjectProvider<McpToolCatalog> mcpCatalogProvider) {
        McpToolCatalog catalog = mcpCatalogProvider.getIfAvailable();
        if (catalog != null) {
            try {
                java.util.List<AgentTool> mcpTools = catalog.catalog();
                if (!mcpTools.isEmpty()) {
                    java.util.List<AgentTool> merged = new ArrayList<>(registry.tools());
                    merged.addAll(mcpTools);
                    registry.replace(merged);
                    log.info("registered {} MCP tool(s)", mcpTools.size());
                }
            } catch (Exception e) {
                log.warn("MCP tool registration skipped: {}", e.getMessage());
            }
        }
        return factory.createStandalone(registry, executor);
    }
}
