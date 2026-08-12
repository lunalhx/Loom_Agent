package cn.lunalhx.ai.domain.skill.model;

import java.util.List;

public record SkillCatalogEntry(
        String name,
        String description,
        String sourceLabel,
        boolean userInvocable,
        boolean modelInvocable,
        String contentDigest,
        String license,
        String compatibility,
        String metadata,
        List<String> compatibilityDiagnostics) {
}
