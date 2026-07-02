package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CodeSearchTool extends FileSystemToolSupport implements AgentTool {

    public CodeSearchTool(AgentRuntimeProperties properties, WorkspacePort workspacePort) {
        super(properties, workspacePort);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("code_search")
                .description("在工作区内执行不区分大小写的文本子串搜索，返回匹配文件、行号和代码片段。不是语义搜索，不是正则搜索。何时使用：需要根据代码内容定位文件时。何时不要使用：按文件名查找请用 find_files，读取已知路径文件请用 read_file。权限：只读自动放行")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"query\":{\"type\":\"string\",\"minLength\":1,\"description\":\"搜索词，不区分大小写的文本子串搜索\"}," +
                        "\"path\":{\"type\":\"string\",\"default\":\".\",\"description\":\"相对路径\"}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"default\":20,\"description\":\"最大结果数\"}" +
                        "}," +
                        "\"required\":[\"query\"]," +
                        "\"additionalProperties\":false" +
                        "}")
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            String query = text(call.getInput(), "query", "");
            if (query.isBlank()) {
                return failure("query_required", "query 不能为空", startedAt);
            }
            Path searchRoot = resolveDirectory(call, "path", ".");
            int userLimit = Math.max(1, Math.min(properties.getSearchMaxResults(), integer(call.getInput(), "limit", 20)));
            int collectLimit = properties.getSearchMaxResults();
            AtomicInteger count = new AtomicInteger();
            List<SearchMatch> matches = new ArrayList<>();
            String lowerQuery = query.toLowerCase(Locale.ROOT);

            Files.walkFileTree(searchRoot, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (timedOut(startedAt) || count.get() >= collectLimit) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (isTraversalBlocked(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (timedOut(startedAt) || count.get() >= collectLimit) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!Files.isRegularFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (isSensitiveFileName(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        if (Files.size(file) > properties.getFileMaxBytes()) {
                            return FileVisitResult.CONTINUE;
                        }
                    } catch (IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    searchFile(call, file, lowerQuery, count, collectLimit, matches);
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

            // Sort deterministically: by path, then by line number within the same file.
            matches.sort(Comparator.comparing(SearchMatch::path).thenComparingInt(SearchMatch::lineNumber));

            StringBuilder output = new StringBuilder();
            int shown = 0;
            for (SearchMatch match : matches) {
                if (shown >= userLimit) {
                    break;
                }
                output.append(match.path())
                        .append(':')
                        .append(match.lineNumber())
                        .append(": ")
                        .append(match.line())
                        .append('\n');
                shown++;
            }

            boolean truncated = count.get() >= collectLimit || timedOut(startedAt) || matches.size() > userLimit;
            return ToolResult.success(output.toString(), truncated, elapsed(startedAt));
        } catch (Exception e) {
            return failure("code_search_failed", e.getMessage(), startedAt);
        }
    }

    private void searchFile(ToolCall call, Path file, String lowerQuery, AtomicInteger count, int collectLimit, List<SearchMatch> matches) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String relPath = relativeNormalized(call, file);
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null && count.get() < collectLimit) {
                lineNumber++;
                if (line.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    count.incrementAndGet();
                    matches.add(new SearchMatch(relPath, lineNumber, line.strip()));
                }
            }
        } catch (Exception ignored) {
            // Non-UTF-8 or unreadable files are invisible to code search.
        }
    }

    /**
     * A single search match with deterministic sort keys.
     *
     * @param path       workspace-relative normalized path ({@code /} separator)
     * @param lineNumber 1-based line number within the file
     * @param line       the matched line content, stripped
     */
    private record SearchMatch(String path, int lineNumber, String line) {}

}
