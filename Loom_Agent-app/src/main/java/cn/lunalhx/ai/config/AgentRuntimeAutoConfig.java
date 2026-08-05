package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.common.LoomPaths;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentRuntimeAutoConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    @ConfigurationProperties(prefix = "loom.ai")
    public ModelRuntimeProperties modelRuntimeProperties() {
        return new ModelRuntimeProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "loom.agent")
    public AgentRuntimeProperties agentRuntimeProperties() {
        return new AgentRuntimeProperties();
    }

    @Bean(initMethod = "initialize")
    public AgentRuntimeConfigRegistry agentRuntimeConfigRegistry(
            RuntimeConfigProperties config,
            AgentRuntimeProperties agent,
            ModelRuntimeProperties model,
            LoomPaths paths) {
        return new AgentRuntimeConfigRegistry(paths.resolveWorkspacePath(config.getPath(), paths.runtimeConfig()),
                agent, model);
    }
}
