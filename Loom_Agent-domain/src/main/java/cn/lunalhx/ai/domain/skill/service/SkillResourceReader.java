package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.ActiveSkillSnapshot;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogLimits;
import cn.lunalhx.ai.domain.skill.model.SkillResourceEntry;
import cn.lunalhx.ai.domain.skill.model.SkillResourceReadException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Reads indexed Skill resources with path, symlink, and drift guards. */
public final class SkillResourceReader {

    public static final int DEFAULT_CHUNK_BYTES = SkillCatalogLimits.MAX_RESOURCE_CHUNK_BYTES;

    public record ReadResult(String content, boolean text, boolean truncated) {
    }

    public ReadResult read(List<ActiveSkillSnapshot> activeSkills,
                           String skillName,
                           String rawPath,
                           int offset,
                           int limit) {
        Objects.requireNonNull(skillName, "skillName");
        ActiveSkillSnapshot skill = findActive(activeSkills, skillName);
        String normalizedPath = SkillResourcePath.normalize(rawPath);
        SkillResourceEntry entry = findResource(skill, normalizedPath);
        if (skill.packageRoot() == null) {
            throw new SkillResourceReadException("skill package root is unavailable");
        }
        int chunkLimit = clampLimit(limit);
        if (offset < 0) {
            throw new SkillResourceReadException("offset must not be negative");
        }
        Path packageRoot;
        try {
            packageRoot = skill.packageRoot().toRealPath();
        } catch (IOException e) {
            throw new SkillResourceReadException("skill package root is unavailable", e);
        }
        Path candidate = packageRoot.resolve(normalizedPath).normalize();
        if (!candidate.startsWith(packageRoot)) {
            throw new SkillResourceReadException("path escapes skill package root");
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new SkillResourceReadException("symlink resources are not allowed");
        }
        if (!Files.isRegularFile(candidate)) {
            throw new SkillResourceReadException("path is not a regular file");
        }
        Path resolved;
        try {
            resolved = candidate.toRealPath();
        } catch (IOException e) {
            throw new SkillResourceReadException("resource could not be resolved", e);
        }
        if (!resolved.startsWith(packageRoot)) {
            throw new SkillResourceReadException("path escapes skill package root");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new SkillResourceReadException("resource could not be read", e);
        }
        String digest = digest(bytes);
        if (!digest.equals(entry.contentDigest())) {
            throw new SkillResourceReadException("resource content drifted from active snapshot");
        }
        if (offset > bytes.length) {
            throw new SkillResourceReadException("offset exceeds resource size");
        }
        int end = Math.min(bytes.length, offset + chunkLimit);
        byte[] chunk = java.util.Arrays.copyOfRange(bytes, offset, end);
        boolean truncated = end < bytes.length;
        if (isText(bytes)) {
            String text = new String(chunk, StandardCharsets.UTF_8);
            return new ReadResult(formatText(skillName, normalizedPath, offset, end, bytes.length, text),
                    true, truncated);
        }
        String encoded = Base64.getEncoder().encodeToString(chunk);
        return new ReadResult(formatBinary(skillName, normalizedPath, offset, end, bytes.length, encoded),
                false, truncated);
    }

    private static ActiveSkillSnapshot findActive(List<ActiveSkillSnapshot> activeSkills, String skillName) {
        if (activeSkills == null || activeSkills.isEmpty()) {
            throw new SkillResourceReadException("no active skill for this Run");
        }
        for (ActiveSkillSnapshot skill : activeSkills) {
            if (skill.name().equals(skillName)) {
                return skill;
            }
        }
        throw new SkillResourceReadException("skill is not active: " + skillName);
    }

    private static SkillResourceEntry findResource(ActiveSkillSnapshot skill, String normalizedPath) {
        for (SkillResourceEntry entry : skill.resources()) {
            if (entry.normalizedPath().equals(normalizedPath)) {
                return entry;
            }
        }
        throw new SkillResourceReadException("resource is not indexed: " + normalizedPath);
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_CHUNK_BYTES;
        }
        return Math.min(limit, DEFAULT_CHUNK_BYTES);
    }

    private static boolean isText(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return false;
            }
        }
        return true;
    }

    private static String formatText(String skill, String path, int offset, int end, int total, String text) {
        return "# skill: " + skill + "\n"
                + "# path: " + path + "\n"
                + "# encoding: utf-8\n"
                + "# range: " + offset + "-" + (end - 1) + " of " + total + "\n"
                + text;
    }

    private static String formatBinary(String skill, String path, int offset, int end, int total, String encoded) {
        return "# skill: " + skill + "\n"
                + "# path: " + path + "\n"
                + "# encoding: base64\n"
                + "# range: " + offset + "-" + (end - 1) + " of " + total + "\n"
                + encoded;
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
