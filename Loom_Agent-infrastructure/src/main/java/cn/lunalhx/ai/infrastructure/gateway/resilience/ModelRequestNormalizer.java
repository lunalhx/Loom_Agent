package cn.lunalhx.ai.infrastructure.gateway.resilience;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelCallPurpose;
import cn.lunalhx.ai.domain.model.valobj.ModelCapabilities;
import cn.lunalhx.ai.domain.model.valobj.ModelCapability;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;

public final class ModelRequestNormalizer {

    private static final String PROVIDER = "deepseek";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final int DEFAULT_MAX_TOKENS = 2048;

    private final ModelRuntimeProperties properties;
    private final Environment environment;

    public ModelRequestNormalizer(ModelRuntimeProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    public ChatPrompt normalizeComplete(ChatPrompt prompt) {
        return normalize(prompt, ModelCapabilities.COMPLETE_AGENT_DECISION);
    }

    public ChatPrompt normalizeStream(ChatPrompt prompt) {
        return normalize(prompt, ModelCapabilities.STREAM_CHAT);
    }

    ChatPrompt withModel(ChatPrompt source, String model) {
        return ChatPrompt.builder()
                .requestId(source.getRequestId())
                .conversationId(source.getConversationId())
                .message(source.getMessage())
                .systemPrompt(source.getSystemPrompt())
                .model(model)
                .temperature(source.getTemperature())
                .maxTokens(source.getMaxTokens())
                .outputFormat(source.getOutputFormat())
                .capability(source.getCapability())
                .purpose(source.getPurpose())
                .deadlineEpochMs(source.getDeadlineEpochMs())
                .messages(source.getMessages())
                .build();
    }

    public ModelCallKey key(ChatPrompt prompt) {
        String model = StringUtils.defaultIfBlank(prompt.getModel(),
                environment.getProperty("spring.ai.deepseek.chat.model", DEFAULT_MODEL));
        String capability = StringUtils.defaultIfBlank(prompt.getCapability(), ModelCapabilities.STREAM_CHAT);
        return new ModelCallKey(PROVIDER, model, capability);
    }

    String defaultModel() {
        return environment.getProperty("spring.ai.deepseek.chat.model", DEFAULT_MODEL);
    }

    int defaultMaxTokens() {
        return environment.getProperty("spring.ai.deepseek.chat.max-tokens", Integer.class, DEFAULT_MAX_TOKENS);
    }

    public ModelCapability capability(String model) {
        String resolved = StringUtils.defaultIfBlank(model,
                environment.getProperty("spring.ai.deepseek.chat.model", DEFAULT_MODEL));
        return properties.capability(resolved);
    }

    private ChatPrompt normalize(ChatPrompt prompt, String fallback) {
        prompt.setCapability(StringUtils.defaultIfBlank(prompt.getCapability(), fallback));
        prompt.setModel(StringUtils.defaultIfBlank(prompt.getModel(),
                environment.getProperty("spring.ai.deepseek.chat.model", DEFAULT_MODEL)));
        if (prompt.getPurpose() == null) {
            prompt.setPurpose(prompt.getOutputFormat() == OutputFormat.JSON_OBJECT
                    ? ModelCallPurpose.CONTROL_JSON
                    : ModelCallPurpose.FINAL_TEXT);
        }
        return prompt;
    }

}
