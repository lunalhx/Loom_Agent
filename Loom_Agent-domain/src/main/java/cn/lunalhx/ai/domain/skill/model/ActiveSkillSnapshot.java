package cn.lunalhx.ai.domain.skill.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable instruction snapshot for one Skill activated in the current Run.
 * Bodies are admitted whole or activation fails.
 */
public record ActiveSkillSnapshot(
        String name,
        String sourceLabel,
        String instructionBody,
        String contentDigest,
        Path packageRoot,
        List<SkillResourceEntry> resources) {

    public ActiveSkillSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        Objects.requireNonNull(instructionBody, "instructionBody");
        Objects.requireNonNull(contentDigest, "contentDigest");
        resources = resources == null ? List.of() : List.copyOf(resources);
    }
}
