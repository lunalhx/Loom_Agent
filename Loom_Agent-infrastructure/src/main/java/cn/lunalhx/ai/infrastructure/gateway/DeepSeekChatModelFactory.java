package cn.lunalhx.ai.infrastructure.gateway;

import cn.lunalhx.ai.domain.conversation.model.entity.ChatPrompt;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.OutputFormat;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DeepSeekChatModelFactory implements ChatModelFactory {

    @Override
    public String provider() {
        return "deepseek";
    }

    @Override
    public ChatModel create(ModelRuntimeProperties.ProviderConfig provider, RestClient.Builder restClientBuilder) {
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .completionsPath(StringUtils.defaultIfBlank(provider.getCompletionsPath(), "/chat/completions"))
                .restClientBuilder(restClientBuilder)
                .build();

        DeepSeekChatOptions defaultOptions = DeepSeekChatOptions.builder()
                .model(provider.getDefaultModel())
                .temperature(provider.getTemperature())
                .maxTokens(provider.getMaxTokens())
                .build();

        RetryTemplate noRetry = new RetryTemplate();
        noRetry.setRetryPolicy(new SimpleRetryPolicy(1));
        noRetry.setBackOffPolicy(new NoBackOffPolicy());

        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(defaultOptions)
                .retryTemplate(noRetry)
                .build();
    }

    @Override
    public ChatOptions createOptions(ModelRuntimeProperties.ProviderConfig config, ChatPrompt prompt,
                                     String resolvedModel, boolean stream) {
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
                .model(resolvedModel)
                .temperature(prompt.getTemperature() != null ? prompt.getTemperature() : config.getTemperature())
                .maxTokens(prompt.getMaxTokens() != null ? prompt.getMaxTokens() : config.getMaxTokens());

        if (OutputFormat.JSON_OBJECT == prompt.getOutputFormat()) {
            builder.responseFormat(
                    org.springframework.ai.deepseek.api.ResponseFormat.builder()
                            .type(org.springframework.ai.deepseek.api.ResponseFormat.Type.JSON_OBJECT)
                            .build());
        }

        return builder.build();
    }
}