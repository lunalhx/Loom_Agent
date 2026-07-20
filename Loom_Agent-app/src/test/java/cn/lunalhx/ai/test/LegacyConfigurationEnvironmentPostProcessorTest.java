package cn.lunalhx.ai.test;

import cn.lunalhx.ai.config.LegacyConfigurationEnvironmentPostProcessor;
import org.junit.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.Assert.assertEquals;

public class LegacyConfigurationEnvironmentPostProcessorTest {

    @Test
    public void legacyModelSettingsAreMigratedInMemory() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.deepseek.api-key", "legacy-key");
        new LegacyConfigurationEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        assertEquals("legacy-key",
                environment.getProperty("loom.ai.providers.deepseek.api-key"));
    }

    @Test
    public void currentSettingWinsOverLegacySetting() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.deepseek.api-key", "legacy-key")
                .withProperty("loom.ai.providers.deepseek.api-key", "current-key");
        new LegacyConfigurationEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        assertEquals("current-key",
                environment.getProperty("loom.ai.providers.deepseek.api-key"));
    }
}
