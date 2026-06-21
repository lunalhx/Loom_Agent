package cn.lunalhx.ai.infrastructure.tool;

import cn.lunalhx.ai.domain.agent.model.valobj.AgentRuntimeProperties;
import cn.lunalhx.ai.domain.tool.adapter.port.AgentTool;
import cn.lunalhx.ai.domain.tool.model.ToolCall;
import cn.lunalhx.ai.domain.tool.model.ToolResult;
import cn.lunalhx.ai.domain.tool.model.ToolSpec;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Component
public class CodeSearchTool extends FileSystemToolSupport implements AgentTool {

    public CodeSearchTool(AgentRuntimeProperties properties) {
        super(properties);
    }

    @Override
    public ToolSpec spec() {
        return ToolSpec.builder()
                .name("code_search")
                .description("在工作区内搜索代码文本，返回匹配文件、行号和代码片段")
                .inputSchema("{\"query\":\"必填搜索词\",\"path\":\"相对路径，默认 .\",\"limit\":\"默认 20\"}")
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
            Path path = resolvePath(call, "path", ".");
            int limit = Math.max(1, Math.min(properties.getSearchMaxResults(), integer(call.getInput(), "limit", 20)));
            AtomicInteger count = new AtomicInteger();
            StringBuilder output = new StringBuilder();
            String lowerQuery = query.toLowerCase(Locale.ROOT);
            try (Stream<Path> stream = Files.walk(path)) {
                for (Path file : stream.filter(file -> isAllowedRegularFile(call, file)).toList()) {
                    if (timedOut(startedAt) || count.get() >= limit) {
                        break;
                    }
                    searchFile(call, file, lowerQuery, count, limit, output);
                }
            }
            boolean truncated = count.get() >= limit || timedOut(startedAt);
            return ToolResult.success(output.toString(), truncated, elapsed(startedAt));
        } catch (Exception e) {
            return failure("code_search_failed", e.getMessage(), startedAt);
        }
    }

    private void searchFile(ToolCall call, Path file, String lowerQuery, AtomicInteger count, int limit, StringBuilder output) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size() && count.get() < limit; i++) {
                String line = lines.get(i);
                if (line.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                    count.incrementAndGet();
                    output.append(relative(call, file))
                            .append(':')
                            .append(i + 1)
                            .append(": ")
                            .append(line.strip())
                            .append('\n');
                }
            }
        } catch (Exception ignored) {
            // 非 UTF-8 或无法读取的文件对代码搜索不可见。
        }
    }

}
