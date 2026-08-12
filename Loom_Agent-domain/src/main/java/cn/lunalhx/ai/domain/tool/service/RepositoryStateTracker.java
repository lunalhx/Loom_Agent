package cn.lunalhx.ai.domain.tool.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Tracks worktree entries and Git logical state without following symlinks. */
public final class RepositoryStateTracker {
    private static final Set<String> RUNTIME_ARTIFACTS = Set.of(".loom-code");

    private RepositoryStateTracker() { }

    public static Map<String, String> snapshot(Path root) {
        Map<String, String> state = new LinkedHashMap<>();
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return state;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> !path.equals(root)).filter(path -> !runtimeArtifact(root, path))
                    .filter(path -> !insideGit(root, path)).filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(path)).sorted().forEach(path -> state.put(relative(root, path), digestEntry(path)));
        } catch (IOException ignored) { }
        captureGit(root, state);
        return state;
    }

    public static String stableFingerprint(Path root) {
        StringBuilder value = new StringBuilder();
        snapshot(root).forEach((path, digest) -> value.append(path).append('=').append(digest).append('\n'));
        return digest(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static DiffResult diff(Map<String, String> before, Map<String, String> after) {
        List<String> affected = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(before.keySet()); keys.addAll(after.keySet());
        for (String key : keys) {
            String oldValue = before.get(key), newValue = after.get(key);
            if (java.util.Objects.equals(oldValue, newValue)) continue;
            affected.add(key);
            summary.add(oldValue == null ? "+ " + key : newValue == null ? "- " + key : "~ " + key);
        }
        return new DiffResult(List.copyOf(affected), List.copyOf(summary));
    }

    public record DiffResult(List<String> affectedPaths, List<String> diffSummary) { }

    private static void captureGit(Path root, Map<String, String> state) {
        Path git = root.resolve(".git");
        if (Files.isRegularFile(git, LinkOption.NOFOLLOW_LINKS)) state.put("git:dir", digestEntry(git));
        if (!Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS)) return;
        for (String name : List.of("HEAD", "index", "packed-refs")) {
            Path file = git.resolve(name);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) state.put("git:" + name, digestEntry(file));
        }
        Path refs = git.resolve("refs");
        if (Files.isDirectory(refs, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> stream = Files.walk(refs)) {
                stream.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).sorted()
                        .forEach(file -> state.put("git:refs/" + relative(refs, file), digestEntry(file)));
            } catch (IOException ignored) { }
        }
    }

    private static boolean runtimeArtifact(Path root, Path path) {
        Path relative = root.relativize(path);
        return relative.getNameCount() > 0 && RUNTIME_ARTIFACTS.contains(relative.getName(0).toString());
    }
    private static boolean insideGit(Path root, Path path) {
        Path relative = root.relativize(path);
        return relative.getNameCount() > 0 && ".git".equals(relative.getName(0).toString());
    }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }
    private static String digestEntry(Path path) {
        try {
            if (Files.isSymbolicLink(path)) return digest(("symlink:\n" + Files.readSymbolicLink(path)).getBytes(StandardCharsets.UTF_8));
            try (InputStream input = Files.newInputStream(path)) { return digest(input.readAllBytes()); }
        } catch (Exception ignored) { return ""; }
    }
    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
}
