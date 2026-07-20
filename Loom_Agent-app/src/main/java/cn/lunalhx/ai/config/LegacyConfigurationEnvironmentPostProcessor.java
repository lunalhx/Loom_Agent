package cn.lunalhx.ai.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LegacyConfigurationEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    private static final Map<String, String> ALIASES = Map.of(
            "spring.ai.deepseek.api-key", "loom.ai.providers.deepseek.api-key",
            "spring.ai.deepseek.base-url", "loom.ai.providers.deepseek.base-url",
            "spring.ai.deepseek.chat.options.model", "loom.ai.providers.deepseek.default-model",
            "spring.ai.deepseek.chat.options.temperature", "loom.ai.providers.deepseek.temperature",
            "spring.ai.deepseek.chat.options.max-tokens", "loom.ai.providers.deepseek.max-tokens"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        Map<String, Object> migrated = new LinkedHashMap<>();
        ALIASES.forEach((legacy, current) -> {
            if (!environment.containsProperty(current) && environment.containsProperty(legacy)) {
                migrated.put(current, environment.getProperty(legacy));
            }
        });
        if (!migrated.isEmpty()) {
            environment.getPropertySources().addLast(
                    new MapPropertySource("loomLegacyCompatibility", migrated));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
