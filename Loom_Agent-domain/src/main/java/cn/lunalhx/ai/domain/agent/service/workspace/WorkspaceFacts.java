package cn.lunalhx.ai.domain.agent.service.workspace;

import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Builds loom-code "Workspace Facts": cwd, repo root, current/default branch,
 * git status, last 5 commits, plus a navigation snippet of whitelisted project
 * docs (max 1200 chars each).
 *
 * <p>Structural identity (cwd, repo root, branch, default branch) is captured
 * separately as a {@code workspaceFingerprint} so that ordinary git status or
 * doc-content churn does not invalidate a {@code StablePrefix}; only a change
 * in the structural identity does.
 *
 * <p>Git commands are bounded to a 5-second timeout; non-git directories fall
 * back to stable values.
 */
public final class WorkspaceFacts {

    private static final String[] DOC_NAMES = {
            "AGENTS.md", "README.md", "pom.xml",
            "settings.gradle", "settings.gradle.kts",
            "build.gradle", "build.gradle.kts", "gradle.properties",
            "pyproject.toml", "package.json"};
    private static final int DOC_CHARS = 1200;
    private static final int STATUS_CHARS = 1500;
    private static final long GIT_TIMEOUT_SECONDS = 5;

    public record Facts(String cwd, String repoRoot, String branch, String defaultBranch,
                        String status, List<String> recentCommits, Map<String, String> projectDocs) {

        /** Deterministic fingerprint over everything actually rendered into the prompt:
         *  structural identity plus git status, recent commits and project docs.
         *  Any change (dirty status, new commit, doc edit) invalidates the prefix. */
        public String workspaceFingerprint() {
            return DigestUtils.sha256Hex(cwd + "\n" + repoRoot + "\n" + branch + "\n" + defaultBranch
                    + "\n" + status + "\n" + String.join("\n", recentCommits == null ? List.of() : recentCommits)
                    + "\n" + projectDocsText());
        }

        private String projectDocsText() {
            if (projectDocs == null || projectDocs.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : projectDocs.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            return sb.toString();
        }

        public String text() {
            StringBuilder sb = new StringBuilder("Workspace:\n");
            sb.append("- cwd: ").append(cwd).append('\n');
            sb.append("- repo_root: ").append(repoRoot).append('\n');
            sb.append("- branch: ").append(branch).append('\n');
            sb.append("- default_branch: ").append(defaultBranch).append('\n');
            sb.append("- status:\n").append(status).append('\n');
            sb.append("- recent_commits:\n");
            if (recentCommits == null || recentCommits.isEmpty()) {
                sb.append("  - none\n");
            } else {
                for (String c : recentCommits) {
                    sb.append("  - ").append(c).append('\n');
                }
            }
            sb.append("- project_docs:\n");
            if (projectDocs == null || projectDocs.isEmpty()) {
                sb.append("  - none\n");
            } else {
                for (Map.Entry<String, String> e : projectDocs.entrySet()) {
                    sb.append("  - ").append(e.getKey()).append('\n').append(e.getValue()).append('\n');
                }
            }
            return sb.toString().stripTrailing();
        }
    }

    private WorkspaceFacts() {
    }

    public static Facts build(Path cwd, Path repoRootOverride) {
        Path c = cwd.toAbsolutePath().normalize();
        String repoRoot = repoRootOverride != null
                ? repoRootOverride.toAbsolutePath().normalize().toString()
                : git(c, "rev-parse --show-toplevel", c.toString());
        Path root = Path.of(repoRoot);

        Map<String, String> docs = new LinkedHashMap<>();
        for (Path base : List.of(root, c)) {
            for (String name : DOC_NAMES) {
                Path p = base.resolve(name);
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String key = root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
                if (docs.containsKey(key)) {
                    continue;
                }
                docs.put(key, clip(readUtf8(p), DOC_CHARS));
            }
        }

        String defaultBranchRef = git(c, "symbolic-ref --short refs/remotes/origin/HEAD", "origin/main");
        if (defaultBranchRef == null || defaultBranchRef.isBlank()) {
            defaultBranchRef = "origin/main";
        }
        String defaultBranch = defaultBranchRef.startsWith("origin/")
                ? defaultBranchRef.substring("origin/".length()) : defaultBranchRef;

        List<String> commits = new ArrayList<>();
        for (String line : git(c, "log --oneline -5", "").split("\n")) {
            if (!line.isBlank()) {
                commits.add(line);
            }
        }

        return new Facts(
                c.toString(),
                repoRoot,
                git(c, "branch --show-current", "-"),
                defaultBranch,
                clip(git(c, "status --short", "clean"), STATUS_CHARS),
                commits,
                docs);
    }

    private static String git(Path cwd, String args, String fallback) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add("git");
            cmd.addAll(java.util.Arrays.asList(args.split("\\s+")));
            Process process = new ProcessBuilder(cmd)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return fallback;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return out.isEmpty() ? fallback : out;
        } catch (IOException | InterruptedException e) {
            return fallback;
        }
    }

    private static String readUtf8(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }
}
