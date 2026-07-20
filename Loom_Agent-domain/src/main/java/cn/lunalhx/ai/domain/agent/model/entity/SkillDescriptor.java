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
        boolean allowedToolsDeclared,
        SkillSource source,
        Path rootPath,
        String manifestSha256,
        int resourceCount
) {
    public SkillDescriptor(String name, String description, String license, String compatibility,
                           Map<String, Object> metadata, List<String> allowedTools,
                           SkillSource source, Path rootPath, String manifestSha256, int resourceCount) {
        this(name, description, license, compatibility, metadata, allowedTools, false,
                source, rootPath, manifestSha256, resourceCount);
    }
}
