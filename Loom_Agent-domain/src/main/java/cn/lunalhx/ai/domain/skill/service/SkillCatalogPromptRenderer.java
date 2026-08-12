package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry;

/** Metadata-only Skill catalog rendering for model discovery during a Run. */
public final class SkillCatalogPromptRenderer {
    public static final String SECTION_TITLE = "Available skills:";

    public String render(SkillCatalog catalog) {
        if (catalog == null || catalog.effective().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(SECTION_TITLE).append('\n');
        sb.append("Metadata only. Activate one with <skill_activation>{\"name\":\"skill-name\"}</skill_activation>.\n");
        boolean any = false;
        for (SkillCatalogEntry entry : catalog.effective()) {
            if (!entry.modelInvocable()) {
                continue;
            }
            any = true;
            sb.append("\n---\n");
            sb.append("name: ").append(entry.name()).append('\n');
            sb.append("source: ").append(entry.sourceLabel()).append('\n');
            sb.append("description: ").append(entry.description()).append('\n');
            sb.append("content_digest: ").append(entry.contentDigest()).append('\n');
        }
        return any ? sb.toString() : "";
    }

    public String appendToSystemPrompt(String stablePrefix, SkillCatalog catalog) {
        String catalogSection = render(catalog);
        if (catalogSection.isEmpty()) {
            return stablePrefix == null ? "" : stablePrefix;
        }
        if (stablePrefix == null || stablePrefix.isBlank()) {
            return catalogSection;
        }
        String prefix = stablePrefix.endsWith("\n") ? stablePrefix : stablePrefix + "\n";
        return prefix + "\n" + catalogSection;
    }
}
