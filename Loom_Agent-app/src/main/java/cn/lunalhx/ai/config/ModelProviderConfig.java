package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties.ProviderConfig;
import cn.lunalhx.ai.infrastructure.gateway.ChatModelFactory;
import cn.lunalhx.ai.infrastructure.gateway.ChatModelFactoryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class ModelProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderConfig.class);

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel chatModel(ModelRuntimeProperties properties,
                               ChatModelFactoryRegistry factoryRegistry) {
        ProviderConfig activeProvider = properties.activeProvider();
        String provider = properties.getProvider();

        if ("none".equalsIgnoreCase(provider)) {
            log.info("AI provider is 'none', skipping ChatModel creation");
            return null;
        }

        RestClient.Builder restClientBuilder = restClientBuilder(properties);
        ChatModelFactory factory = factoryRegistry.require(provider);
        ChatModel model = factory.create(activeProvider, restClientBuilder);
        log.info("Created ChatModel: provider={}, baseUrl={}, model={}",
                provider, activeProvider.getBaseUrl(), activeProvider.getDefaultModel());
        return model;
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
