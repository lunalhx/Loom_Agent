package cn.lunalhx.ai.infrastructure.loom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Shared complete search semantics for execution and revalidation. */
public final class SearchObservationService {

    public static final String OBSERVATION_TYPE = "search";
    private static final String ENGINE = "rg";
    private static final String SEMANTICS_SUFFIX = ":json-line-matches-sort-path-v1";

    private final ObjectMapper mapper;

    public SearchObservationService() {
        this(new ObjectMapper());
    }

    SearchObservationService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Observation observe(Path workspaceRoot, Path scope, String query) throws IOException {
        if (query == null || query.isBlank()) {
            throw new IOException("pattern must not be empty");
        }
        Path root = workspaceRoot.toRealPath();
        Path resolvedScope = scope.toRealPath();
        if (!resolvedScope.startsWith(root)
                || (!Files.isDirectory(resolvedScope) && !Files.isRegularFile(resolvedScope))) {
            throw new IOException("search scope is not a file or directory");
        }
        String normalizedScope = LoomToolSupport.relative(root, resolvedScope);
        if (normalizedScope.isBlank()) {
            normalizedScope = ".";
        }

        String engineVersion = engineVersion();
        String semantics = semantics(engineVersion);
        List<Match> matches = runSearch(root, normalizedScope, query);
        String canonicalMatches = String.join("\n", matches.stream()
                .map(Match::canonical)
                .toList());
        String canonicalObservation = "query=" + query
                + "\nscope=" + normalizedScope
                + "\nsemantics=" + semantics
                + "\nmatches\n" + canonicalMatches;
        return new Observation(query, normalizedScope, engineVersion, semantics,
                List.copyOf(matches), DigestUtils.sha256Hex(canonicalObservation));
    }

    private static String semantics(String engineVersion) {
        return "search:" + ENGINE + ":" + engineVersion + SEMANTICS_SUFFIX;
    }

    private List<Match> runSearch(Path root, String scope, String query) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(ENGINE, "--json", "--smart-case", "--sort", "path",
                    "--", query, scope)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new IOException("required search engine is unavailable", e);
        }

        String output;
        int exitCode;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("search was interrupted", e);
        }
        if (exitCode != 0 && exitCode != 1) {
            String message = output.strip();
            throw new IOException(message.isEmpty() ? "search engine failed" : message);
        }

        List<Match> matches = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode event;
            try {
                event = mapper.readTree(line);
            } catch (Exception e) {
                throw new IOException("search engine returned incomplete output", e);
            }
            if (!"match".equals(event.path("type").asText())) {
                continue;
            }
            JsonNode data = event.path("data");
            String rawPath = data.path("path").path("text").asText(null);
            int lineNumber = data.path("line_number").asInt(0);
            String matchedLine = data.path("lines").path("text").asText(null);
            if (rawPath == null || lineNumber < 1 || matchedLine == null) {
                throw new IOException("search engine returned an unsupported match");
            }
            matches.add(new Match(normalizeMatchPath(root, rawPath), lineNumber,
                    normalizeMatchedLine(matchedLine)));
        }
        matches.sort(Comparator.comparing(Match::relativePath)
                .thenComparingInt(Match::lineNumber)
                .thenComparing(Match::line));
        return matches;
    }

    private String normalizeMatchPath(Path root, String rawPath) throws IOException {
        Path raw = Path.of(rawPath);
        Path absolute = raw.isAbsolute() ? raw : root.resolve(raw);
        Path normalized = absolute.normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("search engine returned a path outside the workspace");
        }
        return LoomToolSupport.relative(root, normalized);
    }

    private String normalizeMatchedLine(String line) {
        String normalized = line.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.endsWith("\n")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private String engineVersion() throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(ENGINE, "--version")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new IOException("required search engine is unavailable", e);
        }
        String output;
        int exitCode;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("search engine version lookup was interrupted", e);
        }
        if (exitCode != 0) {
            throw new IOException("required search engine version is unavailable");
        }
        String firstLine = output.lines().findFirst().orElse("").strip();
        String prefix = "ripgrep ";
        if (!firstLine.startsWith(prefix) || firstLine.substring(prefix.length()).isBlank()) {
            throw new IOException("required search engine version is unavailable");
        }
        return firstLine.substring(prefix.length()).strip();
    }

    public record Match(String relativePath, int lineNumber, String line) {

        private String canonical() {
            return relativePath + "\t" + lineNumber + "\t" + line;
        }

        private String rendered() {
            return relativePath + ":" + lineNumber + ":" + line;
        }
    }

    public record Observation(String normalizedQuery, String searchScope, String engineVersion,
                              String toolSemantics, List<Match> matches, String stateDigest) {

        public String normalizedScope() {
            return searchScope;
        }

        public String render() {
            if (matches.isEmpty()) {
                return "(no matches)";
            }
            return String.join("\n", matches.stream().map(Match::rendered).toList());
        }
    }
}
