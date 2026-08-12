package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;

import java.util.List;

/** Renders Active Skill Snapshots as a lower-priority system-prompt section. */
public final class SkillPromptRenderer {
    public static final String SECTION_TITLE = "Active skills:";

    public String render(List<ActiveSkillSnapshot> activeSkills) {
        if (activeSkills == null || activeSkills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(SECTION_TITLE).append('\n');
        sb.append("These instructions are lower priority than Runtime rules, protocol, tools, and security.\n");
        for (ActiveSkillSnapshot skill : activeSkills) {
            sb.append("\n---\n");
            sb.append("name: ").append(skill.name()).append('\n');
            sb.append("source: ").append(skill.sourceLabel()).append('\n');
            sb.append("content_digest: ").append(skill.contentDigest()).append('\n');
            sb.append("instructions:\n");
            sb.append(skill.instructionBody());
            if (!skill.instructionBody().endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public String appendToSystemPrompt(String stablePrefix, List<ActiveSkillSnapshot> activeSkills) {
        String skills = render(activeSkills);
        if (skills.isEmpty()) {
            return stablePrefix == null ? "" : stablePrefix;
        }
        if (stablePrefix == null || stablePrefix.isBlank()) {
            return skills;
        }
        String prefix = stablePrefix.endsWith("\n") ? stablePrefix : stablePrefix + "\n";
        return prefix + "\n" + skills;
    }
}
