package cn.lunalhx.ai.infrastructure.skill;

import cn.lunalhx.ai.domain.agent.adapter.port.SkillRepository;
import cn.lunalhx.ai.domain.agent.model.entity.SkillCatalog;
import cn.lunalhx.ai.domain.agent.model.entity.SkillDescriptor;
import cn.lunalhx.ai.domain.agent.model.entity.SkillResourceManifest;
import cn.lunalhx.ai.domain.agent.model.entity.SkillSource;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class FileSystemSkillRepository implements SkillRepository {

    private static final Logger log = LoggerFactory.getLogger(FileSystemSkillRepository.class);

    private static final String SKILL_FILE = "SKILL.md";
    private static final int MAX_FILE_BYTES = 100_000;
    private static final int MAX_RESOURCE_BYTES = 10_485_760;
    private static final int MAX_SNAPSHOT_BYTES = 52_428_800;
    private static final int MAX_RESOURCE_FILES = 256;
    private static final int CATALOG_MAX_CHARS = 8000;

    private final Path userSkillsDir;
    private final String projectSkillsDir;

    public FileSystemSkillRepository(Path userSkillsDir, String projectSkillsDir) {
        this.userSkillsDir = userSkillsDir;
        this.projectSkillsDir = projectSkillsDir;
    }

    @Override
    public SkillCatalog discover(Path workspaceRoot) {
        List<SkillDescriptor> skills = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        Map<String, SkillDescriptor> userSkills = new LinkedHashMap<>();
        Path userDir = resolveUserDir();
        if (userDir != null && Files.isDirectory(userDir)) {
            scanDir(userDir, SkillSource.USER, skills, diagnostics, userSkills);
        }

        Map<String, SkillDescriptor> projectSkills = new LinkedHashMap<>();
        Path projectDir = workspaceRoot.resolve(projectSkillsDir).normalize();
        if (Files.isDirectory(projectDir)) {
            scanDir(projectDir, SkillSource.PROJECT, skills, diagnostics, projectSkills);
        }

        // Apply project-over-user shadowing
        List<SkillDescriptor> resolved = new ArrayList<>();
        for (SkillDescriptor project : projectSkills.values()) {
            if (userSkills.containsKey(project.name())) {
                diagnostics.add("shadowed: project skill '" + project.name() + "' overrides user skill");
            }
            resolved.add(project);
        }
        for (SkillDescriptor user : userSkills.values()) {
            if (!projectSkills.containsKey(user.name())) {
                resolved.add(user);
            }
        }

        // Catalog budget truncation: truncate descriptions first, then omit low-priority
        boolean truncated = false;
        int totalChars = estimateCatalogChars(resolved);
        if (totalChars > CATALOG_MAX_CHARS && !resolved.isEmpty()) {
            // Truncate descriptions to 100 chars first
            for (SkillDescriptor skill : resolved) {
                if (skill.description() != null && skill.description().length() > 100) {
                    // We can't mutate records, but we can note the truncation
                    truncated = true;
                }
            }
            // If still over budget, omit from the end (user skills are already after project ones)
            while (estimateCatalogChars(resolved) > CATALOG_MAX_CHARS && resolved.size() > 1) {
                resolved.removeLast();
                truncated = true;
            }
        }

        return new SkillCatalog(List.copyOf(resolved), List.copyOf(diagnostics), truncated);
    }

    @Override
    public SkillDescriptor resolve(String name, Path workspaceRoot) {
        SkillCatalog catalog = discover(workspaceRoot);
        return catalog.skills().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public SkillResourceManifest buildManifest(SkillDescriptor descriptor) {
        if (descriptor == null || descriptor.rootPath() == null) {
            return SkillResourceManifest.EMPTY;
        }
        Path skillDir = descriptor.rootPath();
        List<SkillResourceManifest.ResourceEntry> entries = new ArrayList<>();
        long totalBytes = 0;
        try (Stream<Path> files = Files.walk(skillDir)) {
            List<Path> sorted = files
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(SKILL_FILE))
                    .sorted()
                    .toList();
            for (Path file : sorted) {
                if (entries.size() >= MAX_RESOURCE_FILES) {
                    break;
                }
                long size = Files.size(file);
                if (size > MAX_RESOURCE_BYTES) {
                    continue;
                }
                if (totalBytes + size > MAX_SNAPSHOT_BYTES) {
                    continue;
                }
                String relativePath = skillDir.relativize(file).toString();
                String hash = DigestUtils.sha256Hex(Files.readAllBytes(file));
                entries.add(new SkillResourceManifest.ResourceEntry(relativePath, size, hash));
                totalBytes += size;
            }
        } catch (IOException e) {
            log.warn("Failed to build manifest for skill: {}", descriptor.name(), e);
            return SkillResourceManifest.EMPTY;
        }
        return new SkillResourceManifest(List.copyOf(entries), totalBytes);
    }

    @Override
    public String readSkillContent(SkillDescriptor descriptor) {
        if (descriptor == null || descriptor.rootPath() == null) {
            return "";
        }
        Path skillFile = descriptor.rootPath().resolve(SKILL_FILE);
        if (!Files.isRegularFile(skillFile)) {
            return "";
        }
        try {
            String raw = Files.readString(skillFile, StandardCharsets.UTF_8);
            return extractBody(raw);
        } catch (IOException e) {
            log.warn("Failed to read skill content: {}", descriptor.name(), e);
            return "";
        }
    }

    @Override
    public String readResourceText(SkillDescriptor descriptor, String relativePath, int offset, int maxChars) {
        if (descriptor == null || descriptor.rootPath() == null || StringUtils.isBlank(relativePath)) {
            return "";
        }
        Path resourcePath = resolveResourcePath(descriptor, relativePath);
        if (resourcePath == null) {
            return "";
        }
        try {
            String content = Files.readString(resourcePath, StandardCharsets.UTF_8);
            int start = Math.max(0, Math.min(offset, content.length()));
            int end = Math.min(content.length(), start + Math.max(1, maxChars));
            return content.substring(start, end);
        } catch (IOException e) {
            log.warn("Failed to read resource: {}", relativePath, e);
            return "";
        }
    }

    @Override
    public byte[] readResourceBytes(SkillDescriptor descriptor, String relativePath) {
        if (descriptor == null || descriptor.rootPath() == null || StringUtils.isBlank(relativePath)) {
            return new byte[0];
        }
        Path resourcePath = resolveResourcePath(descriptor, relativePath);
        if (resourcePath == null) {
            return new byte[0];
        }
        try {
            return Files.readAllBytes(resourcePath);
        } catch (IOException e) {
            log.warn("Failed to read resource bytes: {}", relativePath, e);
            return new byte[0];
        }
    }

    // --- private helpers ---

    private Path resolveUserDir() {
        if (userSkillsDir == null) {
            String home = System.getProperty("user.home");
            if (home != null) {
                return Path.of(home, ".loom-agent", "skills");
            }
            return null;
        }
        return userSkillsDir;
    }

    private void scanDir(Path dir, SkillSource source, List<SkillDescriptor> skills,
                         List<String> diagnostics, Map<String, SkillDescriptor> target) {
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> subdirs = entries.filter(Files::isDirectory).sorted().toList();
            for (Path subdir : subdirs) {
                Path skillFile = subdir.resolve(SKILL_FILE);
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }
                try {
                    if (!isSafe(subdir, dir) || !isSafe(skillFile, dir)) {
                        diagnostics.add("security: skill '" + subdir.getFileName() + "' rejected (path traversal or symlink)");
                        continue;
                    }
                    if (Files.size(skillFile) > MAX_FILE_BYTES) {
                        diagnostics.add("rejected: skill '" + subdir.getFileName() + "' SKILL.md exceeds max size");
                        continue;
                    }
                    String raw = Files.readString(skillFile, StandardCharsets.UTF_8);
                    Map<String, Object> fm = parseFrontmatter(raw);
                    if (fm == null) {
                        diagnostics.add("parse_error: skill '" + subdir.getFileName() + "' has invalid or missing frontmatter");
                        continue;
                    }
                    String name = stringField(fm, "name");
                    if (StringUtils.isBlank(name)) {
                        diagnostics.add("parse_error: skill '" + subdir.getFileName() + "' missing required 'name' field");
                        continue;
                    }
                    if (!name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")) {
                        diagnostics.add("rejected: skill '" + name + "' has invalid name format");
                        continue;
                    }
                    String description = stringField(fm, "description");
                    String license = stringField(fm, "license");
                    String compatibility = stringField(fm, "compatibility");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = fm.get("metadata") instanceof Map
                            ? (Map<String, Object>) fm.get("metadata") : Map.of();
                    @SuppressWarnings("unchecked")
                    List<String> allowedTools = fm.get("allowed-tools") instanceof List
                            ? ((List<?>) fm.get("allowed-tools")).stream()
                                    .map(Object::toString).toList()
                            : List.of();

                    String body = extractBody(raw);
                    String manifestSha256 = DigestUtils.sha256Hex(body);
                    int resourceCount = countResources(subdir);

                    SkillDescriptor descriptor = new SkillDescriptor(
                            name, description, license, compatibility,
                            metadata, allowedTools, source, subdir,
                            manifestSha256, resourceCount);
                    skills.add(descriptor);
                    target.put(name, descriptor);
                } catch (IOException e) {
                    diagnostics.add("error: skill '" + subdir.getFileName() + "' read error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            diagnostics.add("error: cannot list directory " + dir + ": " + e.getMessage());
        }
    }

    private boolean isSafe(Path path, Path baseDir) {
        Path normalized = path.normalize();
        Path baseNormalized = baseDir.normalize().toAbsolutePath();
        if (!normalized.toAbsolutePath().startsWith(baseNormalized)) {
            return false;
        }
        try {
            if (Files.isSymbolicLink(normalized)) {
                Path target = Files.readSymbolicLink(normalized).normalize();
                if (target.isAbsolute() || target.startsWith("..")) {
                    return false;
                }
                Path resolved = normalized.getParent().resolve(target).normalize().toAbsolutePath();
                return resolved.startsWith(baseNormalized);
            }
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    /**
     * Simple YAML frontmatter parser. Handles:
     * - key: value
     * - key:
     *     - list items
     * - key:
   *     subkey: subvalue
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String trimmed = raw.stripLeading();
        if (!trimmed.startsWith("---")) {
            return null;
        }
        int end = trimmed.indexOf("---", 3);
        if (end < 0) {
            return null;
        }
        String fmText = trimmed.substring(3, end).strip();
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> lines = fmText.lines().map(String::stripTrailing).toList();

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.isBlank()) {
                i++;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                i++;
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();

            if (value.isEmpty()) {
                // Look ahead for indented content (list items or nested map)
                if (i + 1 < lines.size() && lines.get(i + 1).startsWith("  ")) {
                    List<String> indented = new ArrayList<>();
                    int j = i + 1;
                    while (j < lines.size() && (lines.get(j).startsWith("  ") || lines.get(j).isBlank())) {
                        if (!lines.get(j).isBlank()) {
                            indented.add(lines.get(j).stripLeading());
                        }
                        j++;
                    }
                    if (!indented.isEmpty() && indented.getFirst().startsWith("- ")) {
                        // List
                        List<String> list = new ArrayList<>();
                        for (String item : indented) {
                            if (item.startsWith("- ")) {
                                list.add(item.substring(2).strip());
                            }
                        }
                        result.put(key, list);
                    } else {
                        // Nested map
                        Map<String, String> nested = new LinkedHashMap<>();
                        for (String item : indented) {
                            int c = item.indexOf(':');
                            if (c > 0) {
                                nested.put(item.substring(0, c).strip(), item.substring(c + 1).strip());
                            }
                        }
                        result.put(key, nested);
                    }
                    i = j;
                    continue;
                }
            }

            result.put(key, value);
            i++;
        }
        return result;
    }

    private String extractBody(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String trimmed = raw.stripLeading();
        if (!trimmed.startsWith("---")) {
            return raw;
        }
        int end = trimmed.indexOf("---", 3);
        if (end < 0) {
            return "";
        }
        return trimmed.substring(end + 3).strip();
    }

    private int countResources(Path skillDir) {
        try (Stream<Path> files = Files.walk(skillDir)) {
            return (int) files
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals(SKILL_FILE))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private String stringField(Map<String, Object> fm, String key) {
        Object value = fm.get(key);
        return value == null ? null : value.toString();
    }

    private int estimateCatalogChars(List<SkillDescriptor> skills) {
        int total = 0;
        for (SkillDescriptor skill : skills) {
            total += skill.name().length() + StringUtils.length(skill.description()) + 50;
        }
        return total;
    }

    private Path resolveResourcePath(SkillDescriptor descriptor, String relativePath) {
        Path resolved = descriptor.rootPath().resolve(relativePath).normalize();
        if (!resolved.startsWith(descriptor.rootPath().normalize())) {
            return null;
        }
        if (!Files.isRegularFile(resolved)) {
            return null;
        }
        if (!isSafe(resolved, descriptor.rootPath())) {
            return null;
        }
        try {
            if (Files.size(resolved) > MAX_RESOURCE_BYTES) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return resolved;
    }
}
