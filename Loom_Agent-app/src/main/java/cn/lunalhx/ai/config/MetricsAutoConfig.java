package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.infrastructure.metrics.MicrometerAgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MetricsAutoConfig {

    @Bean
    public AgentMetrics agentMetrics(MeterRegistry meterRegistry) {
        return new MicrometerAgentMetrics(meterRegistry);
    }
}
