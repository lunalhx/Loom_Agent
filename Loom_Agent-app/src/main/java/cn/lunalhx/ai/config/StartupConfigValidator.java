package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import cn.lunalhx.ai.infrastructure.gateway.ChatModelFactoryRegistry;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;

import java.util.concurrent.ThreadPoolExecutor;

final class StartupConfigValidator {

    private StartupConfigValidator() {
    }

    static void validate(ModelRuntimeProperties model,
                         AgentRuntimeProperties agent,
                         StreamRequestLimitProperties stream,
                         Environment environment,
                         ThreadPoolExecutor executor,
                         ChatModelFactoryRegistry factories) {
        if (environment.containsProperty("loom.agent.conversation-ledger.enabled")
                || environment.containsProperty("loom.agent.conversation-ledger.shadow-enabled")) {
            throw new IllegalStateException(
                    "ledger 已是唯一提示词路径，conversation-ledger.enabled/shadow-enabled 配置已删除");
        }
        if ("none".equalsIgnoreCase(environment.getProperty("spring.ai.model.chat", "deepseek"))
                || "none".equalsIgnoreCase(model.getProvider())) {
            return;
        }
        RuntimeConfigValidators.validate(agent, model);
        factories.require(model.getProvider());
        ModelRuntimeProperties.ProviderConfig provider = model.activeProvider();
        if (StringUtils.isBlank(provider.getBaseUrl())) {
            throw new IllegalStateException("loom.ai.providers." + model.getProvider() + ".base-url is required");
        }
        if (StringUtils.isBlank(provider.getApiKey())) {
            throw new IllegalStateException("loom.ai.providers." + model.getProvider() + ".api-key is required");
        }
        model.normalizeModel(model.resolvedDefaultModel(), provider.getDefaultModel());
        if (executor.getMaximumPoolSize() < agent.getSubAgentMaxConcurrency() + 1) {
            throw new IllegalStateException(
                    "thread.pool.executor.config.max-pool-size must exceed loom.agent.sub-agent-max-concurrency");
        }
        if (stream.isEnabled()) {
            validateEndpoint(stream.getAgentAsk(), "loom.http.stream-limit.agent-ask");
            if (stream.getClientStateTtlSeconds() <= stream.getAgentAsk().getWindowSeconds()) {
                throw new IllegalStateException(
                        "loom.http.stream-limit.client-state-ttl-seconds must exceed window-seconds");
            }
            if (stream.getMaxClientStates() <= 0) {
                throw new IllegalStateException("loom.http.stream-limit.max-client-states must be greater than zero");
            }
        }
    }

    private static void validateEndpoint(StreamRequestLimitProperties.EndpointLimit limit, String path) {
        if (limit.getMaxConcurrentGlobal() <= 0
                || limit.getMaxConcurrentPerClient() <= 0
                || limit.getMaxStartsPerWindow() <= 0
                || limit.getWindowSeconds() <= 0) {
            throw new IllegalStateException(path + " limits must be greater than zero");
        }
        if (limit.getMaxConcurrentPerClient() > limit.getMaxConcurrentGlobal()) {
            throw new IllegalStateException(path
                    + ".max-concurrent-per-client cannot exceed max-concurrent-global");
        }
    }
}
