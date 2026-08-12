package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.SkillActivationException;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogLimits;
import cn.lunalhx.ai.domain.skill.model.SkillResourceEntry;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Indexes bounded supporting resources at Skill Activation time. */
public final class SkillResourceIndexer {

    private static final Set<String> ALLOWED_ROOTS = Set.of("references", "assets", "scripts");

    public List<SkillResourceEntry> index(Path packageRoot) {
        if (packageRoot == null) {
            return List.of();
        }
        Path root;
        try {
            root = packageRoot.toRealPath();
        } catch (IOException e) {
            throw new SkillActivationException("skill package root is unavailable", e);
        }
        List<SkillResourceEntry> resources = new ArrayList<>();
        for (String dirName : ALLOWED_ROOTS) {
            Path dir = root.resolve(dirName);
            if (Files.isSymbolicLink(dir)) {
                throw new SkillActivationException(
                        "skill resource directory symlink is not allowed: " + dirName);
            }
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                            if (Files.isSymbolicLink(file)) {
                                throw new SkillActivationException(
                                        "skill resource symlink is not allowed: "
                                                + normalizeRelative(root, file));
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        if (resources.size() >= SkillCatalogLimits.MAX_RESOURCES_PER_SKILL) {
                            throw new SkillActivationException(
                                    "skill resource count exceeds "
                                            + SkillCatalogLimits.MAX_RESOURCES_PER_SKILL);
                        }
                        byte[] bytes = Files.readAllBytes(file);
                        if (bytes.length > SkillCatalogLimits.MAX_RESOURCE_BYTES) {
                            throw new SkillActivationException(
                                    "skill resource exceeds "
                                            + SkillCatalogLimits.MAX_RESOURCE_BYTES + " bytes: "
                                            + normalizeRelative(root, file));
                        }
                        Path realFile = file.toRealPath();
                        if (!realFile.startsWith(root)) {
                            throw new SkillActivationException(
                                    "skill resource escapes package root: "
                                            + normalizeRelative(root, file));
                        }
                        resources.add(new SkillResourceEntry(
                                normalizeRelative(root, file), digest(bytes)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        if (Files.isSymbolicLink(dir) && !dir.equals(root.resolve(dirName))) {
                            throw new SkillActivationException(
                                    "skill resource symlink directory is not allowed");
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new SkillActivationException("skill resources could not be indexed", e);
            }
        }
        return List.copyOf(resources);
    }

    static String normalizeRelative(Path packageRoot, Path file) {
        return packageRoot.relativize(file.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
