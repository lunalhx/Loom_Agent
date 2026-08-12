package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry;
import cn.lunalhx.ai.domain.skill.model.SkillSourceKind;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebinds runtime-only Skill package roots from durable source labels after
 * checkpoint restore, without rediscovering or replacing frozen catalog content.
 */
public final class SkillPackageRootBinder {

    public SkillCatalog rebindCatalog(SkillCatalog catalog, Path workspace, Path userHome) {
        if (catalog == null) {
            return null;
        }
        List<SkillCatalogEntry> rebound = new ArrayList<>();
        for (SkillCatalogEntry entry : catalog.effective()) {
            rebound.add(new SkillCatalogEntry(
                    entry.name(),
                    entry.description(),
                    entry.sourceLabel(),
                    entry.userInvocable(),
                    entry.modelInvocable(),
                    entry.contentDigest(),
                    entry.license(),
                    entry.compatibility(),
                    entry.metadata(),
                    entry.compatibilityDiagnostics(),
                    resolve(entry.sourceLabel(), workspace, userHome)));
        }
        return new SkillCatalog(rebound, catalog.shadowed(), catalog.invalid(), catalog.catalogDiagnostics());
    }

    public List<ActiveSkillSnapshot> rebindActive(List<ActiveSkillSnapshot> active,
                                                  Path workspace,
                                                  Path userHome) {
        if (active == null || active.isEmpty()) {
            return active == null ? List.of() : active;
        }
        List<ActiveSkillSnapshot> rebound = new ArrayList<>();
        for (ActiveSkillSnapshot skill : active) {
            rebound.add(skill.withPackageRoot(resolve(skill.sourceLabel(), workspace, userHome)));
        }
        return List.copyOf(rebound);
    }

    Path resolve(String sourceLabel, Path workspace, Path userHome) {
        if (sourceLabel == null || sourceLabel.isBlank()) {
            return null;
        }
        Path home = userHome == null ? Path.of(System.getProperty("user.home")) : userHome;
        Path ws = workspace;
        for (SkillSourceKind.SkillSourceRoot root : SkillSourceKind.configuredRoots(ws, home)) {
            String prefix = root.kind().scopeLabel() + "/skills/";
            if (sourceLabel.startsWith(prefix)) {
                String packageName = sourceLabel.substring(prefix.length());
                if (packageName.isBlank() || packageName.contains("/") || packageName.contains("\\")) {
                    return null;
                }
                return root.root().resolve(packageName);
            }
        }
        return null;
    }
}
