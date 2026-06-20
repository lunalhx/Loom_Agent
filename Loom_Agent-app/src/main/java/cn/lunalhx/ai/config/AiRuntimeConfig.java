package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.AgentLoopService;
import cn.lunalhx.ai.domain.agent.service.DefaultAgentLoopService;
import cn.lunalhx.ai.domain.conversation.service.ChatStreamService;
import cn.lunalhx.ai.domain.conversation.service.DefaultChatStreamService;
import cn.lunalhx.ai.domain.model.adapter.port.ModelGateway;
import cn.lunalhx.ai.domain.model.service.OutputFormatValidator;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
                                             AgentRuntimeProperties agentRuntimeProperties,
                                             ObjectMapper objectMapper,
                                             ThreadPoolExecutor threadPoolExecutor) {
        return new DefaultAgentLoopService(modelGateway, toolRegistry, agentRuntimeProperties, objectMapper, threadPoolExecutor);
    }

    @Bean
    public InitializingBean aiConfigValidator(ModelRuntimeProperties modelRuntimeProperties,
                                             AgentRuntimeProperties agentRuntimeProperties,
                                             Environment environment) {
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
        };
    }

    private void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalStateException(name + " 必须大于 0");
        }
    }

}
