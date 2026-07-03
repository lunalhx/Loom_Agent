package cn.lunalhx.ai.test;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.entity.StablePrefix;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerAppendService;
import cn.lunalhx.ai.domain.agent.service.ledger.ConversationLedgerInitializer;
import org.junit.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class ConversationLedgerProdConfigTest {

    @Test
    public void prodYamlUsesMandatoryLedgerWithoutRuntimeFlags() {
        Map<?, ?> ledger = ledgerConfig(loadYaml("application-prod.yml"));

        assertFalse(ledger.containsKey("enabled"));
        assertFalse(ledger.containsKey("shadow-enabled"));
        assertThat(ledger.get("compaction-high-watermark")).isEqualTo(200);
        assertThat(ledger.get("compaction-low-watermark")).isEqualTo(50);
    }

    @Test
    public void everyProfileOmitsRemovedRuntimeFlags() {
        for (String resource : new String[]{
                "application-dev.yml", "application-test.yml", "application-prod.yml"}) {
            Map<?, ?> ledger = ledgerConfig(loadYaml(resource));
            assertFalse(resource, ledger.containsKey("enabled"));
            assertFalse(resource, ledger.containsKey("shadow-enabled"));
        }
    }

    @Test
    public void ledgerPropertiesOnlyExposeCompactionDefaults() {
        AgentRuntimeProperties.ConversationLedgerProperties properties =
                new AgentRuntimeProperties.ConversationLedgerProperties();

        assertThat(properties.getCompactionHighWatermark()).isEqualTo(200);
        assertThat(properties.getCompactionLowWatermark()).isEqualTo(50);
    }

    @Test
    public void initializerAndAppendServiceAreAlwaysAvailable() {
        AgentContext context = new AgentContext();
        context.setRunId("mandatory-ledger");
        context.setQuestion("task");

        new ConversationLedgerInitializer().initializeNewConversation(
                context, new StablePrefix("system", "fingerprint"));
        assertNotNull(context.getConversationLedger());

        new ConversationLedgerAppendService().appendAssistant(
                context, "{\"type\":\"final\",\"answer\":\"done\"}", "assistant-1");
        assertThat(context.getConversationLedger().size()).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String resource) {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new ClassPathResource(resource));
        factory.afterPropertiesSet();
        Map<String, Object> root = factory.getObject();
        assertNotNull(root);
        return (Map<String, Object>) root.get("loom");
    }

    private static Map<?, ?> ledgerConfig(Map<String, Object> loom) {
        Map<?, ?> agent = (Map<?, ?>) loom.get("agent");
        assertNotNull(agent);
        Map<?, ?> ledger = (Map<?, ?>) agent.get("conversation-ledger");
        assertNotNull(ledger);
        return ledger;
    }
}
