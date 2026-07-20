package cn.lunalhx.ai.test;

import cn.lunalhx.ai.config.AgentRuntimeConfigRegistry;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRunConfig;
import cn.lunalhx.ai.domain.model.valobj.ModelRuntimeProperties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AgentRuntimeConfigRegistryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void validReloadPublishesVersionedSnapshotAndUpdatesLiveConfig() throws Exception {
        Path file = temporaryFolder.newFile("agent-runtime.yml").toPath();
        Files.writeString(file, "agent:\n  max-steps: 42\n  long-term-memory:\n    max-selected: 2\n    pinned-limit: 2\n");
        AgentRuntimeProperties agent = new AgentRuntimeProperties();
        AgentRuntimeConfigRegistry registry = new AgentRuntimeConfigRegistry(file, agent, model());
        registry.initialize();
        assertEquals(1L, registry.capture().version());
        assertEquals(Integer.valueOf(42), agent.getMaxSteps());
        assertEquals(64, registry.capture().fingerprint().length());
    }

    @Test
    public void invalidReloadKeepsLastKnownGoodSnapshot() throws Exception {
        Path file = temporaryFolder.newFile("agent-runtime-invalid.yml").toPath();
        Files.writeString(file, "agent:\n  max-steps: 42\n");
        AgentRuntimeProperties agent = new AgentRuntimeProperties();
        AgentRuntimeConfigRegistry registry = new AgentRuntimeConfigRegistry(file, agent, model());
        registry.initialize();
        Files.writeString(file, "agent:\n  max-steps: 0\n");
        assertThrows(IllegalStateException.class, registry::reload);
        assertEquals(1L, registry.capture().version());
        assertEquals(Integer.valueOf(42), agent.getMaxSteps());
    }

    @Test
    public void clientConstructionSettingsAreRejectedAtRuntime() throws Exception {
        Path file = temporaryFolder.newFile("agent-runtime-client.yml").toPath();
        Files.writeString(file, "ai:\n  providers: {}\n");
        AgentRuntimeConfigRegistry registry = new AgentRuntimeConfigRegistry(
                file, new AgentRuntimeProperties(), model());
        IllegalStateException error = assertThrows(IllegalStateException.class, registry::initialize);
        assertTrue(error.getMessage().contains("startup-only"));
    }

    @Test
    public void capturedRunConfigDoesNotChangeAfterReload() throws Exception {
        Path file = temporaryFolder.newFile("agent-runtime-snapshot.yml").toPath();
        Files.writeString(file, "agent:\n  max-steps: 42\n  long-term-memory:\n    max-selected: 2\n    pinned-limit: 2\n");
        AgentRuntimeConfigRegistry registry = new AgentRuntimeConfigRegistry(
                file, new AgentRuntimeProperties(), model());
        registry.initialize();
        AgentRunConfig firstRun = registry.captureRunConfig();

        Files.writeString(file, "agent:\n  max-steps: 77\n  long-term-memory:\n    max-selected: 3\n    pinned-limit: 3\nai:\n  default-model: deepseek-v4-pro\n");
        registry.reload();
        AgentRunConfig secondRun = registry.captureRunConfig();

        assertEquals(1L, firstRun.version());
        assertEquals(Integer.valueOf(42), firstRun.agent().getMaxSteps());
        assertEquals(2, firstRun.agent().getLongTermMemory().getMaxSelected());
        assertEquals("deepseek-v4-flash", firstRun.model().resolvedDefaultModel());
        assertEquals(2L, secondRun.version());
        assertEquals(Integer.valueOf(77), secondRun.agent().getMaxSteps());
        assertEquals(3, secondRun.agent().getLongTermMemory().getMaxSelected());
        assertEquals("deepseek-v4-pro", secondRun.model().resolvedDefaultModel());
    }

    private ModelRuntimeProperties model() {
        ModelRuntimeProperties model = new ModelRuntimeProperties();
        ModelRuntimeProperties.ProviderConfig provider = new ModelRuntimeProperties.ProviderConfig();
        provider.setBaseUrl("https://example.invalid");
        provider.setApiKey("secret");
        provider.setDefaultModel("deepseek-v4-flash");
        LinkedHashMap<String, ModelRuntimeProperties.ProviderConfig> providers = new LinkedHashMap<>();
        providers.put("deepseek", provider);
        model.setProviders(providers);
        return model;
    }
}
