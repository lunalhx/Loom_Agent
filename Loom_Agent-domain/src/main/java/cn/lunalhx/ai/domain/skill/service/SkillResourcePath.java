package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.SkillResourceReadException;

import java.nio.file.Path;
import java.util.Set;

/** Canonical relative-path rules for Skill Resource Observation. */
final class SkillResourcePath {

    private static final Set<String> ALLOWED_ROOTS = Set.of("references/", "assets/", "scripts/");

    private SkillResourcePath() {
    }

    static String normalize(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new SkillResourceReadException("path must not be empty");
        }
        String trimmed = rawPath.trim().replace('\\', '/');
        if (trimmed.startsWith("/")) {
            throw new SkillResourceReadException("absolute paths are not allowed");
        }
        Path normalized = Path.of(trimmed).normalize();
        String result = normalized.toString().replace('\\', '/');
        if (result.isEmpty() || result.startsWith("..") || result.contains("/../")) {
            throw new SkillResourceReadException("path traversal is not allowed");
        }
        if (ALLOWED_ROOTS.stream().noneMatch(result::startsWith)) {
            throw new SkillResourceReadException(
                    "path must be under references/, assets/, or scripts/");
        }
        return result;
    }
}
