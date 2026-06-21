package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.adapter.port.WorkspacePort;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
                .description("列出工作区内的目录和文件，只返回只读观察结果")
                .inputSchema("{\"path\":\"相对路径，默认 .\",\"maxDepth\":\"1-3，默认 1\"}")
                .build();
    }

    @Override
    public ToolResult call(ToolCall call) {
        long startedAt = System.currentTimeMillis();
        try {
            Path path = resolvePath(call, "path", ".");
            if (!Files.isDirectory(path)) {
                return failure("not_directory", "不是目录：" + relative(call, path), startedAt);
            }
            int maxDepth = Math.max(1, Math.min(3, integer(call.getInput(), "maxDepth", 1)));
            int limit = Math.max(1, properties.getSearchMaxResults());
            AtomicInteger count = new AtomicInteger();
            StringBuilder output = new StringBuilder();
            try (Stream<Path> stream = Files.walk(path, maxDepth)) {
                stream.filter(item -> !item.equals(path))
                        .filter(item -> isAllowedPath(call, item))
                        .sorted(Comparator.comparing(Path::toString))
                        .limit(limit + 1L)
                        .forEach(item -> {
                            if (count.incrementAndGet() <= limit) {
                                output.append(Files.isDirectory(item) ? "D " : "F ")
                                        .append(relative(call, item))
                                        .append('\n');
                            }
                        });
            }
            boolean truncated = count.get() > limit;
            return ToolResult.success(output.toString(), truncated, elapsed(startedAt));
        } catch (Exception e) {
            return failure("list_dir_failed", e.getMessage(), startedAt);
        }
    }

}
