package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillActivationException;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogLimits;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SkillExplicitActivationContractTest {

    private final SkillSelectorParser selectorParser = new SkillSelectorParser();
    private final SkillActivationService activation = new SkillActivationService();
    private final SkillPromptRenderer renderer = new SkillPromptRenderer();

    @Test
    public void selectorDedupesByAppearanceOrderAndRemovesFromTask() {
        SkillSelectorParser.ParsedSelectors parsed =
                selectorParser.parse("  $beta please $alpha then $beta again  ");
        assertEquals(List.of("beta", "alpha"), parsed.names());
        assertEquals("please then again", parsed.taskWithoutSelectors());
        assertTrue(parsed.hadSelectors());
    }

    @Test
    public void overBudgetBodyIsRejectedAtomically() throws Exception {
        Path root = Files.createTempDirectory("skill-budget");
        Path pkg = root.resolve("huge");
        Files.createDirectories(pkg);
        String body = "x".repeat(SkillCatalogLimits.MAX_ACTIVE_BODY_CHARS + 1);
        String skillMd = """
                ---
                name: huge
                description: Huge body.
                ---
                %s
                """.formatted(body);
        byte[] bytes = skillMd.getBytes(StandardCharsets.UTF_8);
        Files.write(pkg.resolve("SKILL.md"), bytes);
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "huge", "Huge body.", "project .agents/skills/huge", true, true,
                digest(bytes), null, null, null, List.of(), pkg);
        try {
            activation.activateExplicit(new SkillCatalog(List.of(entry), List.of(), List.of(), List.of()),
                    List.of("huge"));
            fail("expected budget rejection");
        } catch (SkillActivationException e) {
            assertTrue(e.getMessage().contains("exceeds"));
        }
    }

    @Test
    public void promptRendererPlacesActiveSkillsBelowStablePrefix() {
        String system = renderer.appendToSystemPrompt(
                "Collaboration mode: build\nRuntime rules first.\n",
                List.of(new ActiveSkillSnapshot(
                        "review-pr",
                        "project .agents/skills/review-pr",
                        "Always check tests first.",
                        "abc",
                        null,
                        List.of())));
        assertTrue(system.contains("Collaboration mode: build"));
        assertTrue(system.contains(SkillPromptRenderer.SECTION_TITLE));
        assertTrue(system.contains("name: review-pr"));
        assertTrue(system.contains("Always check tests first."));
        assertTrue(system.indexOf(SkillPromptRenderer.SECTION_TITLE)
                > system.indexOf("Collaboration mode:"));
        assertFalse(system.contains("$review-pr"));
    }

    @Test
    public void contentDigestDriftIsRejected() throws Exception {
        Path root = Files.createTempDirectory("skill-drift");
        Path pkg = root.resolve("drift");
        Files.createDirectories(pkg);
        String skillMd = """
                ---
                name: drift
                description: Drift skill.
                ---
                Original body.
                """;
        Files.writeString(pkg.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "drift", "Drift skill.", "project .agents/skills/drift", true, true,
                "deadbeef", null, null, null, List.of(), pkg);
        try {
            activation.activateExplicit(new SkillCatalog(List.of(entry), List.of(), List.of(), List.of()),
                    List.of("drift"));
            fail("expected content drift rejection");
        } catch (SkillActivationException e) {
            assertTrue(e.getMessage().contains("drifted"));
        }
    }

    @Test
    public void contextBudgetRejectionDoesNotCommitExplicitActiveState() throws Exception {
        Path home = Files.createTempDirectory("skill-admission-home");
        Path workspace = Files.createTempDirectory("skill-admission-workspace").toRealPath();
        Path skill = workspace.resolve(".agents/skills/tiny");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), """
                ---
                name: tiny
                description: Tiny skill.
                ---
                This whole body must not be clipped.
                """, StandardCharsets.UTF_8);
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getContext().setTotalBudgetChars(200);
        properties.getContext().setWorkspaceFloorChars(1);
        properties.getContext().setMemoryFloorChars(1);
        properties.getContext().setRelevantMemoryFloorChars(1);
        properties.getContext().setHistoryFloorChars(1);
        AgentContext context = new AgentContext();
        context.setQuestion("$tiny do work");
        context.setResolvedWorkspace(workspace);
        context.setCollaborationMode(CollaborationMode.BUILD);

        try {
            new SkillRunBootstrap(properties,
                    new cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry(List.of(),
                            new cn.lunalhx.ai.domain.tool.service.ToolSchemaValidator(
                                    new com.fasterxml.jackson.databind.ObjectMapper())))
                    .prepareRun(context, home);
            fail("expected context-budget rejection");
        } catch (SkillActivationException e) {
            assertTrue(e.getMessage().contains("context budget"));
        }
        assertTrue(context.getActiveSkills().isEmpty());
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
