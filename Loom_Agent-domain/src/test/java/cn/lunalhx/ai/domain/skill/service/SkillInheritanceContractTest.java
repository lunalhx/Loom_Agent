package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
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

/**
 * Delegate inheritance must reuse the frozen catalog/active snapshots and never
 * rediscover from disk.
 */
public class SkillInheritanceContractTest {

    @Test
    public void prepareRunKeepsInheritedCatalogAndActiveSkillsWithoutRediscovery()
            throws Exception {
        Path home = Files.createTempDirectory("skill-inherit-home");
        Path workspace = Files.createTempDirectory("skill-inherit-workspace").toRealPath();
        Path skillDir = workspace.resolve(".agents/skills/parent-method");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: parent-method
                description: Parent method.
                ---
                FROZEN_PARENT_BODY
                """, StandardCharsets.UTF_8);

        AgentContext root = new AgentContext();
        root.setQuestion("$parent-method do work");
        root.setResolvedWorkspace(workspace);
        root.setCollaborationMode(CollaborationMode.BUILD);
        new SkillRunBootstrap().prepareRun(root, home);
        SkillCatalog frozen = root.getSkillCatalogSnapshot();
        List<ActiveSkillSnapshot> active = root.getActiveSkills();
        assertEquals(1, active.size());
        assertEquals("FROZEN_PARENT_BODY", active.getFirst().instructionBody().trim());

        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: parent-method
                description: Parent method.
                ---
                DRIFTED_SHOULD_NOT_LOAD
                """, StandardCharsets.UTF_8);
        Path ghost = workspace.resolve(".agents/skills/ghost-skill");
        Files.createDirectories(ghost);
        Files.writeString(ghost.resolve("SKILL.md"), """
                ---
                name: ghost-skill
                description: Ghost.
                ---
                GHOST
                """, StandardCharsets.UTF_8);

        AgentContext child = new AgentContext();
        child.setParentRunId("parent-run");
        child.setAgentDepth(1);
        child.setResolvedWorkspace(workspace);
        child.setCollaborationMode(CollaborationMode.BUILD);
        child.setSkillCatalogSnapshot(frozen);
        child.setActiveSkills(active);

        new SkillRunBootstrap().prepareRun(child, home);

        assertSame(frozen, child.getSkillCatalogSnapshot());
        assertEquals(1, child.getActiveSkills().size());
        assertEquals("FROZEN_PARENT_BODY", child.getActiveSkills().getFirst().instructionBody().trim());
        assertTrue(child.getSkillCatalogSnapshot().effective().stream()
                .map(SkillCatalogEntry::name).anyMatch("parent-method"::equals));
        assertFalse(child.getSkillCatalogSnapshot().effective().stream()
                .map(SkillCatalogEntry::name).anyMatch("ghost-skill"::equals));
        assertFalse(child.getActiveSkills().getFirst().instructionBody().contains("DRIFTED"));
    }
}
