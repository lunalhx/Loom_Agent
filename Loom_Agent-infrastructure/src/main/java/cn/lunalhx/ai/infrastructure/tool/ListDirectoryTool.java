package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class ListDirectoryTool extends FileSystemToolSupport implements AgentTool {

    public ListDirectoryTool(AgentRuntimeProperties properties) {
        super(properties);
    }

    @Autowired
    public ListDirectoryTool(AgentRuntimeProperties properties, WorkspacePort workspacePort) {
        super(properties, workspacePort);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("list_dir")
                .description("列出已知工作区目录内的文件和子目录结构。何时使用：浏览已知路径的目录内容时。何时不要使用：名称未知需要递归按模式匹配时请用 find_files，搜索文件内容请用 code_search。限制：最大深度 3 层。权限：只读自动放行")
                .inputSchema("{" +
                        "\"type\":\"object\"," +
                        "\"properties\":{" +
                        "\"path\":{\"type\":\"string\",\"default\":\".\",\"description\":\"相对路径\"}," +
                        "\"maxDepth\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":3,\"default\":1,\"description\":\"最大深度\"}" +
                        "}," +
                        "\"additionalProperties\":false" +
                        "}")
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            Path path = resolveDirectory(call, "path", ".");
            if (!Files.isDirectory(path)) {
                return failure("not_directory", "不是目录：" + relative(call, path), startedAt);
            }
            int maxDepth = Math.max(1, Math.min(3, integer(call.getInput(), "maxDepth", 1)));
            int limit = Math.max(1, properties.getSearchMaxResults());

            List<String> entries = new ArrayList<>();
            Files.walkFileTree(path, java.util.Set.of(), maxDepth, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(path) && isTraversalBlocked(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (entries.size() >= limit + 1) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (isSensitiveFileName(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    entries.add((Files.isDirectory(file) ? "D " : "F ") + relative(call, file));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (!dir.equals(path)) {
                        entries.add("D " + relative(call, dir));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            entries.sort(Comparator.naturalOrder());

            StringBuilder output = new StringBuilder();
            int shown = 0;
            for (String entry : entries) {
                if (shown >= limit) {
                    break;
                }
                output.append(entry).append('\n');
                shown++;
            }

            boolean truncated = entries.size() > limit;
            return ToolResult.success(output.toString(), truncated, elapsed(startedAt));
        } catch (Exception e) {
            return failure("list_dir_failed", e.getMessage(), startedAt);
        }
    }

}
