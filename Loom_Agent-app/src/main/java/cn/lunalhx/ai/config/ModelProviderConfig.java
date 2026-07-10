package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.model.valobj.ModelErrorCode;
import cn.lunalhx.ai.domain.model.valobj.ModelGatewayException;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties.ProviderConfig;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ModelProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderConfig.class);

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel chatModel(ModelRuntimeProperties properties) {
        ProviderConfig activeProvider = properties.activeProvider();
        String provider = properties.getProvider();

        if ("none".equalsIgnoreCase(provider)) {
            log.info("AI provider is 'none', skipping ChatModel creation");
            return null;
        }

        RestClient.Builder restClientBuilder = restClientBuilder(properties);

        if ("deepseek".equalsIgnoreCase(provider)) {
            return deepseekChatModel(activeProvider, restClientBuilder);
        }
        if ("opencode-go".equalsIgnoreCase(provider)) {
            return opencodeGoChatModel(activeProvider, restClientBuilder);
        }

        throw new ModelGatewayException(ModelErrorCode.CONFIG_ERROR,
                "不支持的 AI provider: " + provider, false, null, null);
    }

    private ChatModel deepseekChatModel(ProviderConfig provider, RestClient.Builder restClientBuilder) {
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

        log.info("Created DeepSeekChatModel: baseUrl={}, model={}", provider.getBaseUrl(), provider.getDefaultModel());
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .defaultOptions(defaultOptions)
                .retryTemplate(noRetryTemplate())
                .build();
    }

    private ChatModel opencodeGoChatModel(ProviderConfig provider, RestClient.Builder restClientBuilder) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .completionsPath(StringUtils.defaultIfBlank(provider.getCompletionsPath(), "/v1/chat/completions"))
                .restClientBuilder(restClientBuilder)
                .build();

        OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(provider.getDefaultModel())
                .temperature(provider.getTemperature())
                .maxTokens(provider.getMaxTokens())
                .streamUsage(true)
                .build();

        log.info("Created OpenAiChatModel (OpenCode Go): baseUrl={}, completionsPath={}, model={}",
                provider.getBaseUrl(), provider.getCompletionsPath(), provider.getDefaultModel());
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(defaultOptions)
                .retryTemplate(noRetryTemplate())
                .build();
    }

    private RetryTemplate noRetryTemplate() {
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(new SimpleRetryPolicy(1));
        template.setBackOffPolicy(new NoBackOffPolicy());
        return template;
    }

    private RestClient.Builder restClientBuilder(ModelRuntimeProperties properties) {
        long connectTimeoutMs = properties.getConnectTimeoutMs() != null
                ? Math.max(1L, properties.getConnectTimeoutMs()) : 10000L;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeoutMs);
        factory.setReadTimeout(0);

        return RestClient.builder().requestFactory(factory);
    }

}
