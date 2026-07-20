package cn.lunalhx.ai.domain.agent.model.entity;

import java.time.Instant;
import java.util.List;

public record SkillActivation(
        String name,
        SkillSource source,
        String manifestSha256,
        String snapshotArtifactId,
        Instant activatedAt,
        int resourceCount,
        List<String> allowedTools,
        boolean allowedToolsDeclared
) {
    public SkillActivation(String name, SkillSource source, String manifestSha256,
                           String snapshotArtifactId, Instant activatedAt, int resourceCount) {
        this(name, source, manifestSha256, snapshotArtifactId, activatedAt, resourceCount,
                List.of(), false);
    }

    public SkillActivation {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }
}
