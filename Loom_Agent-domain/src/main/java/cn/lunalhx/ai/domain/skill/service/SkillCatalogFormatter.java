package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry;
import cn.lunalhx.ai.domain.skill.model.SkillInvalidEntry;
import cn.lunalhx.ai.domain.skill.model.SkillShadowedEntry;

/** Deterministic text rendering for the `/skills` control command. */
public final class SkillCatalogFormatter {
    public String format(SkillCatalog catalog) {
        StringBuilder view = new StringBuilder("skills:\n");
        if (catalog.effective().isEmpty()) {
            view.append("  (none)\n");
        } else {
            for (SkillCatalogEntry entry : catalog.effective()) {
                appendEffective(view, entry);
            }
        }
        view.append("shadowed:\n");
        if (catalog.shadowed().isEmpty()) {
            view.append("  (none)\n");
        } else {
            for (SkillShadowedEntry entry : catalog.shadowed()) {
                view.append("  - name: ").append(entry.name()).append('\n')
                        .append("    source: ").append(entry.sourceLabel()).append('\n')
                        .append("    shadowed_by: ").append(entry.shadowedBy()).append('\n');
            }
        }
        view.append("invalid:\n");
        if (catalog.invalid().isEmpty()) {
            view.append("  (none)\n");
        } else {
            for (SkillInvalidEntry entry : catalog.invalid()) {
                view.append("  - path: ").append(entry.pathLabel()).append('\n');
                for (String reason : entry.reasons()) {
                    view.append("    reason: ").append(reason).append('\n');
                }
            }
        }
        view.append("diagnostics:\n");
        if (catalog.catalogDiagnostics().isEmpty()) {
            view.append("  (none)");
        } else {
            for (String diagnostic : catalog.catalogDiagnostics()) {
                view.append("  - ").append(diagnostic).append('\n');
            }
            if (view.charAt(view.length() - 1) == '\n') {
                view.setLength(view.length() - 1);
            }
        }
        return view.toString();
    }

    private static void appendEffective(StringBuilder view, SkillCatalogEntry entry) {
        view.append("  - name: ").append(entry.name()).append('\n')
                .append("    source: ").append(entry.sourceLabel()).append('\n')
                .append("    description: ").append(entry.description()).append('\n')
                .append("    invocation: ").append(invocationLabel(entry)).append('\n')
                .append("    content_digest: ").append(entry.contentDigest()).append('\n');
        appendOptional(view, "license", entry.license());
        appendOptional(view, "compatibility", entry.compatibility());
        appendOptional(view, "metadata", entry.metadata());
        if (!entry.compatibilityDiagnostics().isEmpty()) {
            for (String diagnostic : entry.compatibilityDiagnostics()) {
                view.append("    compatibility: ").append(diagnostic).append('\n');
            }
        }
    }

    private static String invocationLabel(SkillCatalogEntry entry) {
        if (entry.userInvocable() && entry.modelInvocable()) {
            return "user, model";
        }
        if (entry.userInvocable()) {
            return "user";
        }
        if (entry.modelInvocable()) {
            return "model";
        }
        return "none";
    }

    private static void appendOptional(StringBuilder view, String key, String value) {
        if (value != null && !value.isBlank()) {
            view.append("    ").append(key).append(": ").append(value).append('\n');
        }
    }
}
