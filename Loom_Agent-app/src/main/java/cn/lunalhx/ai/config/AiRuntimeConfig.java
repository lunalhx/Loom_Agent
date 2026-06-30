package cn.lunalhx.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Agent runtime 装配入口，聚合五个子配置模块。
 *
 * <p>本类只声明 {@link Import}，不再包含任何 {@code @Bean} 方法。
 */
@Configuration(proxyBeanMethods = false)
@Import({
        AgentLoopAutoConfig.class,
        ToolAutoConfig.class,
        PersistenceAutoConfig.class,
        MemoryAutoConfig.class,
        MetricsAutoConfig.class,
        McpAutoConfig.class
})
public class AiRuntimeConfig {
}
