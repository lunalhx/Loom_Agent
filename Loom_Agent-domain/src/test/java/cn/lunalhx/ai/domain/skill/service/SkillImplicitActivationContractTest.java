package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillActivationException;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SkillImplicitActivationContractTest {

    private final SkillActivationService activation = new SkillActivationService();
    private final SkillCatalogPromptRenderer catalogRenderer = new SkillCatalogPromptRenderer();

    @Test
    public void modelCatalogShowsMetadataOnlyForModelInvocableSkills() throws Exception {
        Path root = Files.createTempDirectory("skill-catalog-prompt");
        Path pkg = root.resolve("review-pr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("SKILL.md"), """
                ---
                name: review-pr
                description: Review pull requests.
                ---
                Secret instruction body.
                """, StandardCharsets.UTF_8);
        byte[] bytes = Files.readAllBytes(pkg.resolve("SKILL.md"));
        SkillCatalogEntry invocable = entry("review-pr", "Review pull requests.", true, true, bytes, pkg);
        SkillCatalogEntry manualOnly = entry("manual-only", "Manual only.", true, false, bytes, pkg);
        SkillCatalogEntry modelOnly = entry("model-only", "Model only.", false, true, bytes, pkg);

        String rendered = catalogRenderer.render(new SkillCatalog(
                List.of(invocable, manualOnly, modelOnly), List.of(), List.of(), List.of()));

        assertTrue(rendered.contains("name: review-pr"));
        assertTrue(rendered.contains("name: model-only"));
        assertTrue(rendered.contains("description: Review pull requests."));
        assertFalse(rendered.contains("Secret instruction body."));
        assertFalse(rendered.contains("manual-only"));
    }

    @Test
    public void implicitActivationReusesExplicitSnapshotShape() throws Exception {
        Path root = Files.createTempDirectory("skill-implicit");
        ActiveSkillSnapshot snapshot = writeAndActivate(root, "review-pr", "Check tests first.");
        assertEquals("review-pr", snapshot.name());
        assertEquals("Check tests first.", snapshot.instructionBody());
        assertTrue(snapshot.sourceLabel().contains("review-pr"));
    }

    @Test
    public void modelOnlySkillCannotBeActivatedExplicitly() throws Exception {
        Path root = Files.createTempDirectory("skill-user-block");
        Path pkg = root.resolve("bg");
        Files.createDirectories(pkg);
        String skillMd = """
                ---
                name: bg
                description: Background guidance.
                user-invocable: false
                ---
                Background body.
                """;
        Files.writeString(pkg.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);
        byte[] bytes = skillMd.getBytes(StandardCharsets.UTF_8);
        SkillCatalogEntry entry = entry("bg", "Background guidance.", false, true, bytes, pkg);
        SkillCatalog catalog = new SkillCatalog(List.of(entry), List.of(), List.of(), List.of());
        try {
            activation.activateExplicit(catalog, List.of("bg"));
            fail("expected user-invocable rejection");
        } catch (SkillActivationException e) {
            assertTrue(e.getMessage().contains("user-invocable"));
        }
        ActiveSkillSnapshot implicit = activation.activateImplicit(catalog, "bg");
        assertEquals("Background body.", implicit.instructionBody());
    }

    @Test
    public void manualOnlySkillCannotBeActivatedImplicitly() throws Exception {
        Path root = Files.createTempDirectory("skill-model-block");
        Path pkg = root.resolve("manual");
        Files.createDirectories(pkg);
        String skillMd = """
                ---
                name: manual
                description: Manual only.
                disable-model-invocation: true
                ---
                Manual body.
                """;
        Files.writeString(pkg.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);
        byte[] bytes = skillMd.getBytes(StandardCharsets.UTF_8);
        SkillCatalogEntry entry = entry("manual", "Manual only.", true, false, bytes, pkg);
        SkillCatalog catalog = new SkillCatalog(List.of(entry), List.of(), List.of(), List.of());
        activation.activateExplicit(catalog, List.of("manual"));
        try {
            activation.activateImplicit(catalog, "manual");
            fail("expected model invocation rejection");
        } catch (SkillActivationException e) {
            assertTrue(e.getMessage().contains("model"));
        }
    }

    @Test
    public void duplicateMergeKeepsSingleSnapshot() throws Exception {
        Path root = Files.createTempDirectory("skill-dedupe-implicit");
        ActiveSkillSnapshot first = writeAndActivate(root, "alpha", "Alpha body.");
        List<ActiveSkillSnapshot> merged = activation.mergeActive(
                List.of(first), writeAndActivate(root, "alpha", "Alpha body."));
        assertEquals(1, merged.size());
        assertSame(first, merged.getFirst());
    }

    private ActiveSkillSnapshot writeAndActivate(Path root, String name, String body) throws Exception {
        Path pkg = root.resolve(name);
        Files.createDirectories(pkg);
        String skillMd = """
                ---
                name: %s
                description: %s skill.
                ---
                %s
                """.formatted(name, name, body);
        Files.writeString(pkg.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);
        byte[] bytes = skillMd.getBytes(StandardCharsets.UTF_8);
        SkillCatalogEntry entry = entry(name, name + " skill.", true, true, bytes, pkg);
        return activation.activateImplicit(new SkillCatalog(List.of(entry), List.of(), List.of(), List.of()), name);
    }

    private static SkillCatalogEntry entry(String name, String description, boolean userInvocable,
                                           boolean modelInvocable, byte[] bytes, Path pkg) throws Exception {
        return new SkillCatalogEntry(name, description, "project .agents/skills/" + name,
                userInvocable, modelInvocable, digest(bytes), null, null, null, List.of(), pkg);
    }

    private static String digest(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
