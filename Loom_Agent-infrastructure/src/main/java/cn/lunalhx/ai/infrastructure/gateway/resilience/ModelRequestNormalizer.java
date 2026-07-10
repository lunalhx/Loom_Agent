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

    ChatPrompt withEmptyResponseHint(ChatPrompt source) {
        String existing = source.getSystemPrompt();
        String hint = "上次响应为空，只返回一个合法 action/final JSON";
        if (existing != null && existing.contains(hint)) {
            return source;
        }
        String systemPrompt = StringUtils.isBlank(existing) ? hint : existing + "\n" + hint;
        return ChatPrompt.builder()
                .requestId(source.getRequestId())
                .conversationId(source.getConversationId())
                .message(source.getMessage())
                .systemPrompt(systemPrompt)
                .model(source.getModel())
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
        String model = StringUtils.defaultIfBlank(prompt.getModel(), defaultModel());
        String capability = StringUtils.defaultIfBlank(prompt.getCapability(), ModelCapabilities.STREAM_CHAT);
        return new ModelCallKey(providerName(), model, capability);
    }

    String defaultModel() {
        return properties.activeProvider().getDefaultModel();
    }

    int defaultMaxTokens() {
        ModelRuntimeProperties.ProviderConfig active = properties.activeProvider();
        return active.getMaxTokens() != null ? active.getMaxTokens() : DEFAULT_MAX_TOKENS;
    }

    public ModelCapability capability(String model) {
        String resolved = StringUtils.defaultIfBlank(model, defaultModel());
        return properties.capability(resolved);
    }

    private ChatPrompt normalize(ChatPrompt prompt, String fallback) {
        prompt.setCapability(StringUtils.defaultIfBlank(prompt.getCapability(), fallback));
        prompt.setModel(StringUtils.defaultIfBlank(prompt.getModel(), defaultModel()));
        if (prompt.getPurpose() == null) {
            prompt.setPurpose(prompt.getOutputFormat() == OutputFormat.JSON_OBJECT
                    ? ModelCallPurpose.CONTROL_JSON
                    : ModelCallPurpose.FINAL_TEXT);
        }
        return prompt;
    }

    private String providerName() {
        return properties.getProvider();
    }

}
