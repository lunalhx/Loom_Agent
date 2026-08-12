package cn.lunalhx.ai.domain.skill.model;

import java.util.List;

public record SkillInvalidEntry(
        String pathLabel,
        List<String> reasons) {
}
