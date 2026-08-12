package cn.lunalhx.ai.domain.skill.model;

import java.util.List;

public record SkillShadowedEntry(
        String name,
        String sourceLabel,
        String shadowedBy) {
}
