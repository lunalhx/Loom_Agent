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
import static org.junit.Assert.*;

/**
 * D2: Production ledger config binding and on/off path verification.
 *
 * <p>Verifies:
 * <ul>
 *   <li>application-prod.yml has {@code enabled=true, shadow-enabled=false}</li>
 *   <li>Diagnostic payload body stays off in prod</li>
 *   <li>Ledger services behave correctly under enabled vs. disabled configs</li>
 * </ul>
 */
public class ConversationLedgerProdConfigTest {

    // ================================================================
    // 1. Prod YAML config binding — direct parse, no Spring context
    // ================================================================

    @Test
    public void prodYamlLedgerEnabledTrue() {
        Map<String, Object> props = loadProdYaml();
        Map<?, ?> agent = (Map<?, ?>) props.get("agent");
        assertNotNull("loom.agent must exist in application-prod.yml", agent);
        Map<?, ?> ledger = (Map<?, ?>) agent.get("conversation-ledger");
        assertNotNull("loom.agent.conversation-ledger must exist", ledger);

        assertEquals("enabled must be true in prod",
                Boolean.TRUE, ledger.get("enabled"));
        assertEquals("shadow-enabled must be false in prod",
                Boolean.FALSE, ledger.get("shadow-enabled"));
    }

    @Test
    public void prodYamlDiagnosticPayloadBodyOff() {
        Map<String, Object> props = loadProdYaml();
        Map<?, ?> ai = (Map<?, ?>) props.get("ai");
        assertNotNull("loom.ai must exist", ai);
        Map<?, ?> diagnostics = (Map<?, ?>) ai.get("diagnostics");
        assertNotNull("loom.ai.diagnostics must exist", diagnostics);
        Map<?, ?> cache = (Map<?, ?>) diagnostics.get("prompt-cache");
        assertNotNull("loom.ai.diagnostics.prompt-cache must exist", cache);

        // Raw YAML contains Spring placeholders; verify defaults resolve to false
        String enabledRaw = String.valueOf(cache.get("enabled"));
        assertTrue("diagnostics prompt-cache default must be false, got: " + enabledRaw,
                enabledRaw.endsWith(":false}") || Boolean.FALSE.equals(cache.get("enabled")));
        String bodyRaw = String.valueOf(cache.get("log-redacted-body"));
        assertTrue("diagnostics log-redacted-body default must be false, got: " + bodyRaw,
                bodyRaw.endsWith(":false}") || Boolean.FALSE.equals(cache.get("log-redacted-body")));
    }

    @Test
    public void prodYamlCompactionWatermarkDefaults() {
        Map<String, Object> props = loadProdYaml();
        Map<?, ?> agent = (Map<?, ?>) props.get("agent");
        Map<?, ?> ledger = (Map<?, ?>) agent.get("conversation-ledger");

        // Prod doesn't override watermark values — they fall back to Java defaults.
        // Verify no unexpected overrides.
        Object hw = ledger.get("compaction-high-watermark");
        Object lw = ledger.get("compaction-low-watermark");
        if (hw != null) {
            assertThat((Integer) hw).isGreaterThan(0);
        }
        if (lw != null) {
            assertThat((Integer) lw).isGreaterThan(0);
        }
        // If absent, Java defaults (200/50) apply — acceptable.
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadProdYaml() {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new ClassPathResource("application-prod.yml"));
        factory.afterPropertiesSet();
        Map<String, Object> root = factory.getObject();
        assertNotNull("application-prod.yml must be loadable", root);
        Map<String, Object> loom = (Map<String, Object>) root.get("loom");
        assertNotNull("loom key must exist in application-prod.yml", loom);
        return loom;
    }

    // ================================================================
    // 2. Config on/off paths — runtime gating verification
    // ================================================================

    @Test
    public void enabledConfigInitCreatesLedger() {
        AgentRuntimeProperties.ConversationLedgerProperties cfg =
                new AgentRuntimeProperties.ConversationLedgerProperties();
        cfg.setEnabled(true);
        cfg.setShadowEnabled(false);

        ConversationLedgerInitializer init = new ConversationLedgerInitializer(cfg);
        AgentContext ctx = newContext("r-on-1", "test");
        StablePrefix sp = new StablePrefix("base", "fp-1");

        init.initializeNewConversation(ctx, sp);

        assertNotNull("ledger must be created when enabled=true", ctx.getConversationLedger());
        assertEquals("generation starts at 0 when enabled", 0, ctx.getGeneration());
        assertNotNull("stable prefix must be set when enabled", ctx.getStablePrefix());
    }

