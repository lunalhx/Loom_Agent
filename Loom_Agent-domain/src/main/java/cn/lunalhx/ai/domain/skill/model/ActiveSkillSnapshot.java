package cn.lunalhx.ai.domain.skill.model;

import java.util.Objects;

/**
 * Immutable instruction snapshot for one Skill activated in the current Run.
 * Bodies are admitted whole or activation fails.
 */
public record ActiveSkillSnapshot(
        String name,
        String sourceLabel,
        String instructionBody,
        String contentDigest) {

    public ActiveSkillSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        Objects.requireNonNull(instructionBody, "instructionBody");
        Objects.requireNonNull(contentDigest, "contentDigest");
    }
}
