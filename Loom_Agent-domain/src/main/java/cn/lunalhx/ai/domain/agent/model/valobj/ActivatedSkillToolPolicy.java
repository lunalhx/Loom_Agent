package cn.lunalhx.ai.domain.agent.model.valobj;

import cn.lunalhx.ai.domain.agent.model.entity.SkillActivation;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record ActivatedSkillToolPolicy(boolean restricted, Set<String> allowedTools) {

    public static ActivatedSkillToolPolicy from(List<SkillActivation> activations) {
        if (activations == null || activations.isEmpty()
                || activations.stream().anyMatch(activation -> !activation.allowedToolsDeclared())) {
            return new ActivatedSkillToolPolicy(false, Set.of());
        }
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        activations.forEach(activation -> allowed.addAll(activation.allowedTools()));
        return new ActivatedSkillToolPolicy(true, Set.copyOf(allowed));
    }

    public boolean allows(String toolName) {
        return !restricted || allowedTools.contains(toolName);
    }

    public List<ToolSpec> filter(List<ToolSpec> specs) {
        if (!restricted || specs == null) {
            return specs == null ? List.of() : List.copyOf(specs);
        }
        return specs.stream().filter(spec -> allows(spec.getName())).toList();
    }
}
