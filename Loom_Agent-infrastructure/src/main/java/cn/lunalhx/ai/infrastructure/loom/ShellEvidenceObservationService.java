package cn.lunalhx.ai.infrastructure.loom;

import cn.lunalhx.ai.domain.tool.model.EvidenceObservationType;
import cn.lunalhx.ai.domain.tool.model.EvidenceRevalidation;
import cn.lunalhx.ai.domain.tool.model.ToolEvidenceCandidate;
import cn.lunalhx.ai.domain.tool.service.RepositoryStateTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Re-observes the repository for a deliberately small set of Plan Shell forms.
 * Anything outside that set produces one whole-repository receipt; it never
 * tries to interpret stdout as evidence.
 */
final class ShellEvidenceObservationService {

    private static final int ALL_LINES = Integer.MAX_VALUE;
    private final ListFilesObservationService directories = new ListFilesObservationService();
    private final SearchObservationService searches = new SearchObservationService();

    List<ToolEvidenceCandidate> observe(Path root, String command) throws IOException {
        List<String> units = split(command);
        if (units == null) return List.of(repository(root));
        LinkedHashMap<String, ToolEvidenceCandidate> candidates = new LinkedHashMap<>();
        for (String unit : units) {
            ToolEvidenceCandidate candidate = observeUnit(root, unit);
            if (candidate == null) return List.of(repository(root));
            candidates.putIfAbsent(candidate.getEvidenceKey(), candidate);
        }
        return List.copyOf(candidates.values());
    }

    private ToolEvidenceCandidate observeUnit(Path root, String unit) throws IOException {
        String[] words = unit.trim().split("\\s+");
        if (words.length == 0 || words[0].isBlank()) return null;
        return switch (words[0]) {
            case "pwd" -> repository(root);
            case "git" -> git(root, words);
            case "ls" -> directory(root, words);
            case "cat", "wc", "stat", "file" -> file(root, words);
            case "rg" -> search(root, words);
            default -> null;
        };
    }

    private ToolEvidenceCandidate directory(Path root, String[] words) throws IOException {
        if (words.length > 2 || (words.length == 2 && words[1].startsWith("-"))) return null;
        Path directory = words.length == 1 ? root : resolve(root, words[1]);
        if (!Files.isDirectory(directory)) return null;
        ListFilesObservationService.Observation observation = directories.observe(root, directory);
        String scope = observation.normalizedScope();
        return ToolEvidenceCandidate.builder()
                .evidenceKey("shell:list_files|" + scope)
                .normalizedScope(scope).stateDigest(observation.stateDigest()).complete(true)
                .revalidation(EvidenceRevalidation.builder().digestAlgorithm("SHA-256")
                        .observationType(EvidenceObservationType.LIST_FILES)
                        .toolSemantics(ListFilesObservationService.TOOL_SEMANTICS)
                        .repositoryRelativePath(scope).build())
                .build();
    }

    private ToolEvidenceCandidate file(Path root, String[] words) throws IOException {
        if (words.length != 2 || words[1].startsWith("-")) return null;
        Path file = resolve(root, words[1]);
        if (!Files.isRegularFile(file)) return null;
        String relative = LoomToolSupport.relative(root, file);
        return ToolEvidenceCandidate.builder()
                .evidenceKey("shell:read_file|" + relative)
                .normalizedScope(relative + "#lines=1-all")
                .stateDigest(ReadFileEvidenceSupport.digest(file, 1, ALL_LINES)).complete(true)
                .revalidation(EvidenceRevalidation.builder().digestAlgorithm("SHA-256")
                        .observationType(EvidenceObservationType.READ_FILE)
                        .toolSemantics("read_file:utf8-lines:v2").repositoryRelativePath(relative)
                        .startLine(1).endLine(ALL_LINES).build())
                .build();
    }

    private ToolEvidenceCandidate search(Path root, String[] words) throws IOException {
        if (words.length < 2 || words.length > 3 || words[1].startsWith("-")) return null;
        Path scope = words.length == 3 ? resolve(root, words[2]) : root;
        SearchObservationService.Observation observation = searches.observe(root, scope, words[1]);
        return ToolEvidenceCandidate.builder()
                .evidenceKey("shell:search|" + observation.searchScope() + "|" + observation.normalizedQuery())
                .normalizedScope(observation.normalizedScope()).stateDigest(observation.stateDigest()).complete(true)
                .revalidation(EvidenceRevalidation.builder().digestAlgorithm("SHA-256")
                        .observationType(EvidenceObservationType.SEARCH).toolSemantics(observation.toolSemantics())
                        .repositoryRelativePath(observation.searchScope()).normalizedQuery(observation.normalizedQuery())
                        .searchScope(observation.searchScope()).engineVersion(observation.engineVersion()).build())
                .build();
    }

    private ToolEvidenceCandidate git(Path root, String[] words) {
        if (words.length < 2 || !(words[1].equals("status") || words[1].equals("diff")
                || words[1].equals("log") || words[1].equals("show") || words[1].equals("rev-parse"))) return null;
        return ToolEvidenceCandidate.builder()
                .evidenceKey("shell:git").normalizedScope("git:.")
                .stateDigest(RepositoryStateTracker.stableFingerprint(root)).complete(true)
                .revalidation(EvidenceRevalidation.builder().digestAlgorithm("SHA-256")
                        .observationType(EvidenceObservationType.GIT).toolSemantics("shell:git:v1")
                        .repositoryRelativePath(".").build())
                .build();
    }

    private ToolEvidenceCandidate repository(Path root) {
        return ToolEvidenceCandidate.builder()
                .evidenceKey("run_shell|repository").normalizedScope("repository:.")
                .stateDigest(RepositoryStateTracker.stableFingerprint(root)).complete(true)
                .revalidation(EvidenceRevalidation.builder().digestAlgorithm("SHA-256")
                        .observationType(EvidenceObservationType.REPOSITORY).toolSemantics("shell:repository:v1")
                        .repositoryRelativePath(".").build())
                .build();
    }

    private static Path resolve(Path root, String raw) throws IOException {
        Path candidate = root.resolve(raw).normalize();
        Path real = candidate.toRealPath();
        if (!real.startsWith(root.toRealPath())) throw new IOException("path escapes workspace");
        return real;
    }

    private static List<String> split(String command) {
        if (command == null || command.isBlank() || command.matches(".*[\"'`$<>{}*?()].*")
                || command.matches(".*(?<!&)&(?!&).*")) return null;
        String[] parts = command.split(";|\\n|&&|\\|\\||\\|");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part.isBlank()) return null;
            result.add(part.trim());
        }
        return result;
    }
}
