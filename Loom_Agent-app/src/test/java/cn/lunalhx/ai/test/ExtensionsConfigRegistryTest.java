package cn.lunalhx.ai.test;

import cn.lunalhx.ai.config.ExtensionsConfigRegistry;
import cn.lunalhx.ai.domain.agent.model.entity.SkillCatalog;
import cn.lunalhx.ai.domain.tool.model.McpClientProperties;
import cn.lunalhx.ai.infrastructure.mcp.ExtensionsConfigLoader;
import cn.lunalhx.ai.infrastructure.skill.FileSystemSkillRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ExtensionsConfigRegistryTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void extensionSkillStatesFilterTheRuntimeCatalog() throws Exception {
        Path workspace = temporaryFolder.newFolder("workspace").toPath();
        writeSkill(workspace, "alpha");
        writeSkill(workspace, "beta");
        Path config = temporaryFolder.newFile("loom-extensions.json").toPath();
        Files.writeString(config, """
                {"mcpServers":{},"skills":{"alpha":{"enabled":false},"beta":{"enabled":true}}}
                """);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ExtensionsConfigRegistry registry = new ExtensionsConfigRegistry(
                new ExtensionsConfigLoader(config, mapper, new McpClientProperties()), mapper);
        registry.initialize();
        FileSystemSkillRepository repository = new FileSystemSkillRepository(
                temporaryFolder.newFolder("user-skills").toPath(), ".agents/skills",
                () -> registry.capture().config().getSkills());

        SkillCatalog catalog = repository.discover(workspace);
        assertEquals(1, catalog.skills().size());
        assertEquals("beta", catalog.skills().getFirst().name());
        assertEquals("beta", repository.resolve("beta", workspace).name());
    }

    @Test
    public void failedConsumerKeepsLastKnownGoodExtensionSnapshot() throws Exception {
        Path config = temporaryFolder.newFile("extensions-lkg.json").toPath();
        Files.writeString(config, "{\"mcpServers\":{},\"skills\":{}}");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ExtensionsConfigRegistry registry = new ExtensionsConfigRegistry(
                new ExtensionsConfigLoader(config, mapper, new McpClientProperties()), mapper);
        registry.initialize();
        Files.writeString(config, "{\"mcpServers\":{},\"skills\":{\"new-skill\":{\"enabled\":true}}}");

        assertThrows(IllegalStateException.class,
                () -> registry.reload(ignored -> {
                    throw new IllegalStateException("consumer rejected snapshot");
                }));
        assertEquals(1L, registry.capture().version());
        assertEquals(0, registry.capture().config().getSkills().size());
    }

    private void writeSkill(Path workspace, String name) throws Exception {
        Path directory = workspace.resolve(".agents/skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), """
                ---
                name: %s
                description: test skill
                ---
                Body
                """.formatted(name));
    }
}
