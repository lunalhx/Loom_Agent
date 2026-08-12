package cn.lunalhx.ai.domain.skill.service;

import cn.lunalhx.ai.domain.skill.model.SkillCatalog;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogEntry;
import cn.lunalhx.ai.domain.skill.model.SkillCatalogLimits;
import cn.lunalhx.ai.domain.skill.model.SkillInvalidEntry;
import cn.lunalhx.ai.domain.skill.model.SkillShadowedEntry;
import cn.lunalhx.ai.domain.skill.model.SkillSourceKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Discovers metadata-only Skill packages from configured sources without executing content. */
public final class SkillDiscoveryService {
    private final SkillFrontmatterParser parser = new SkillFrontmatterParser();

    public SkillCatalog discover(Path workspace, Path userHome) {
        Path canonicalWorkspace = workspace.toAbsolutePath().normalize();
        Path canonicalHome = userHome.toAbsolutePath().normalize();
        List<DiscoveredPackage> packages = new ArrayList<>();
        List<String> catalogDiagnostics = new ArrayList<>();
        for (SkillSourceKind.SkillSourceRoot source : SkillSourceKind.configuredRoots(canonicalWorkspace, canonicalHome)) {
            packages.addAll(discoverSource(source, catalogDiagnostics));
        }
        return resolveCatalog(packages, catalogDiagnostics);
    }

    private List<DiscoveredPackage> discoverSource(SkillSourceKind.SkillSourceRoot source,
                                                     List<String> catalogDiagnostics) {
        Path root = source.root();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            return List.of();
        }
        List<Path> directories;
        try (Stream<Path> entries = Files.list(root)) {
            directories = entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            catalogDiagnostics.add("cannot read " + source.kind().scopeLabel() + "/skills: " + e.getMessage());
            return List.of();
        }
        List<DiscoveredPackage> packages = new ArrayList<>();
        for (Path packageDir : directories) {
            if (Files.isSymbolicLink(packageDir)) {
                packages.add(DiscoveredPackage.invalid(source, packageDir,
                        List.of("skill package directory must not be a symlink")));
                continue;
            }
            Path skillMd = packageDir.resolve("SKILL.md");
            if (!Files.isRegularFile(skillMd)) {
                continue;
            }
            if (Files.isSymbolicLink(skillMd)) {
                packages.add(DiscoveredPackage.invalid(source, packageDir,
                        List.of("SKILL.md must not be a symlink")));
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(skillMd);
                String directoryName = packageDir.getFileName().toString();
                SkillFrontmatterParser.ParsedFrontmatter frontmatter = parser.parse(directoryName, bytes);
                if (!frontmatter.valid()) {
                    packages.add(DiscoveredPackage.invalid(source, packageDir, frontmatter.validationErrors()));
                    continue;
                }
                packages.add(DiscoveredPackage.valid(source, packageDir, frontmatter, digestOrThrow(bytes)));
            } catch (IOException e) {
                packages.add(DiscoveredPackage.invalid(source, packageDir,
                        List.of("cannot read SKILL.md: " + e.getMessage())));
            }
        }
        return packages;
    }

    private SkillCatalog resolveCatalog(List<DiscoveredPackage> packages, List<String> catalogDiagnostics) {
        Map<String, Winner> winners = new LinkedHashMap<>();
        List<SkillShadowedEntry> shadowed = new ArrayList<>();
        List<SkillInvalidEntry> invalid = new ArrayList<>();
        packages.sort(Comparator.comparing(DiscoveredPackage::source)
                .thenComparing(pkg -> pkg.packageDir().getFileName().toString()));
        for (DiscoveredPackage pkg : packages) {
            if (!pkg.valid()) {
                invalid.add(new SkillInvalidEntry(pkg.source().labelFor(pkg.packageDir()), pkg.reasons()));
                continue;
            }
            Winner existing = winners.get(pkg.frontmatter().name());
            if (existing == null) {
                if (winners.size() >= SkillCatalogLimits.MAX_PACKAGES) {
                    catalogDiagnostics.add("catalog truncated after " + SkillCatalogLimits.MAX_PACKAGES + " effective skills");
                    break;
                }
                winners.put(pkg.frontmatter().name(), new Winner(pkg));
                continue;
            }
            shadowed.add(new SkillShadowedEntry(
                    pkg.frontmatter().name(),
                    pkg.source().labelFor(pkg.packageDir()),
                    existing.pkg.source().labelFor(existing.pkg.packageDir())));
        }
        List<SkillCatalogEntry> effective = winners.values().stream()
                .map(Winner::toEntry)
                .sorted(Comparator.comparing(SkillCatalogEntry::name))
                .toList();
        shadowed.sort(Comparator.comparing(SkillShadowedEntry::name)
                .thenComparing(SkillShadowedEntry::sourceLabel));
        invalid.sort(Comparator.comparing(SkillInvalidEntry::pathLabel));
        return new SkillCatalog(effective, List.copyOf(shadowed), List.copyOf(invalid),
                List.copyOf(catalogDiagnostics));
    }

    private static String digestOrThrow(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Winner(DiscoveredPackage pkg) {
        SkillCatalogEntry toEntry() {
            SkillFrontmatterParser.ParsedFrontmatter frontmatter = pkg.frontmatter();
            return new SkillCatalogEntry(
                    frontmatter.name(),
                    frontmatter.description(),
                    pkg.source().labelFor(pkg.packageDir()),
                    frontmatter.userInvocable(),
                    frontmatter.modelInvocable(),
                    pkg.contentDigest(),
                    frontmatter.license(),
                    frontmatter.compatibility(),
                    frontmatter.metadata(),
                    frontmatter.compatibilityDiagnostics());
        }
    }

    private record DiscoveredPackage(
            SkillSourceKind.SkillSourceRoot source,
            Path packageDir,
            boolean valid,
            SkillFrontmatterParser.ParsedFrontmatter frontmatter,
            String contentDigest,
            List<String> reasons) {
        static DiscoveredPackage valid(SkillSourceKind.SkillSourceRoot source, Path packageDir,
                                       SkillFrontmatterParser.ParsedFrontmatter frontmatter,
                                       String contentDigest) {
            return new DiscoveredPackage(source, packageDir, true, frontmatter, contentDigest, List.of());
        }

        static DiscoveredPackage invalid(SkillSourceKind.SkillSourceRoot source, Path packageDir,
                                         List<String> reasons) {
            return new DiscoveredPackage(source, packageDir, false, null, null, List.copyOf(reasons));
        }
    }
}
