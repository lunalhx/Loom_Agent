package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.service.observability.NoopAgentMetrics;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryAgentRunRepository;
import cn.lunalhx.ai.infrastructure.adapter.repository.InMemoryTraceRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-memory repositories for the CLI runtime. loom-code Java keeps all
 * session/run artifacts in the workspace {@code .loom-code} directory; the
 * in-memory stores here back the in-process loop until the file stores are
 * wired in.
 */
@Configuration(proxyBeanMethods = false)
public class CliPersistenceAutoConfig {

    @Bean
    public AgentRunRepository agentRunRepository() {
        return new InMemoryAgentRunRepository();
    }

    @Bean
    public AgentCheckpointRepository agentCheckpointRepository() {
        return new InMemoryAgentCheckpointRepository();
    }

    @Bean
    public TraceRecorder traceRecorder() {
        return new InMemoryTraceRecorder();
    }

    @Bean
    public AgentMetrics agentMetrics() {
        return new NoopAgentMetrics();
    }
}
