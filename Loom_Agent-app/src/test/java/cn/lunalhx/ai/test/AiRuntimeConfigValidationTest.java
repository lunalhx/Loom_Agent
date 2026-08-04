package cn.lunalhx.ai.test;

import cn.lunalhx.ai.config.AgentLoopAutoConfig;
import cn.lunalhx.ai.config.StreamRequestLimitProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.junit.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.mock.env.MockEnvironment;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AiRuntimeConfigValidationTest {


    @Test
    public void contextFallbackMustHaveLargerWindow() {
        ModelRuntimeProperties modelProperties = new ModelRuntimeProperties();
        AgentRuntimeProperties agentProperties = new AgentRuntimeProperties();
        agentProperties.getModelRecovery().setContextFallbackModel("deepseek-v4-pro");
        ThreadPoolExecutor executor = executor();
        InitializingBean validator = new AgentLoopAutoConfig()
                .aiConfigValidator(modelProperties, agentProperties, streamLimitProps(), environment(), executor);

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
        InitializingBean validator = new AgentLoopAutoConfig()
                .aiConfigValidator(modelProperties, agentProperties, streamLimitProps(), environment, executor);

        try {
            assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void removedLedgerEnabledPropertyMustFailStartup() {
        assertRemovedLedgerPropertyRejected("loom.agent.conversation-ledger.enabled");
    }

    @Test
    public void removedLedgerShadowPropertyMustFailStartup() {
        assertRemovedLedgerPropertyRejected("loom.agent.conversation-ledger.shadow-enabled");
    }

    @Test
    public void removedContextStorageRootPropertyMustFailStartup() {
        assertRemovedLedgerPropertyRejected("loom.agent.context.storage-root");
    }

    @Test
    public void removedContextToolPreviewPropertyMustFailStartup() {
        assertRemovedLedgerPropertyRejected("loom.agent.context.tool-preview-chars");
    }

    private void assertRemovedLedgerPropertyRejected(String property) {
        ModelRuntimeProperties modelProperties = new ModelRuntimeProperties();
        AgentRuntimeProperties agentProperties = new AgentRuntimeProperties();
        ThreadPoolExecutor executor = executor();
        MockEnvironment environment = environment().withProperty(property, "false");
        InitializingBean validator = new AgentLoopAutoConfig()
                .aiConfigValidator(modelProperties, agentProperties, streamLimitProps(), environment, executor);

        try {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class, validator::afterPropertiesSet);
            assertTrue(error.getMessage().contains("配置已删除"));
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

    private StreamRequestLimitProperties streamLimitProps() {
        StreamRequestLimitProperties props = new StreamRequestLimitProperties();
        props.setEnabled(false);
        return props;
    }
}
