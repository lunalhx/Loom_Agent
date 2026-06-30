package cn.lunalhx.ai.domain.agent.model.entity;

import java.util.List;

public record SkillCatalog(
        List<SkillDescriptor> skills,
        List<String> diagnostics,
        boolean catalogTruncated
) {

    public static final SkillCatalog EMPTY = new SkillCatalog(List.of(), List.of(), false);

    public String renderCatalogText(int maxChars) {
        StringBuilder sb = new StringBuilder();
        int budget = maxChars;
        for (SkillDescriptor skill : skills) {
            String line = "- " + skill.name() + ": " + skill.description()
                    + " [source=" + skill.source().name().toLowerCase()
                    + ", compat=" + skill.compatibility() + "]\n";
            if (budget - line.length() < 0) {
                break;
            }
            sb.append(line);
            budget -= line.length();
        }
        return sb.toString();
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }
}
