package cn.lunalhx.ai.domain.skill.model;

import java.util.Objects;

/** Immutable indexed resource identity bound to an Active Skill Snapshot. */
public record SkillResourceEntry(String normalizedPath, String contentDigest) {

    public SkillResourceEntry {
        Objects.requireNonNull(normalizedPath, "normalizedPath");
        Objects.requireNonNull(contentDigest, "contentDigest");
    }
}
