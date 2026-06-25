package cn.lunalhx.ai.test;

import cn.lunalhx.ai.config.AiRuntimeConfig;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.junit.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.mock.env.MockEnvironment;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertThrows;

public class AiRuntimeConfigValidationTest {

    @Test
    public void reactiveCompactAttemptsMustBeAtMostOne() {
        ModelRuntimeProperties modelProperties = new ModelRuntimeProperties();
        AgentRuntimeProperties agentProperties = new AgentRuntimeProperties();
        agentProperties.getContext().setReactiveCompactMaxAttempts(2);
        ThreadPoolExecutor executor = executor();
        InitializingBean validator = new AiRuntimeConfig()
                .aiConfigValidator(modelProperties, agentProperties, environment(), executor);

        try {
            assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void contextFallbackMustHaveLargerWindow() {
        ModelRuntimeProperties modelProperties = new ModelRuntimeProperties();
        AgentRuntimeProperties agentProperties = new AgentRuntimeProperties();
        agentProperties.getModelRecovery().setContextFallbackModel("deepseek-v4-pro");
        ThreadPoolExecutor executor = executor();
        InitializingBean validator = new AiRuntimeConfig()
                .aiConfigValidator(modelProperties, agentProperties, environment(), executor);

        try {
            assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void fallbackModelMustBeAllowed() {
        ModelRuntimeProperties modelProperties = new ModelRuntimeProperties();
        modelProperties.getResilience().setFallbackModel("not-allowed");
        AgentRuntimeProperties agentProperties = new AgentRuntimeProperties();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 10, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.model.chat", "deepseek")
                .withProperty("spring.ai.deepseek.base-url", "https://api.deepseek.com")
                .withProperty("spring.ai.deepseek.api-key", "test-key")
                .withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash");
        InitializingBean validator = new AiRuntimeConfig()
                .aiConfigValidator(modelProperties, agentProperties, environment, executor);

        try {
            assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        } finally {
            executor.shutdownNow();
        }
    }

    private ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(2, 10, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("spring.ai.model.chat", "deepseek")
                .withProperty("spring.ai.deepseek.base-url", "https://api.deepseek.com")
                .withProperty("spring.ai.deepseek.api-key", "test-key")
                .withProperty("spring.ai.deepseek.chat.model", "deepseek-v4-flash");
    }

}
