package cn.lunalhx.ai.domain.agent.model.entity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record SkillDescriptor(
        String name,
        String description,
        String license,
        String compatibility,
        Map<String, Object> metadata,
        List<String> allowedTools,
        SkillSource source,
        Path rootPath,
        String manifestSha256,
        int resourceCount
) {}
