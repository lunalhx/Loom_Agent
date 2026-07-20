package cn.lunalhx.ai.config;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.UndoProperties;
import cn.lunalhx.ai.domain.common.LoomPaths;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class AgentRuntimeAutoConfig {

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
        Path path = paths.resolveWorkspacePath(config.getPath(), paths.runtimeConfig());
        return new AgentRuntimeConfigRegistry(path, agent, model);
    }

    @Bean
    public Object agentRuntimeConfigHotReloadRegistration(RuntimeConfigProperties config,
                                                          AgentRuntimeConfigRegistry registry,
                                                          LoomConfigWatcher watcher) {
        if (config.isHotReload()) {
            watcher.register(registry.path(), registry::reload);
        }
        return new Object();
    }

    @Bean
    public UndoProperties undoProperties(AgentRuntimeProperties properties) {
        return properties.getUndo() == null ? new UndoProperties() : properties.getUndo();
    }
}
