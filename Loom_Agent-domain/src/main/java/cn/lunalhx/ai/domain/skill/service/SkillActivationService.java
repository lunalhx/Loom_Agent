package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillActivationException;
import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogLimits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Shared Skill Activation path: resolve Effective Skill Descriptors, freeze full bodies,
 * and fail closed on unknown, non-user-invocable, drifted, or over-budget Skills.
 */
public final class SkillActivationService {
    private final SkillFrontmatterParser frontmatterParser = new SkillFrontmatterParser();

    public List<ActiveSkillSnapshot> activateExplicit(SkillCatalog catalog, List<String> namesInOrder) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(namesInOrder, "namesInOrder");
        Map<String, SkillCatalogEntry> byName = new LinkedHashMap<>();
        for (SkillCatalogEntry entry : catalog.effective()) {
            byName.put(entry.name(), entry);
        }
        List<ActiveSkillSnapshot> activated = new ArrayList<>();
        int totalChars = 0;
        for (String name : namesInOrder) {
            SkillCatalogEntry entry = byName.get(name);
            if (entry == null) {
                throw new SkillActivationException("unknown skill: $" + name);
            }
            if (!entry.userInvocable()) {
                throw new SkillActivationException(
                        "skill $" + name + " is not user-invocable");
            }
            ActiveSkillSnapshot snapshot = loadSnapshot(entry);
            if (snapshot.instructionBody().length() > SkillCatalogLimits.MAX_ACTIVE_BODY_CHARS) {
                throw new SkillActivationException(
                        "skill $" + name + " instruction body exceeds "
                                + SkillCatalogLimits.MAX_ACTIVE_BODY_CHARS + " characters");
            }
            totalChars += snapshot.instructionBody().length();
            if (totalChars > SkillCatalogLimits.MAX_ACTIVE_TOTAL_CHARS) {
                throw new SkillActivationException(
                        "active skill instructions exceed "
                                + SkillCatalogLimits.MAX_ACTIVE_TOTAL_CHARS + " characters");
            }
            activated.add(snapshot);
        }
        return List.copyOf(activated);
    }

    private ActiveSkillSnapshot loadSnapshot(SkillCatalogEntry entry) {
        Path skillMd = entry.packageRoot() == null ? null : entry.packageRoot().resolve("SKILL.md");
        if (skillMd == null || !Files.isRegularFile(skillMd) || Files.isSymbolicLink(skillMd)) {
            throw new SkillActivationException(
                    "skill $" + entry.name() + " content is unavailable");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(skillMd);
        } catch (IOException e) {
            throw new SkillActivationException(
                    "skill $" + entry.name() + " content could not be read", e);
        }
        String digest = digestOrThrow(bytes);
        if (!digest.equals(entry.contentDigest())) {
            throw new SkillActivationException(
                    "skill $" + entry.name() + " content drifted from catalog snapshot");
        }
        String body = frontmatterParser.extractBody(bytes);
        if (body.isBlank()) {
            throw new SkillActivationException(
                    "skill $" + entry.name() + " instruction body is empty");
        }
        return new ActiveSkillSnapshot(entry.name(), entry.sourceLabel(), body, digest);
    }

    private static String digestOrThrow(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
