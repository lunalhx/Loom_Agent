package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.agent.model.entity.AgentContext;
import cn.lunalhx.ai.domain.agent.model.valobj.CollaborationMode;
import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.tool.adapter.port.ToolRegistry;
import cn.lunalhx.ai.domain.tool.model.ExecutionProfile;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;

import java.util.List;
import java.util.Objects;

/** Projects read_skill_resource into the model-visible catalog when indexed resources exist. */
public final class SkillToolCatalogProjector {

    public static final String READ_SKILL_RESOURCE = "read_skill_resource";

    private SkillToolCatalogProjector() {
    }

    public static List<ToolSpec> project(AgentContext context, ToolRegistry registry) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(registry, "registry");
        CollaborationMode mode = context.getCollaborationMode() == null
                ? CollaborationMode.BUILD : context.getCollaborationMode();
        ExecutionProfile profile = context.getExecutionProfile() == null
                ? ExecutionProfile.forRun(mode, context.getParentRunId() != null)
                : context.getExecutionProfile();
        List<ToolSpec> specs = registry.effectiveSpecs(mode, context.getAllowedTools(), profile);
        if (!hasIndexedResources(context.getActiveSkills())) {
            return specs.stream()
                    .filter(spec -> !READ_SKILL_RESOURCE.equals(spec.getName()))
                    .toList();
        }
        return specs;
    }

    public static boolean hasIndexedResources(List<ActiveSkillSnapshot> activeSkills) {
        if (activeSkills == null || activeSkills.isEmpty()) {
            return false;
        }
        for (ActiveSkillSnapshot skill : activeSkills) {
            if (skill.resources() != null && !skill.resources().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
