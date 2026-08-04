package cn.lunalhx.ai.domain.tool.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Recursive SHA-256 workspace snapshot used to detect tool-induced workspace
 * changes (mirrors loom-code {@code capture_workspace_snapshot}).
 */
public final class WorkspaceFingerprint {

    private static final Set<String> IGNORED = Set.of(
            ".git", ".loom-code", "__pycache__", ".pytest_cache", ".ruff_cache", ".venv", "venv");

    private WorkspaceFingerprint() {
    }

    public static Map<String, String> snapshot(Path root) {
        Map<String, String> map = new LinkedHashMap<>();
        if (root == null || !Files.isDirectory(root)) {
            return map;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !ignored(root, p))
                    .sorted()
                    .forEach(p -> map.put(relative(root, p), sha256(p)));
        } catch (IOException ignored) {
            // snapshot is best-effort
        }
        return map;
    }

    /** Compute affected paths + diff summary between two snapshots. */
    public static DiffResult diff(Map<String, String> before, Map<String, String> after) {
        List<String> affected = new ArrayList<>();
        List<String> diffSummary = new ArrayList<>();
        Set<String> keys = new java.util.LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            String b = before.get(key);
            String a = after.get(key);
            if (java.util.Objects.equals(b, a)) {
                continue;
            }
            affected.add(key);
            if (b == null) {
                diffSummary.add("+ " + key);
            } else if (a == null) {
                diffSummary.add("- " + key);
            } else {
                diffSummary.add("~ " + key);
            }
        }
        return new DiffResult(affected, diffSummary);
    }

    public record DiffResult(List<String> affectedPaths, List<String> diffSummary) {
    }

    private static boolean ignored(Path root, Path path) {
        Path rel = root.relativize(path);
        for (Path part : rel) {
            if (IGNORED.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String sha256(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, len);
            }
            return hex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
