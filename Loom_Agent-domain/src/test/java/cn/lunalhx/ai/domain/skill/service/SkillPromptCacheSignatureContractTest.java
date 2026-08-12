package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillResourceEntry;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Ticket 07 seam: resumed active Skill rendering must keep a stable prompt
 * section so prompt-cache signatures remain aligned with the frozen body.
 */
public class SkillPromptCacheSignatureContractTest {

    @Test
    public void activeSkillSectionIsDeterministicForFrozenSnapshot() {
        SkillPromptRenderer renderer = new SkillPromptRenderer();
        List<ActiveSkillSnapshot> active = List.of(new ActiveSkillSnapshot(
                "review-pr",
                "project .agents/skills/review-pr",
                "FROZEN_BODY\n",
                "digest",
                null,
                List.of(new SkillResourceEntry("references/a.md", "r1"))));

        String first = renderer.render(active);
        String second = renderer.render(List.copyOf(active));
        assertEquals(first, second);
        assertEquals(org.apache.commons.codec.digest.DigestUtils.sha256Hex(first),
                org.apache.commons.codec.digest.DigestUtils.sha256Hex(second));
    }
}
