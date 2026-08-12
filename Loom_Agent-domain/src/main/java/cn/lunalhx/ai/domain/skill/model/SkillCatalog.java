package cn.lunalhx.ai.domain.skill.model;

import java.util.List;

public record SkillCatalog(
        List<SkillCatalogEntry> effective,
        List<SkillShadowedEntry> shadowed,
        List<SkillInvalidEntry> invalid,
        List<String> catalogDiagnostics) {
}
