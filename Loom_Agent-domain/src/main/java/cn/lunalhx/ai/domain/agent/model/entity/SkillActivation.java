package cn.lunalhx.ai.domain.agent.model.entity;

import java.time.Instant;

public record SkillActivation(
        String name,
        SkillSource source,
        String manifestSha256,
        String snapshotArtifactId,
        Instant activatedAt,
        int resourceCount
) {}
