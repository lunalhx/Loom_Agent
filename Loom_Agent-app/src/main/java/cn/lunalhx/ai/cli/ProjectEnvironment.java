package cn.lunalhx.ai.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Project-local {@code .env} loading mirroring loom-code {@code config.py}.
 *
 * <p>Supports {@code export} prefixes, legal variable-name validation and
 * paired quotes. The JVM global environment is never mutated; the merged
 * {@link ProjectEnvironment} is constructed instead.
 */
public final class ProjectEnvironment {

    private static final Pattern ENV_KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final Map<String, String> values;

    private ProjectEnvironment(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /** Merge order: system env < project .env (project wins). */
    public static ProjectEnvironment load(Path start) {
        Map<String, String> merged = new LinkedHashMap<>();
        System.getenv().forEach((k, v) -> merged.put(k, v));
        Path envPath = findProjectEnv(start);
        if (envPath != null) {
            parse(envPath).forEach(merged::put);
        }
        return new ProjectEnvironment(merged);
    }

    public String get(String name) {
        return values.get(name);
    }

    public String getOr(String name, String fallback) {
        String value = values.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    public Map<String, String> asMap() {
        return values;
    }

    public static Path findProjectEnv(Path start) {
        Path current = start.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        for (Path path = current; path != null; path = path.getParent()) {
            Path env = path.resolve(".env");
            if (Files.isRegularFile(env)) {
                return env;
            }
        }
        return null;
    }

    public static Map<String, String> parse(Path envPath) {
        Map<String, String> loaded = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(envPath)) {
                String parsed = parseLine(line);
                if (parsed == null) {
                    continue;
                }
                String[] parts = parsed.split("=", 2);
                loaded.put(parts[0], stripQuotes(parts[1]));
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read .env file " + envPath + ": " + e.getMessage(), e);
        }
        return loaded;
    }

    private static String parseLine(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        if (trimmed.startsWith("export ")) {
            trimmed = trimmed.substring("export ".length()).strip();
        }
        int eq = trimmed.indexOf('=');
        if (eq < 0) {
            throw new IllegalArgumentException("invalid .env line: " + line);
        }
        String name = trimmed.substring(0, eq).strip();
        if (!ENV_KEY_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid .env variable name: " + name);
        }
        return name + "=" + trimmed.substring(eq + 1);
    }

    private static String stripQuotes(String value) {
        String v = value.strip();
        if (v.length() >= 2 && v.charAt(0) == v.charAt(v.length() - 1)
                && (v.charAt(0) == '\'' || v.charAt(0) == '"')) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
