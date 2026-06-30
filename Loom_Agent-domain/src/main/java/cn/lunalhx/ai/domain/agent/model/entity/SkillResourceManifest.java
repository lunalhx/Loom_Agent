package cn.lunalhx.ai.domain.agent.model.entity;

import java.util.List;

public record SkillResourceManifest(
        List<ResourceEntry> entries,
        long totalBytes
) {

    public record ResourceEntry(
            String path,
            long size,
            String sha256
    ) {}

    public static final SkillResourceManifest EMPTY = new SkillResourceManifest(List.of(), 0L);
}
