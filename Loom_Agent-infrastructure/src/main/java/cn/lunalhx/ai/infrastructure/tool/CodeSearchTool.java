package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class CodeSearchTool extends FileSystemToolSupport implements AgentTool {

    public CodeSearchTool(AgentRuntimeProperties properties) {
        super(properties);
    }

    @Autowired
    public CodeSearchTool(AgentRuntimeProperties properties, WorkspacePort workspacePort) {
        super(properties, workspacePort);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("code_search")
                .description("在工作区内搜索代码文本，返回匹配文件、行号和代码片段")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"query\":{\"type\":\"string\",\"description\":\"搜索词\"}," +
                        "\"path\":{\"type\":\"string\",\"description\":\"相对路径，默认 .\"}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"最大结果数，默认 20\"}" +
                        "}," +
                        "\"required\":[\"query\"]" +
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
            int limit = Math.max(1, Math.min(properties.getSearchMaxResults(), integer(call.getInput(), "limit", 20)));
            AtomicInteger count = new AtomicInteger();
            StringBuilder output = new StringBuilder();
            String lowerQuery = query.toLowerCase(Locale.ROOT);

            Files.walkFileTree(searchRoot, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (timedOut(startedAt) || count.get() >= limit) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (isTraversalBlocked(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (timedOut(startedAt) || count.get() >= limit) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!Files.isRegularFile(file)) {
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
                    searchFile(call, file, lowerQuery, count, limit, output);
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

            boolean truncated = count.get() >= limit || timedOut(startedAt);
            return ToolResult.success(output.toString(), truncated, elapsed(startedAt));
        } catch (Exception e) {
            return failure("code_search_failed", e.getMessage(), startedAt);
        }
    }

    private void searchFile(ToolCall call, Path file, String lowerQuery, AtomicInteger count, int limit, StringBuilder output) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null && count.get() < limit) {
                lineNumber++;
                if (line.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    count.incrementAndGet();
                    output.append(relative(call, file))
                            .append(':')
                            .append(lineNumber)
                            .append(": ")
                            .append(line.strip())
                            .append('\n');
                }
            }
        } catch (Exception ignored) {
            // Non-UTF-8 or unreadable files are invisible to code search.
        }
    }

}
