package cn.lunalhx.ai.config;

import cn.lunalhx.ai.trigger.http.StreamRequestLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentHttpAutoConfig {

    @Bean
    public StreamRequestLimitProperties streamRequestLimitProperties() {
        return new StreamRequestLimitProperties();
    }

    @Bean
    public StreamRequestLimiter streamRequestLimiter(StreamRequestLimitProperties properties) {
        StreamRequestLimiter.Config config = new StreamRequestLimiter.Config();
        config.enabled = properties.isEnabled();
        config.clientIdHeader = properties.getClientIdHeader();
        config.trustForwardedHeaders = properties.isTrustForwardedHeaders();
        config.maxClientStates = properties.getMaxClientStates();
        config.clientStateTtlSeconds = properties.getClientStateTtlSeconds();
        var ask = properties.getAgentAsk();
        config.agentAsk = new StreamRequestLimiter.EndpointLimit(
                ask.getMaxConcurrentGlobal(), ask.getMaxConcurrentPerClient(),
                ask.getMaxStartsPerWindow(), ask.getWindowSeconds());
        return new StreamRequestLimiter(config);
    }
}
