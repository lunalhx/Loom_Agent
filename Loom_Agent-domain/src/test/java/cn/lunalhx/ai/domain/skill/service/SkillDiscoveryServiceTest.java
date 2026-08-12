package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkillDiscoveryServiceTest {

    private final SkillDiscoveryService discovery = new SkillDiscoveryService();

    @Test
    public void discoversFourSourcesWithDeterministicPrecedence() throws Exception {
        Path home = Files.createTempDirectory("loom-skill-home");
        Path workspace = Files.createTempDirectory("loom-skill-workspace").toRealPath();
        writeSkill(home.resolve(".agents/skills/shared-skill"), "shared-skill", "User agents wins.");
        writeSkill(home.resolve(".claude/skills/shared-skill"), "shared-skill", "User claude loses.");
        writeSkill(workspace.resolve(".agents/skills/shared-skill"), "shared-skill", "Project agents loses.");
        writeSkill(workspace.resolve(".claude/skills/shared-skill"), "shared-skill", "Project claude loses.");

        SkillCatalog catalog = discovery.discover(workspace, home);

        SkillCatalogEntry effective = catalog.effective().stream()
                .filter(entry -> entry.name().equals("shared-skill"))
                .findFirst()
                .orElseThrow();
        assertEquals("user .agents/skills/shared-skill", effective.sourceLabel());
        assertEquals(3, catalog.shadowed().size());
        List<String> shadowSources = catalog.shadowed().stream()
                .map(entry -> entry.sourceLabel())
                .sorted()
                .collect(Collectors.toList());
        assertEquals(List.of(
                "project .agents/skills/shared-skill",
                "project .claude/skills/shared-skill",
                "user .claude/skills/shared-skill"), shadowSources);
    }

    @Test
    public void userSkillWinsOverProjectSkillWithSameName() throws Exception {
        Path home = Files.createTempDirectory("loom-skill-home");
        Path workspace = Files.createTempDirectory("loom-skill-workspace").toRealPath();
        writeSkill(home.resolve(".agents/skills/personal"), "personal", "Personal copy.");
        writeSkill(workspace.resolve(".agents/skills/personal"), "personal", "Project copy.");

        SkillCatalog catalog = discovery.discover(workspace, home);

        assertEquals("user .agents/skills/personal",
                catalog.effective().getFirst().sourceLabel());
        assertEquals(1, catalog.shadowed().size());
        assertEquals("project .agents/skills/personal", catalog.shadowed().getFirst().sourceLabel());
    }

    @Test
    public void invalidPackagesAreReportedWithoutPartialLoading() throws Exception {
        Path home = Files.createTempDirectory("loom-skill-home");
        Path workspace = Files.createTempDirectory("loom-skill-workspace").toRealPath();
        Path broken = workspace.resolve(".agents/skills/broken-skill");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("SKILL.md"), """
                ---
                name: broken-skill
                ---
                missing description
                """);

        SkillCatalog catalog = discovery.discover(workspace, home);

        assertTrue(catalog.effective().isEmpty());
        assertEquals(1, catalog.invalid().size());
        assertTrue(catalog.invalid().getFirst().pathLabel().contains("broken-skill"));
        assertFalse(catalog.invalid().getFirst().reasons().isEmpty());
    }

    @Test
    public void sortsEffectiveEntriesByName() throws Exception {
        Path home = Files.createTempDirectory("loom-skill-home");
        Path workspace = Files.createTempDirectory("loom-skill-workspace").toRealPath();
        writeSkill(workspace.resolve(".agents/skills/zebra"), "zebra", "Z.");
        writeSkill(workspace.resolve(".agents/skills/alpha"), "alpha", "A.");

        SkillCatalog catalog = discovery.discover(workspace, home);

        assertEquals(List.of("alpha", "zebra"),
                catalog.effective().stream().map(SkillCatalogEntry::name).toList());
    }

    private static void writeSkill(Path root, String name, String description) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                Instruction body for %s.
                """.formatted(name, description, name), StandardCharsets.UTF_8);
    }
}
