package cn.lunalhx.ai.infrastructure.loom;

import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Shared complete directory-listing semantics for execution and revalidation. */
public final class ListFilesObservationService {

    public static final String OBSERVATION_TYPE = "list_files";
    public static final String TOOL_SEMANTICS = "list_files:directory-entries:v1";

    public Observation observe(Path workspaceRoot, Path directory) throws IOException {
        Path root = workspaceRoot.toRealPath();
        Path dir = directory.toRealPath();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            throw new IOException("path is not a directory");
        }

        List<Entry> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(path -> !LoomToolSupport.isIgnored(path))
                    .map(path -> new Entry(Files.isDirectory(path), LoomToolSupport.relative(root, path)))
                    .forEach(entries::add);
        }
        entries.sort(Comparator.comparing(Entry::directory).reversed()
                .thenComparing(entry -> entry.relativePath().toLowerCase(Locale.ROOT))
                .thenComparing(Entry::relativePath));

        String normalizedScope = LoomToolSupport.relative(root, dir);
        if (normalizedScope.isBlank()) {
            normalizedScope = ".";
        }
        String canonicalEntries = String.join("\n", entries.stream()
                .map(Entry::canonical)
                .toList());
        return new Observation(normalizedScope, List.copyOf(entries),
                DigestUtils.sha256Hex(canonicalEntries));
    }

    public record Entry(boolean directory, String relativePath) {

        private String canonical() {
            return (directory ? "D" : "F") + "\t" + relativePath;
        }

        private String rendered() {
            return (directory ? "[D] " : "[F] ") + relativePath;
        }
    }

    public record Observation(String normalizedScope, List<Entry> entries, String stateDigest) {

        public String render() {
            if (entries.isEmpty()) {
                return "(empty)";
            }
            return String.join("\n", entries.stream().map(Entry::rendered).toList());
        }
    }
}
