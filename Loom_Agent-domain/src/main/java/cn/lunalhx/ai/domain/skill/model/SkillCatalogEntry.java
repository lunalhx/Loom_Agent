package cn.lunalhx.ai.domain.skill.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.file.Path;
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
        List<String> compatibilityDiagnostics,
        @JsonIgnore Path packageRoot) {
}
