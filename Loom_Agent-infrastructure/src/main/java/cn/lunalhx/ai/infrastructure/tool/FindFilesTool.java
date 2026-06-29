package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolPolicyDecision;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class FindFilesTool extends FileSystemToolSupport implements AgentTool {

    private static final int DEFAULT_MAX_DEPTH = 20;
    private static final int MIN_MAX_DEPTH = 1;
    private static final int MAX_MAX_DEPTH = 50;
    private static final Set<String> SKIP_DIR_NAMES = Set.of(".git", ".idea", "target", "node_modules");

    public FindFilesTool(AgentRuntimeProperties properties) {
        super(properties);
    }

    @Autowired
    public FindFilesTool(AgentRuntimeProperties properties, WorkspacePort workspacePort) {
        super(properties, workspacePort);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("find_files")
                .description("按文件名 Glob 模式递归查找文件，返回相对路径列表；搜索 .git/.idea/target/node_modules 等目录自动跳过")
                .inputSchema("{\"pattern\":\"必填 Glob 模式，如 *hello*.py 或 src/**/*.java\",\"path\":\"搜索起点，默认 .\",\"maxDepth\":\"1-50，默认 20\",\"limit\":\"最大结果数，默认 50\",\"caseSensitive\":\"默认 false\"}")
                .build();
    }

    @Override
    public ToolPolicyDecision policy(ToolCall call) {
        return ToolPolicyDecision.readOnly("find_files 只返回文件路径，不读取内容", spec().getName() + " " + inputPreview(call));
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            String pattern = text(call.getInput(), "pattern", null);
            if (pattern == null || pattern.isBlank()) {
                return failure("invalid_pattern", "pattern 不能为空", startedAt);
            }

            String rawPath = text(call.getInput(), "path", ".");
            Path searchRoot = resolveSearchRoot(call, rawPath);
            if (searchRoot == null) {
                return failure("not_found", "搜索起点不存在：" + rawPath, startedAt);
            }

            int maxDepth = Math.max(MIN_MAX_DEPTH, Math.min(MAX_MAX_DEPTH,
                    integer(call.getInput(), "maxDepth", DEFAULT_MAX_DEPTH)));
            int rawLimit = Math.max(1, integer(call.getInput(), "limit", properties.getSearchMaxResults()));
            final int limit = Math.min(rawLimit, properties.getSearchMaxResults());
            boolean caseSensitive = call.getInput() != null
                    && call.getInput().has("caseSensitive")
                    && call.getInput().get("caseSensitive").asBoolean(false);

            Path root = workspaceRoot(call);
            PathMatcher matcher = buildMatcher(root, searchRoot, pattern, caseSensitive);
            if (matcher == null) {
                return failure("invalid_pattern", "无效的 Glob 模式：" + pattern, startedAt);
            }

            List<String> results = new ArrayList<>();
            boolean[] timedOut = {false};
            Files.walkFileTree(searchRoot, java.util.Set.of(), maxDepth, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (timedOut(call, startedAt)) {
                        timedOut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (shouldSkip(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (timedOut(call, startedAt)) {
                        timedOut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (results.size() >= limit) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!Files.isRegularFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relPath = root.relativize(file.toAbsolutePath().normalize());
                    if (matcher.matches(relPath)) {
                        results.add("F " + relPath.toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            results.sort(Comparator.naturalOrder());

            StringBuilder output = new StringBuilder();
            if (results.isEmpty()) {
                output.append("未找到匹配文件：").append(pattern);
            } else {
                for (String result : results) {
                    output.append(result).append('\n');
                }
            }

            boolean truncated = timedOut[0] || results.size() >= limit;
            if (truncated && !results.isEmpty()) {
                output.append("\n结果已截断，建议缩小搜索范围");
            }

            return ToolResult.success(output.toString(), truncated, elapsed(startedAt));
        } catch (Exception e) {
            return failure("find_files_failed", e.getMessage(), startedAt);
        }
    }

    private Path resolveSearchRoot(ToolCall call, String rawPath) throws IOException {
        Path root = workspaceRoot(call);
        Path candidate = root.resolve(Path.of(rawPath)).normalize().toAbsolutePath();
        if (!candidate.startsWith(root)) {
            return null;
        }
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return candidate;
    }

    private PathMatcher buildMatcher(Path root, Path searchRoot, String pattern, boolean caseSensitive) {
        try {
            String normalized = pattern.replace('\\', '/');
            Path searchRel = root.relativize(searchRoot);

            String fullPattern;
            if (normalized.contains("/") || normalized.startsWith("**")) {
                if (searchRel.toString().isEmpty() || searchRel.toString().equals(".")) {
                    fullPattern = normalized;
                } else {
                    String prefix = searchRel.toString().replace('\\', '/');
                    fullPattern = prefix + "/" + normalized;
                }
            } else {
                if (searchRel.toString().isEmpty() || searchRel.toString().equals(".")) {
                    fullPattern = "**/" + normalized;
                } else {
                    String prefix = searchRel.toString().replace('\\', '/');
                    fullPattern = prefix + "/**/" + normalized;
                }
            }

            String regex = caseSensitive
                    ? globToRegex(fullPattern)
                    : globToCaseInsensitiveRegex(fullPattern);
            return FileSystems.getDefault().getPathMatcher("regex:" + regex);
        } catch (PatternSyntaxException | UnsupportedOperationException e) {
            return null;
        }
    }

    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        regex.append("(?:.*/)?");
                        i++;
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (c == '.') {
                regex.append("\\.");
            } else if (c == '{') {
                regex.append("(?:");
            } else if (c == '}') {
                regex.append(')');
            } else if (c == ',') {
                regex.append('|');
            } else if (c == '[' || c == ']' || c == '(' || c == ')' || c == '+'
                    || c == '^' || c == '$' || c == '\\' || c == '|') {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.toString();
    }

    private String globToCaseInsensitiveRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (Character.isLetter(c)) {
                regex.append('[').append(Character.toUpperCase(c))
                        .append(Character.toLowerCase(c)).append(']');
            } else if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        regex.append("(?:.*/)?");
                        i++;
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (c == '.') {
                regex.append("\\.");
            } else if (c == '{') {
                regex.append("(?:");
            } else if (c == '}') {
                regex.append(')');
            } else if (c == ',') {
                regex.append('|');
            } else if (c == '[' || c == ']' || c == '(' || c == ')' || c == '+' || c == '^' || c == '$' || c == '\\' || c == '|') {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.toString();
    }

    private boolean shouldSkip(Path dir) {
        String name = dir.getFileName().toString();
        if (SKIP_DIR_NAMES.contains(name)) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".key") || lower.endsWith(".pem") || lower.endsWith(".p12");
    }

    private boolean timedOut(ToolCall call, long startedAt) {
        return elapsed(startedAt) > properties.getToolTimeoutMs();
    }

    private String inputPreview(ToolCall call) {
        return call == null || call.getInput() == null ? "" : call.getInput().toString();
    }
}