    @Test
    public void disabledConfigInitSkipsLedger() {
        AgentRuntimeProperties.ConversationLedgerProperties cfg =
                new AgentRuntimeProperties.ConversationLedgerProperties();
        cfg.setEnabled(false);
        cfg.setShadowEnabled(false);

        ConversationLedgerInitializer init = new ConversationLedgerInitializer(cfg);
        AgentContext ctx = newContext("r-off-1", "test");
        StablePrefix sp = new StablePrefix("base", "fp-2");

        init.initializeNewConversation(ctx, sp);

        assertNull("ledger must be null when disabled", ctx.getConversationLedger());
    }

    @Test
    public void shadowConfigInitCreatesLedgerForDiagnostics() {
        AgentRuntimeProperties.ConversationLedgerProperties cfg =
                new AgentRuntimeProperties.ConversationLedgerProperties();
        cfg.setEnabled(false);
        cfg.setShadowEnabled(true);

        ConversationLedgerInitializer init = new ConversationLedgerInitializer(cfg);
        AgentContext ctx = newContext("r-shadow-1", "test");
        StablePrefix sp = new StablePrefix("base", "fp-3");

        init.initializeNewConversation(ctx, sp);

        assertNotNull("ledger must be created in shadow mode", ctx.getConversationLedger());
    }

    @Test
    public void appendServiceActiveWhenEnabled() {
        AgentRuntimeProperties.ConversationLedgerProperties cfg =
                new AgentRuntimeProperties.ConversationLedgerProperties();
        cfg.setEnabled(true);
        cfg.setShadowEnabled(false);

        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(cfg);
        assertTrue("append service must be active when enabled=true", svc.isActive());
    }

    @Test
    public void appendServiceActiveWhenShadowEnabled() {
        AgentRuntimeProperties.ConversationLedgerProperties cfg =
                new AgentRuntimeProperties.ConversationLedgerProperties();
        cfg.setEnabled(false);
        cfg.setShadowEnabled(true);

        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(cfg);
        assertTrue("append service must be active when shadow-enabled=true", svc.isActive());
    }

    @Test
    public void appendServiceInactiveWhenBothFalse() {
        AgentRuntimeProperties.ConversationLedgerProperties cfg =
                new AgentRuntimeProperties.ConversationLedgerProperties();
        cfg.setEnabled(false);
        cfg.setShadowEnabled(false);

        ConversationLedgerAppendService svc = new ConversationLedgerAppendService(cfg);
        assertFalse("append service must be inactive when both flags false", svc.isActive());
    }

    @Test
    public void javaDefaultsAreSafeDisabled() {
        // If no YAML overrides, Java defaults must keep ledger off.
        AgentRuntimeProperties.ConversationLedgerProperties defaults =
                new AgentRuntimeProperties.ConversationLedgerProperties();
        assertFalse("Java default enabled must be false", defaults.getEnabled());
        assertFalse("Java default shadowEnabled must be false", defaults.getShadowEnabled());
    }

    // ================================================================
    // 3. Rollback simulation — toggling enabled off restores old path
    // ================================================================

    @Test
    public void rollbackFromEnabledToDisabledShouldClearLedgerBehavior() {
        // Simulate: start with enabled=true, then rollback to enabled=false
        AgentRuntimeProperties.ConversationLedgerProperties enabledCfg =
                new AgentRuntimeProperties.ConversationLedgerProperties();
        enabledCfg.setEnabled(true);

        AgentRuntimeProperties.ConversationLedgerProperties disabledCfg =
                new AgentRuntimeProperties.ConversationLedgerProperties();
        disabledCfg.setEnabled(false);

        // Enabled path
        ConversationLedgerInitializer enabledInit = new ConversationLedgerInitializer(enabledCfg);
        AgentContext ctx = newContext("r-rollback-1", "test");
        enabledInit.initializeNewConversation(ctx, new StablePrefix("base", "fp-rb"));
        assertNotNull("ledger must exist before rollback", ctx.getConversationLedger());

        // Rollback — new init with disabled config should not touch ledger
        // (rollback only requires flag flip + restart; no DB migration)
        ConversationLedgerInitializer disabledInit = new ConversationLedgerInitializer(disabledCfg);
        AgentContext freshCtx = newContext("r-rollback-2", "test");
        disabledInit.initializeNewConversation(freshCtx, new StablePrefix("base2", "fp-rb2"));
        assertNull("new ctx after rollback must have no ledger", freshCtx.getConversationLedger());

        // Append service also goes inactive
        ConversationLedgerAppendService appendSvc = new ConversationLedgerAppendService(disabledCfg);
        assertFalse("append service must be inactive after rollback", appendSvc.isActive());
    }

    // ================================================================
    // helpers
    // ================================================================

    private static AgentContext newContext(String runId, String question) {
        AgentContext ctx = new AgentContext();
        ctx.setRunId(runId);
        ctx.setQuestion(question);
        return ctx;
    }
}
