package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ApprovalStore;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.AgentWorkspaceResolver;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.agent.service.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryAgentRunRepository;
import cn.lunalhx.ai.domain.agent.service.InMemoryApprovalStore;
import cn.lunalhx.ai.domain.agent.service.RoleToolRegistryFactory;
import cn.lunalhx.ai.domain.agent.service.SubAgentCoordinator;
import cn.lunalhx.ai.domain.conversation.service.ChatStreamService;
import cn.lunalhx.ai.domain.conversation.service.DefaultChatStreamService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.service.OutputFormatValidator;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.MybatisApprovalStore;
import cn.lunalhx.ai.infrastructure.dao.AgentPendingApprovalDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunCheckpointDao;
import cn.lunalhx.ai.infrastructure.dao.AgentRunDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AiRuntimeConfig {

    @Bean
    @ConfigurationProperties(prefix = "loom.ai")
    public ModelRuntimeProperties modelRuntimeProperties() {
        return new ModelRuntimeProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "loom.agent")
    public AgentRuntimeProperties agentRuntimeProperties() {
        return new AgentRuntimeProperties();
    }

    @Bean
    public OutputFormatValidator outputFormatValidator(ObjectMapper objectMapper) {
        return new OutputFormatValidator(objectMapper);
    }

    @Bean
    public ToolRegistry toolRegistry(List<AgentTool> tools) {
        return new ToolRegistry(tools);
    }

    @Bean
    public RoleToolRegistryFactory roleToolRegistryFactory(List<AgentTool> tools) {
        return new RoleToolRegistryFactory(tools);
    }

    @Bean
    public AgentRunRepository agentRunRepository(ObjectProvider<AgentRunDao> agentRunDaoProvider) {
        AgentRunDao agentRunDao = agentRunDaoProvider.getIfAvailable();
        return agentRunDao == null ? new InMemoryAgentRunRepository() : new MybatisAgentRunRepository(agentRunDao);
    }

    @Bean
    public AgentCheckpointRepository agentCheckpointRepository(ObjectProvider<AgentRunCheckpointDao> checkpointDaoProvider,
                                                               ObjectMapper objectMapper) {
        AgentRunCheckpointDao checkpointDao = checkpointDaoProvider.getIfAvailable();
        return checkpointDao == null
                ? new InMemoryAgentCheckpointRepository()
                : new MybatisAgentCheckpointRepository(checkpointDao, objectMapper);
    }

    @Bean
    public ApprovalStore approvalStore(ObjectProvider<AgentPendingApprovalDao> approvalDaoProvider,
                                       ObjectMapper objectMapper) {
        AgentPendingApprovalDao approvalDao = approvalDaoProvider.getIfAvailable();
        return approvalDao == null ? new InMemoryApprovalStore() : new MybatisApprovalStore(approvalDao, objectMapper);
    }

    @Bean
    public AgentWorkspaceResolver agentWorkspaceResolver(AgentRuntimeProperties agentRuntimeProperties) {
        return new AgentWorkspaceResolver(agentRuntimeProperties);
    }

    @Bean
    public ChatStreamService chatStreamService(ModelGateway modelGateway,
                                               ModelRuntimeProperties modelRuntimeProperties,
                                               OutputFormatValidator outputFormatValidator,
                                               Environment environment) {
        String model = environment.getProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash");
        Double temperature = environment.getProperty("spring.ai.deepseek.chat.temperature", Double.class, 0.7D);
        Integer maxTokens = environment.getProperty("spring.ai.deepseek.chat.max-tokens", Integer.class, 2048);
        return new DefaultChatStreamService(modelGateway, modelRuntimeProperties, outputFormatValidator, model, temperature, maxTokens);
    }

    @Bean
    public AgentLoopService agentLoopService(ModelGateway modelGateway,
                                             ToolRegistry toolRegistry,
                                             ApprovalStore approvalStore,
                                             AgentWorkspaceResolver agentWorkspaceResolver,
                                             AgentRunRepository agentRunRepository,
                                             AgentCheckpointRepository agentCheckpointRepository,
                                             AgentRuntimeProperties agentRuntimeProperties,
                                             ObjectMapper objectMapper,
                                             ThreadPoolExecutor threadPoolExecutor,
                                             SubAgentCoordinator subAgentCoordinator) {
        return new DefaultAgentLoopService(
                modelGateway,
                toolRegistry,
                approvalStore,
                agentWorkspaceResolver,
                agentRunRepository,
                agentCheckpointRepository,
                agentRuntimeProperties,
                objectMapper,
                threadPoolExecutor,
                subAgentCoordinator);
    }

    @Bean
    public SubAgentCoordinator subAgentCoordinator(ModelGateway modelGateway,
                                                   RoleToolRegistryFactory roleToolRegistryFactory,
                                                   ApprovalStore approvalStore,
                                                   AgentWorkspaceResolver agentWorkspaceResolver,
                                                   AgentRunRepository agentRunRepository,
                                                   AgentCheckpointRepository agentCheckpointRepository,
                                                   AgentRuntimeProperties agentRuntimeProperties,
                                                   ObjectMapper objectMapper,
                                                   ThreadPoolExecutor threadPoolExecutor) {
        return new SubAgentCoordinator(
                modelGateway,
                roleToolRegistryFactory,
                approvalStore,
                agentWorkspaceResolver,
                agentRunRepository,
                agentCheckpointRepository,
                agentRuntimeProperties,
                objectMapper,
                threadPoolExecutor);
    }

    @Bean
    public InitializingBean aiConfigValidator(ModelRuntimeProperties modelRuntimeProperties,
                                             AgentRuntimeProperties agentRuntimeProperties,
                                             Environment environment,
                                             ThreadPoolExecutor threadPoolExecutor) {
        return () -> {
            String chatProvider = environment.getProperty("spring.ai.model.chat", "deepseek");
            if ("none".equalsIgnoreCase(chatProvider)) {
                return;
            }
            String baseUrl = environment.getProperty("spring.ai.deepseek.base-url");
            String apiKey = environment.getProperty("spring.ai.deepseek.api-key");
            String model = environment.getProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash");
            if (StringUtils.isBlank(baseUrl)) {
                throw new IllegalStateException("DEEPSEEK_BASE_URL 不能为空");
            }
            if (StringUtils.isBlank(apiKey)) {
                throw new IllegalStateException("DEEPSEEK_API_KEY 不能为空，请参考 docs/env/.env.example");
            }
            modelRuntimeProperties.normalizeModel(model, "deepseek-v4-flash");
            requirePositive(modelRuntimeProperties.getConnectTimeoutMs(), "AI_CONNECT_TIMEOUT_MS");
            requirePositive(modelRuntimeProperties.getFirstTokenTimeoutMs(), "AI_FIRST_TOKEN_TIMEOUT_MS");
            requirePositive(modelRuntimeProperties.getStreamTimeoutMs(), "AI_STREAM_TIMEOUT_MS");
            if (modelRuntimeProperties.getRetryMaxAttempts() == null || modelRuntimeProperties.getRetryMaxAttempts() < 1) {
                throw new IllegalStateException("AI_RETRY_MAX_ATTEMPTS 必须大于等于 1");
            }
            requirePositive(agentRuntimeProperties.getTotalTimeoutMs(), "AGENT_TOTAL_TIMEOUT_MS");
            requirePositive(agentRuntimeProperties.getStepTimeoutMs(), "AGENT_STEP_TIMEOUT_MS");
            requirePositive(agentRuntimeProperties.getToolTimeoutMs(), "AGENT_TOOL_TIMEOUT_MS");
            requirePositive(agentRuntimeProperties.getApprovalTtlSeconds(), "AGENT_APPROVAL_TTL_SECONDS");
            requirePositive(agentRuntimeProperties.getShellTimeoutMs(), "AGENT_SHELL_TIMEOUT_MS");
            if (agentRuntimeProperties.getShellMaxOutputChars() == null || agentRuntimeProperties.getShellMaxOutputChars() <= 0) {
                throw new IllegalStateException("AGENT_SHELL_MAX_OUTPUT_CHARS 必须大于 0");
            }
            if (!"DENY".equalsIgnoreCase(agentRuntimeProperties.getHighRiskPolicy())) {
                throw new IllegalStateException("AGENT_HIGH_RISK_POLICY 第一版仅支持 DENY");
            }
            requirePositive(agentRuntimeProperties.getSubAgentTimeoutMs(), "AGENT_SUB_AGENT_TIMEOUT_MS");
            if (agentRuntimeProperties.getSubAgentMaxChildren() == null || agentRuntimeProperties.getSubAgentMaxChildren() < 1) {
                throw new IllegalStateException("AGENT_SUB_AGENT_MAX_CHILDREN 必须大于等于 1");
            }
            if (agentRuntimeProperties.getSubAgentMaxConcurrency() == null || agentRuntimeProperties.getSubAgentMaxConcurrency() < 1) {
                throw new IllegalStateException("AGENT_SUB_AGENT_MAX_CONCURRENCY 必须大于等于 1");
            }
            if (agentRuntimeProperties.getSubAgentMaxDepth() == null || agentRuntimeProperties.getSubAgentMaxDepth() < 1) {
                throw new IllegalStateException("AGENT_SUB_AGENT_MAX_DEPTH 必须大于等于 1");
            }
            if (agentRuntimeProperties.getSubAgentSummaryMaxChars() == null || agentRuntimeProperties.getSubAgentSummaryMaxChars() < 1000) {
                throw new IllegalStateException("AGENT_SUB_AGENT_SUMMARY_MAX_CHARS 必须大于等于 1000");
            }
            if (threadPoolExecutor.getMaximumPoolSize() < agentRuntimeProperties.getSubAgentMaxConcurrency() + 1) {
                throw new IllegalStateException("线程池最大线程数必须大于 AGENT_SUB_AGENT_MAX_CONCURRENCY");
            }
        };
    }

    private void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalStateException(name + " 必须大于 0");
        }
    }

}
