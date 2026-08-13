package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.adapter.port.AgentCheckpointRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentMetrics;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentRunRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AgentSessionRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.AttemptLeaseRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.ConversationHistoryRepository;
import cn.lunalhx.ai.domain.agent.adapter.port.TraceRecorder;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.context.SanitizationPolicy;
import cn.lunalhx.ai.domain.agent.service.context.SecretRedactor;
import cn.lunalhx.ai.domain.agent.service.observability.NoopAgentMetrics;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.store.ArtifactRedactor;
import cn.lunalhx.ai.infrastructure.store.FileAgentCheckpointRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentRunRepository;
import cn.lunalhx.ai.infrastructure.store.FileAttemptLeaseRepository;
import cn.lunalhx.ai.infrastructure.store.FileAgentSessionRepository;
import cn.lunalhx.ai.infrastructure.store.FileConversationHistoryRepository;
import cn.lunalhx.ai.infrastructure.store.FileTraceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * File-backed repositories for the CLI runtime. The same repository beans are
 * consumed by the Spring-owned Agent loop and the CLI session facade, so a
 * process resume observes the artifacts produced by the active loop.
 */
@Configuration(proxyBeanMethods = false)
public class CliPersistenceAutoConfig {

    @Bean
    public ArtifactRedactor artifactRedactor(AgentRuntimeProperties agent,
                                             ModelRuntimeProperties model) {
        Set<String> providerKeys = new LinkedHashSet<>();
        if (model.getProviders() != null && model.getProvider() != null) {
            ModelRuntimeProperties.ProviderConfig provider = model.getProviders().get(model.getProvider());
            if (provider != null && provider.getApiKey() != null) {
                providerKeys.add(provider.getApiKey());
            }
        }
        return new ArtifactRedactor(SecretRedactor.fromPolicy(
                SanitizationPolicy.withEnvDiscovery(
                        new LinkedHashSet<>(agent.getSecretEnvNames()), providerKeys)));
    }

    @Bean
    public AgentSessionRepository agentSessionRepository(AgentRuntimeProperties agent,
                                                         ObjectMapper mapper,
                                                         ArtifactRedactor redactor) {
        return new FileAgentSessionRepository(workspace(agent), mapper, redactor);
    }

    @Bean
    public AgentRunRepository agentRunRepository(AgentRuntimeProperties agent,
                                                 ObjectMapper mapper,
                                                 ArtifactRedactor redactor) {
        return new FileAgentRunRepository(workspace(agent), mapper, redactor);
    }

    @Bean
    public AgentCheckpointRepository agentCheckpointRepository(AgentRuntimeProperties agent,
                                                               ObjectMapper mapper,
                                                               ArtifactRedactor redactor) {
        return new FileAgentCheckpointRepository(workspace(agent), mapper, redactor);
    }

    @Bean
    public AttemptLeaseRepository attemptLeaseRepository(AgentRuntimeProperties agent,
                                                         ObjectMapper mapper) {
        return new FileAttemptLeaseRepository(workspace(agent), mapper);
    }

    @Bean
    public ConversationHistoryRepository conversationHistoryRepository(AgentRuntimeProperties agent,
                                                                       ObjectMapper mapper,
                                                                       ArtifactRedactor redactor) {
        return new FileConversationHistoryRepository(workspace(agent), mapper, redactor);
    }

    @Bean
    public TraceRecorder traceRecorder(AgentRuntimeProperties agent,
                                       ObjectMapper mapper,
                                       ArtifactRedactor redactor) {
        return new FileTraceRecorder(workspace(agent), mapper, redactor);
    }

    @Bean
    public AgentMetrics agentMetrics() {
        return new NoopAgentMetrics();
    }

    private static Path workspace(AgentRuntimeProperties agent) {
        String root = agent.getWorkspaceRoot();
        return Path.of(root == null || root.isBlank() ? "." : root)
                .toAbsolutePath().normalize();
    }
}
