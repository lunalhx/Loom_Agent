package cn.lunalhx.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({
        AgentLoopAutoConfig.class,
        ToolAutoConfig.class,
        PersistenceAutoConfig.class,
        MetricsAutoConfig.class
})
public class AiRuntimeConfig {
}
